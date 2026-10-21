#include "sdkconfig.h"

#include "display.h"

#if CONFIG_ENABLE_LCD

#include <math.h>
#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "font5x7.h"
#include "spi.h"
#if CONFIG_ENABLE_TEMPERATURE
#include "temperature.h"
#endif

static const char TAG[] = "DISPLAY";

#define LCD_W 160               /* landscape */
#define LCD_H 80
#define LCD_SPI_HOST 1          /* same host as spi.c (SPI2) */
#define LCD_CLOCK_HZ (20 * 1000 * 1000)
#define BAND_MAX_H 26
#define MAX_CHUNK 4000

#define STATION_SLOTS 32
#define STATION_WINDOW_MS 60000
#define RATE_BUCKETS 60

/* RGB565 */
#define C_BLACK  0x0000
#define C_WHITE  0xFFFF
#define C_GREY   0x8410
#define C_DGREY  0x2104
#define C_NAVY   0x0010
#define C_GREEN  0x07E0
#define C_ORANGE 0xFC00
#define C_YELLOW 0xFFE0
#define C_CYAN   0x07FF
#define C_RED    0xF800

typedef struct {
    int x, y;
    uint8_t scale;
    uint16_t fg;
    const char *s;
} seg_t;

static spi_device_handle_t s_dev;
static uint16_t *s_buf;
static bool s_ready;
static volatile bool s_on = true;

static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static struct {
    uint32_t frames;
    uint32_t its_frames;
    uint32_t secured;
    int8_t last_rssi;
    int64_t last_us;
    bool sniffing;
    uint32_t freq;
    bool ble;
    struct { uint8_t mac[6]; int64_t seen_us; } st[STATION_SLOTS];
    uint16_t buckets[RATE_BUCKETS];
    uint32_t bucket_sec;
} s;

/* ── low level ─────────────────────────────────────────────────────────── */

static void lcd_tx(bool data, const void *buf, size_t len)
{
    if (!len) return;
    gpio_set_level(CONFIG_LCD_PIN_DC, data ? 1 : 0);
    const uint8_t *p = buf;
    while (len) {
        size_t n = len > MAX_CHUNK ? MAX_CHUNK : len;
        spi_transaction_t t = { .length = n * 8, .tx_buffer = p };
        spi_device_polling_transmit(s_dev, &t);
        p += n;
        len -= n;
    }
}

static void lcd_cmd(uint8_t cmd, const uint8_t *data, size_t len)
{
    lcd_tx(false, &cmd, 1);
    if (len) lcd_tx(true, data, len);
}

static void lcd_window(int x0, int y0, int x1, int y1)
{
    x0 += CONFIG_LCD_X_OFFSET; x1 += CONFIG_LCD_X_OFFSET;
    y0 += CONFIG_LCD_Y_OFFSET; y1 += CONFIG_LCD_Y_OFFSET;
    uint8_t ca[4] = { x0 >> 8, x0 & 0xFF, x1 >> 8, x1 & 0xFF };
    uint8_t ra[4] = { y0 >> 8, y0 & 0xFF, y1 >> 8, y1 & 0xFF };
    lcd_cmd(0x2A, ca, 4);
    lcd_cmd(0x2B, ra, 4);
    lcd_cmd(0x2C, NULL, 0);
}

static void lcd_panel_init(void)
{
    /* hardware reset */
    gpio_set_level(CONFIG_LCD_PIN_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(20));
    gpio_set_level(CONFIG_LCD_PIN_RST, 0);
    vTaskDelay(pdMS_TO_TICKS(20));
    gpio_set_level(CONFIG_LCD_PIN_RST, 1);
    vTaskDelay(pdMS_TO_TICKS(120));

    /* Same sequence as LilyGO's ST7735 "green tab 160x80" init */
    lcd_cmd(0x01, NULL, 0); vTaskDelay(pdMS_TO_TICKS(150));       /* SWRESET */
    lcd_cmd(0x11, NULL, 0); vTaskDelay(pdMS_TO_TICKS(255));       /* SLPOUT  */
    lcd_cmd(0xB1, (const uint8_t[]){0x01, 0x2C, 0x2D}, 3);
    lcd_cmd(0xB2, (const uint8_t[]){0x01, 0x2C, 0x2D}, 3);
    lcd_cmd(0xB3, (const uint8_t[]){0x01, 0x2C, 0x2D, 0x01, 0x2C, 0x2D}, 6);
    lcd_cmd(0xB4, (const uint8_t[]){0x07}, 1);
    lcd_cmd(0xC0, (const uint8_t[]){0xA2, 0x02, 0x84}, 3);
    lcd_cmd(0xC1, (const uint8_t[]){0xC5}, 1);
    lcd_cmd(0xC2, (const uint8_t[]){0x0A, 0x00}, 2);
    lcd_cmd(0xC3, (const uint8_t[]){0x8A, 0x2A}, 2);
    lcd_cmd(0xC4, (const uint8_t[]){0x8A, 0xEE}, 2);
    lcd_cmd(0xC5, (const uint8_t[]){0x0E}, 1);
    lcd_cmd(0x21, NULL, 0);                                       /* INVON   */
    lcd_cmd(0x3A, (const uint8_t[]){0x05}, 1);                    /* 16 bpp  */
    lcd_cmd(0x36, (const uint8_t[]){0xA8}, 1);                    /* MY|MV|BGR: landscape */
    lcd_cmd(0xE0, (const uint8_t[]){0x02, 0x1c, 0x07, 0x12, 0x37, 0x32, 0x29, 0x2d,
                                    0x29, 0x25, 0x2B, 0x39, 0x00, 0x01, 0x03, 0x10}, 16);
    lcd_cmd(0xE1, (const uint8_t[]){0x03, 0x1d, 0x07, 0x06, 0x2E, 0x2C, 0x29, 0x2D,
                                    0x2E, 0x2E, 0x37, 0x3F, 0x00, 0x00, 0x02, 0x10}, 16);
    lcd_cmd(0x13, NULL, 0); vTaskDelay(pdMS_TO_TICKS(10));        /* NORON   */
    lcd_cmd(0x29, NULL, 0); vTaskDelay(pdMS_TO_TICKS(100));       /* DISPON  */
}

static void set_power(bool on)
{
    if (on) {
        lcd_cmd(0x29, NULL, 0);
        gpio_set_level(CONFIG_LCD_PIN_BL, 0);   /* active low */
    } else {
        gpio_set_level(CONFIG_LCD_PIN_BL, 1);
        lcd_cmd(0x28, NULL, 0);
    }
}

/* ── tiny renderer ─────────────────────────────────────────────────────── */

static inline uint16_t be(uint16_t c) { return (uint16_t)((c >> 8) | (c << 8)); }

static void draw_text(int h, const seg_t *g)
{
    int x = g->x;
    const uint16_t fg = be(g->fg);
    for (const char *p = g->s; *p; ++p) {
        char ch = *p;
        if (ch < 0x20 || ch > 0x7E) ch = '?';
        const uint8_t *glyph = font5x7[ch - 0x20];
        for (int col = 0; col < 5; ++col) {
            uint8_t bits = glyph[col];
            for (int row = 0; row < 7; ++row) {
                if (!(bits & (1 << row))) continue;
                for (int dy = 0; dy < g->scale; ++dy) {
                    int py = g->y + row * g->scale + dy;
                    if (py < 0 || py >= h) continue;
                    for (int dx = 0; dx < g->scale; ++dx) {
                        int px = x + col * g->scale + dx;
                        if (px >= 0 && px < LCD_W)
                            s_buf[py * LCD_W + px] = fg;
                    }
                }
            }
        }
        x += 6 * g->scale;
    }
}

static int right_x(const char *str, int scale)
{
    return LCD_W - 2 - (int)strlen(str) * 6 * scale;
}

static void draw_band(int y0, int h, uint16_t bg, const seg_t *segs, int n)
{
    const uint16_t b = be(bg);
    for (int i = 0; i < LCD_W * h; ++i) s_buf[i] = b;
    for (int i = 0; i < n; ++i) draw_text(h, &segs[i]);
    lcd_window(0, y0, LCD_W - 1, y0 + h - 1);
    lcd_tx(true, s_buf, (size_t)LCD_W * h * 2);
}

/* ── stats ─────────────────────────────────────────────────────────────── */

static void rate_roll(uint32_t now_sec)
{
    /* caller holds s_lock */
    if (now_sec - s.bucket_sec >= RATE_BUCKETS) {
        memset(s.buckets, 0, sizeof(s.buckets));
    } else {
        for (uint32_t t = s.bucket_sec + 1; t <= now_sec; ++t)
            s.buckets[t % RATE_BUCKETS] = 0;
    }
    s.bucket_sec = now_sec;
}

void display_on_packet(const void *payload, uint32_t len, int8_t rssi)
{
    if (!s_ready) return;
    const uint8_t *p = payload;
    int64_t now = esp_timer_get_time();
    uint32_t now_sec = (uint32_t)(now / 1000000);

    bool is_its = false, is_sec = false;
    const uint8_t *mac = NULL;
    if (len >= 24 && (p[0] & 0x0C) == 0x08) {             /* data frame */
        mac = &p[10];                                       /* transmitter */
        uint32_t hdr = 24 + ((p[0] & 0x80) ? 2 : 0);        /* QoS */
        if (len >= hdr + 9 && p[hdr] == 0xAA && p[hdr + 1] == 0xAA && p[hdr + 2] == 0x03 &&
            p[hdr + 6] == 0x89 && p[hdr + 7] == 0x47) {    /* LLC/SNAP, GeoNetworking */
            is_its = true;
            is_sec = (p[hdr + 8] & 0x0F) == 2;               /* GN basic header NH = secured */
        }
    }

    portENTER_CRITICAL(&s_lock);
    s.frames++;
    if (is_its) s.its_frames++;
    if (is_sec) s.secured++;
    s.last_rssi = rssi;
    s.last_us = now;
    rate_roll(now_sec);
    s.buckets[now_sec % RATE_BUCKETS]++;
    if (mac) {
        int slot = -1, oldest = 0;
        for (int i = 0; i < STATION_SLOTS; ++i) {
            if (s.st[i].seen_us && !memcmp(s.st[i].mac, mac, 6)) { slot = i; break; }
            if (s.st[i].seen_us < s.st[oldest].seen_us) oldest = i;
        }
        if (slot < 0) {
            slot = oldest;
            memcpy(s.st[slot].mac, mac, 6);
        }
        s.st[slot].seen_us = now;
    }
    portEXIT_CRITICAL(&s_lock);
}

void display_set_sniffer(bool running, uint32_t freq_mhz)
{
    portENTER_CRITICAL(&s_lock);
    s.sniffing = running;
    s.freq = freq_mhz;
    portEXIT_CRITICAL(&s_lock);
}

void display_set_ble_connected(bool connected)
{
    portENTER_CRITICAL(&s_lock);
    s.ble = connected;
    portEXIT_CRITICAL(&s_lock);
}

static void reset_stats(void)
{
    portENTER_CRITICAL(&s_lock);
    s.frames = s.its_frames = s.secured = 0;
    s.last_us = 0;
    s.last_rssi = 0;
    memset(s.st, 0, sizeof(s.st));
    memset(s.buckets, 0, sizeof(s.buckets));
    portEXIT_CRITICAL(&s_lock);
}

/* ── screen ────────────────────────────────────────────────────────────── */

static void render(void)
{
    int64_t now = esp_timer_get_time();
    uint32_t frames, its, sec, rate = 0, stations = 0, freq;
    int8_t rssi;
    int64_t last;
    bool sniff, ble;

    portENTER_CRITICAL(&s_lock);
    rate_roll((uint32_t)(now / 1000000));
    frames = s.frames; its = s.its_frames; sec = s.secured;
    rssi = s.last_rssi; last = s.last_us;
    sniff = s.sniffing; freq = s.freq; ble = s.ble;
    for (int i = 0; i < RATE_BUCKETS; ++i) rate += s.buckets[i];
    for (int i = 0; i < STATION_SLOTS; ++i)
        if (s.st[i].seen_us && now - s.st[i].seen_us < (int64_t)STATION_WINDOW_MS * 1000) stations++;
    portEXIT_CRITICAL(&s_lock);

    char a[28], b[28];

    /* header */
    snprintf(b, sizeof(b), sniff ? "RX %luMHz" : "PAUSED", (unsigned long)freq);
    seg_t hdr[] = {
        { 2, 2, 1, C_WHITE, "V2X2MAP" },
        { right_x(b, 1), 2, 1, sniff ? C_GREEN : C_ORANGE, b },
    };
    draw_band(0, 12, C_NAVY, hdr, 2);

    /* big frame counter */
    snprintf(a, sizeof(a), "%lu", (unsigned long)frames);
    int sc = strlen(a) > 6 ? 2 : 3;          /* 3x: up to 6 digits, 2x beyond */
    seg_t big[] = {
        { 2, sc == 3 ? 2 : 6, sc, C_WHITE, a },
        { right_x("frames", 1), 17, 1, C_GREY, "frames" },
    };
    draw_band(12, 26, C_BLACK, big, 2);

    /* rate + stations */
    snprintf(a, sizeof(a), "%lu/min", (unsigned long)rate);
    snprintf(b, sizeof(b), "Stn %lu", (unsigned long)stations);
    seg_t l1[] = { { 2, 1, 1, C_YELLOW, a }, { right_x(b, 1), 1, 1, C_CYAN, b } };
    draw_band(38, 10, C_BLACK, l1, 2);

    /* rssi + age */
    if (last) {
        unsigned age = (unsigned)((now - last) / 1000000);
        snprintf(a, sizeof(a), "%ddBm", rssi);
        if (age < 100) snprintf(b, sizeof(b), "%us ago", age);
        else if (age < 6000) snprintf(b, sizeof(b), "%um ago", age / 60);
        else snprintf(b, sizeof(b), "%uh ago", age / 3600);
    } else {
        snprintf(a, sizeof(a), "no frames");
        b[0] = 0;
    }
    seg_t l2[] = { { 2, 1, 1, C_WHITE, a }, { right_x(b, 1), 1, 1, C_GREY, b } };
    draw_band(48, 10, C_BLACK, l2, 2);

    /* secured share + temperature */
    if (its) snprintf(a, sizeof(a), "G5 %lu sec %lu%%", (unsigned long)its,
                      (unsigned long)(sec * 100 / its));
    else snprintf(a, sizeof(a), "G5 0");
    b[0] = 0;
    uint16_t tcol = C_GREY;
#if CONFIG_ENABLE_TEMPERATURE
    float t = temperature_get();
    if (!isnan(t)) {
        snprintf(b, sizeof(b), "%dC", (int)lroundf(t));
        tcol = t >= 75.0f ? C_RED : (t >= 60.0f ? C_ORANGE : C_GREY);
    }
#endif
    seg_t l3[] = { { 2, 1, 1, C_GREY, a }, { right_x(b, 1), 1, 1, tcol, b } };
    draw_band(58, 10, C_BLACK, l3, 2);

    /* footer: BLE + uptime */
    uint32_t up = (uint32_t)(now / 1000000);
    snprintf(b, sizeof(b), "%lu:%02lu:%02lu", (unsigned long)(up / 3600),
             (unsigned long)((up / 60) % 60), (unsigned long)(up % 60));
    seg_t ft[] = {
        { 2, 2, 1, ble ? C_GREEN : C_GREY, ble ? "BLE linked" : "BLE --" },
        { right_x(b, 1), 2, 1, C_GREY, b },
    };
    draw_band(68, 12, C_DGREY, ft, 2);
}

static void display_task(void *arg)
{
    (void)arg;
    lcd_panel_init();
    set_power(true);

    bool btn_prev = false;
    int64_t btn_down = 0;
    int64_t next_draw = 0;

    for (;;) {
#if CONFIG_LCD_BUTTON_PIN >= 0
        bool pressed = gpio_get_level(CONFIG_LCD_BUTTON_PIN) == 0;
        int64_t now = esp_timer_get_time();
        if (pressed && !btn_prev) btn_down = now;
        if (!pressed && btn_prev) {
            if (now - btn_down > 1500000) {
                reset_stats();              /* long press: clear counters */
                ESP_LOGI(TAG, "counters reset");
            } else {
                s_on = !s_on;               /* short press: screen on/off */
                set_power(s_on);
            }
            next_draw = 0;
        }
        btn_prev = pressed;
#else
        int64_t now = esp_timer_get_time();
#endif
        if (s_on && now >= next_draw) {
            render();
            next_draw = now + 500000;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

void display_init(void)
{
    if (!spi_bus_ready()) {
        ESP_LOGW(TAG, "SPI bus not available, display disabled");
        return;
    }

    gpio_config_t out = {
        .pin_bit_mask = (1ULL << CONFIG_LCD_PIN_DC) | (1ULL << CONFIG_LCD_PIN_RST) |
                        (1ULL << CONFIG_LCD_PIN_BL),
        .mode = GPIO_MODE_OUTPUT,
    };
    gpio_config(&out);
    gpio_set_level(CONFIG_LCD_PIN_BL, 1);
#if CONFIG_LCD_BUTTON_PIN >= 0
    gpio_config_t in = {
        .pin_bit_mask = 1ULL << CONFIG_LCD_BUTTON_PIN,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
    };
    gpio_config(&in);
#endif

    spi_device_interface_config_t dev = {
        .clock_speed_hz = LCD_CLOCK_HZ,
        .mode = 0,
        .spics_io_num = CONFIG_LCD_PIN_CS,
        .queue_size = 1,
    };
    esp_err_t err = spi_bus_add_device(LCD_SPI_HOST, &dev, &s_dev);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "spi_bus_add_device failed: %s", esp_err_to_name(err));
        return;
    }

    s_buf = heap_caps_malloc(LCD_W * BAND_MAX_H * 2, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    if (!s_buf) {
        ESP_LOGE(TAG, "no memory for line buffer");
        return;
    }

    s_ready = true;
    xTaskCreate(display_task, "display", 4096, NULL, 1, NULL);
}

#else /* !CONFIG_ENABLE_LCD */

void display_init(void) {}
void display_on_packet(const void *payload, uint32_t len, int8_t rssi)
{
    (void)payload; (void)len; (void)rssi;
}
void display_set_sniffer(bool running, uint32_t freq_mhz) { (void)running; (void)freq_mhz; }
void display_set_ble_connected(bool connected) { (void)connected; }

#endif
