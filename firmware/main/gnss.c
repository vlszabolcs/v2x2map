#include "sdkconfig.h"

#include "gnss.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "clock.h"
#include "sdlog.h"

#if CONFIG_ENABLE_GNSS
#include "driver/uart.h"
#endif


#define CLIENT_FRESH_US  (10LL * 1000000LL)

static portMUX_TYPE   s_lock = portMUX_INITIALIZER_UNLOCKED;
static gnss_state_t   s_st;
static volatile int64_t s_client_us;
static int64_t        s_last_nmea_us;

static void log_pos(const char *src, int64_t up_us, double lat, double lon, float alt,
                    float spd, float hdg, int fix, int sats, float hdop, float acc)
{
    char js[256];
    int n = snprintf(js, sizeof(js),
        "{\"t\":\"pos\",\"src\":\"%s\",\"up_us\":%lld,\"unix_ms\":%lld,"
        "\"lat\":%.7f,\"lon\":%.7f,\"alt\":%.1f,\"spd\":%.2f,\"hdg\":%.1f",
        src, (long long)up_us, (long long)clock_unix_ms(), lat, lon, alt, spd, hdg);
    if (n <= 0 || n >= (int)sizeof(js)) return;
    int m;
    if (fix >= 0) m = snprintf(js + n, sizeof(js) - n, ",\"fix\":%d,\"sats\":%d,\"hdop\":%.1f}", fix, sats, hdop);
    else          m = snprintf(js + n, sizeof(js) - n, ",\"acc\":%.1f}", acc);
    if (m <= 0 || n + m >= (int)sizeof(js)) return;
    sdlog_write_json(js, (uint16_t)(n + m));
}

void gnss_client_position(int64_t unix_ms, double lat, double lon, float alt_m,
                          float speed_mps, float course_deg, float acc_m, const char *via)
{
    int64_t now = esp_timer_get_time();
    if (unix_ms > 0) clock_set_unix_ms(unix_ms, CLOCK_SRC_CLIENT);
    if (isnan(lat) || isnan(lon)) return;          /* time only */
    s_client_us = now;
    log_pos(via, now, lat, lon, alt_m, speed_mps, course_deg, -1, 0, 0, acc_m);
}

bool gnss_client_fresh(void)
{
    return s_client_us && esp_timer_get_time() - s_client_us < CLIENT_FRESH_US;
}

void gnss_get(gnss_state_t *out)
{
    portENTER_CRITICAL(&s_lock);
    *out = s_st;
    portEXIT_CRITICAL(&s_lock);
    out->present = s_last_nmea_us && esp_timer_get_time() - s_last_nmea_us < 5000000LL;
}

#if CONFIG_ENABLE_GNSS

static const char TAG[] = "GNSS";
#define UART_PORT  CONFIG_GNSS_UART_NUM

/* ── NMEA helpers ─────────────────────────────────────────────────────── */

static bool nmea_checksum_ok(const char *s)
{
    if (*s != '$') return false;
    uint8_t c = 0;
    const char *p = s + 1;
    while (*p && *p != '*') c ^= (uint8_t)*p++;
    if (*p != '*') return false;
    unsigned v;
    return sscanf(p + 1, "%2x", &v) == 1 && v == c;
}

/* Split at commas in place; returns field count. */
static int split(char *s, char **f, int max)
{
    int n = 0;
    char *star = strchr(s, '*');
    if (star) *star = '\0';
    while (n < max) {
        f[n++] = s;
        char *c = strchr(s, ',');
        if (!c) break;
        *c = '\0';
        s = c + 1;
    }
    return n;
}

static double nmea_coord(const char *v, const char *hemi)
{
    if (!*v) return NAN;
    double raw = atof(v);
    int deg = (int)(raw / 100);
    double d = deg + (raw - deg * 100) / 60.0;
    if (*hemi == 'S' || *hemi == 'W') d = -d;
    return d;
}

/* days since 1970-01-01 for a civil date */
static int64_t days_from_civil(int y, int m, int d)
{
    y -= m <= 2;
    int64_t era = (y >= 0 ? y : y - 399) / 400;
    int64_t yoe = y - era * 400;
    int64_t doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

static uint8_t s_gsa_fix;
static uint8_t s_gga_q, s_gga_sats;
static float   s_gga_hdop, s_gga_alt;

static void handle_rmc(char **f, int n)
{
    if (n < 10) return;
    int64_t now = esp_timer_get_time();
    bool valid = f[2][0] == 'A';

    /* Time is useful even without a position fix once the date is known */
    if (strlen(f[1]) >= 6 && strlen(f[9]) == 6) {
        int hh = (f[1][0]-'0')*10 + (f[1][1]-'0');
        int mm = (f[1][2]-'0')*10 + (f[1][3]-'0');
        double ss = atof(f[1] + 4);
        int dd = (f[9][0]-'0')*10 + (f[9][1]-'0');
        int mo = (f[9][2]-'0')*10 + (f[9][3]-'0');
        int yy = 2000 + (f[9][4]-'0')*10 + (f[9][5]-'0');
        if (valid && yy >= 2024 && mo >= 1 && mo <= 12) {
            int64_t ms = (days_from_civil(yy, mo, dd) * 86400LL + hh * 3600 + mm * 60) * 1000LL
                         + (int64_t)llround(ss * 1000.0);
            clock_set_unix_ms(ms, CLOCK_SRC_GNSS);
        }
    }
    if (!valid) {
        portENTER_CRITICAL(&s_lock);
        s_st.fix = 0;
        portEXIT_CRITICAL(&s_lock);
        return;
    }

    double lat = nmea_coord(f[3], f[4]);
    double lon = nmea_coord(f[5], f[6]);
    float spd = f[7][0] ? (float)(atof(f[7]) * 0.514444) : 0.f;
    float hdg = f[8][0] ? (float)atof(f[8]) : NAN;
    uint8_t fix = s_gsa_fix >= 2 ? s_gsa_fix : (s_gga_q ? 3 : 2);

    portENTER_CRITICAL(&s_lock);
    s_st.fix = fix; s_st.lat = lat; s_st.lon = lon;
    s_st.speed_mps = spd; s_st.course_deg = hdg;
    s_st.sats = s_gga_sats; s_st.hdop = s_gga_hdop; s_st.alt_m = s_gga_alt;
    s_st.updated_us = now;
    portEXIT_CRITICAL(&s_lock);

    /* The client wins while it supplies positions */
    if (!gnss_client_fresh())
        log_pos("gnss", now, lat, lon, s_gga_alt, spd, isnan(hdg) ? -1.f : hdg,
                fix, s_gga_sats, s_gga_hdop, 0);
}

static void handle_gga(char **f, int n)
{
    if (n < 10) return;
    s_gga_q    = (uint8_t)atoi(f[6]);
    s_gga_sats = (uint8_t)atoi(f[7]);
    s_gga_hdop = f[8][0] ? (float)atof(f[8]) : 99.f;
    s_gga_alt  = f[9][0] ? (float)atof(f[9]) : 0.f;
}

static void handle_gsa(char **f, int n)
{
    if (n < 3) return;
    s_gsa_fix = (uint8_t)atoi(f[2]);   /* 1 none, 2 = 2D, 3 = 3D */
    if (s_gsa_fix < 2) s_gsa_fix = 0;
}

static void handle_line(char *line)
{
    if (!nmea_checksum_ok(line)) return;
    s_last_nmea_us = esp_timer_get_time();
    char *f[24];
    int n = split(line, f, 24);
    if (n < 1 || strlen(f[0]) < 6) return;
    const char *type = f[0] + 3;              /* skip "$GP"/"$GN"/… */
    if      (!strcmp(type, "RMC")) handle_rmc(f, n);
    else if (!strcmp(type, "GGA")) handle_gga(f, n);
    else if (!strcmp(type, "GSA")) handle_gsa(f, n);
}

/* ── u-blox power control ─────────────────────────────────────────────── */

static void ubx_send(uint8_t cls, uint8_t id, const uint8_t *pl, uint16_t len)
{
    uint8_t hdr[6] = { 0xB5, 0x62, cls, id, (uint8_t)len, (uint8_t)(len >> 8) };
    uint8_t ca = 0, cb = 0;
    for (int i = 2; i < 6; i++) { ca += hdr[i]; cb += ca; }
    for (int i = 0; i < len; i++) { ca += pl[i]; cb += ca; }
    uint8_t ck[2] = { ca, cb };
    uart_write_bytes(UART_PORT, hdr, 6);
    if (len) uart_write_bytes(UART_PORT, pl, len);
    uart_write_bytes(UART_PORT, ck, 2);
}

static void gnss_sleep(bool sleep)
{
    if (sleep == s_st.asleep) return;
#if CONFIG_GNSS_UBLOX
    if (sleep) {
        /* UBX-RXM-PMREQ v0 (16 B): infinite backup, wake on UART RX */
        uint8_t pl[16] = {0};
        pl[8]  = 0x02;          /* flags: backup */
        pl[12] = 0x08;          /* wakeupSources: uartrx */
        ubx_send(0x02, 0x41, pl, sizeof(pl));
    } else {
        uint8_t ff[16];
        memset(ff, 0xFF, sizeof(ff));
        uart_write_bytes(UART_PORT, ff, sizeof(ff));   /* any RX activity wakes it */
    }
#endif
    portENTER_CRITICAL(&s_lock);
    s_st.asleep = sleep;
    portEXIT_CRITICAL(&s_lock);
    ESP_LOGI(TAG, "%s", sleep ? "sleep (client supplies position)" : "wake");
}

static void gnss_task(void *arg)
{
    (void)arg;
    static char line[128];
    int pos = 0;
    uint8_t buf[128];
    int64_t last_check = 0;

    for (;;) {
        int n = uart_read_bytes(UART_PORT, buf, sizeof(buf), pdMS_TO_TICKS(200));
        for (int i = 0; i < n; i++) {
            char c = (char)buf[i];
            if (c == '$') pos = 0;
            if (c == '\r' || c == '\n') {
                if (pos > 0) { line[pos] = '\0'; handle_line(line); pos = 0; }
            } else if (pos < (int)sizeof(line) - 1) {
                line[pos++] = c;
            } else {
                pos = 0;
            }
        }
        int64_t now = esp_timer_get_time();
        if (now - last_check > 1000000) {
            last_check = now;
            gnss_sleep(gnss_client_fresh());
        }
    }
}

void gnss_init(void)
{
    s_st.enabled = true;
    uart_config_t cfg = {
        .baud_rate = CONFIG_GNSS_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    esp_err_t err = uart_driver_install(UART_PORT, 2048, 0, 0, NULL, 0);
    if (err == ESP_OK) err = uart_param_config(UART_PORT, &cfg);
    if (err == ESP_OK) err = uart_set_pin(UART_PORT, CONFIG_GNSS_TX_PIN, CONFIG_GNSS_RX_PIN,
                                          UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "UART setup failed: %s", esp_err_to_name(err));
        return;
    }
    xTaskCreate(gnss_task, "gnss", 4096, NULL, 3, NULL);
    ESP_LOGI(TAG, "GNSS on UART%d (RX=%d TX=%d, %d baud)", UART_PORT,
             CONFIG_GNSS_RX_PIN, CONFIG_GNSS_TX_PIN, CONFIG_GNSS_BAUD);
}

#else

void gnss_init(void) {}

#endif
