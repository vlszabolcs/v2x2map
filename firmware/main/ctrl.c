#include "sdkconfig.h"

#include "ctrl.h"

#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "bt_stream.h"
#include "clock.h"
#include "config.h"
#include "gnss.h"
#include "records.h"
#include "sdlog.h"
#include "usb_stream.h"

static const char TAG[] = "CTRL";

#define CTRL_LINE_MAX 200
#define USB_CHUNK  4096
#define BLE_CHUNK  1024

typedef struct {
    ctrl_channel_t ch;
    char line[CTRL_LINE_MAX];
} ctrl_msg_t;

static QueueHandle_t s_q;

/* Plain CRC-32 (IEEE 802.3, same as zlib / java.util.zip.CRC32) */
static uint32_t crc32_ieee(const uint8_t *p, size_t n)
{
    uint32_t c = 0xFFFFFFFFu;
    while (n--) {
        c ^= *p++;
        for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
    return ~c;
}

static void send_raw(ctrl_channel_t ch, const uint8_t *buf, size_t len)
{
    if (ch == CTRL_CH_USB) usb_stream_send_record(buf, len);
    else                   bt_stream_send_record(buf, len);
}

void ctrl_send_json(ctrl_channel_t ch, const char *json, uint16_t len)
{
    uint8_t *buf = malloc(REC_JSON_HDR + len);
    if (!buf) return;
    rec_json_hdr(buf, len);
    memcpy(buf + REC_JSON_HDR, json, len);
    send_raw(ch, buf, REC_JSON_HDR + len);
    free(buf);
}

static void reply(ctrl_channel_t ch, const char *fmt, ...)
{
    char js[384];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(js, sizeof(js), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    if (n >= (int)sizeof(js)) n = sizeof(js) - 1;
    ctrl_send_json(ch, js, (uint16_t)n);
}

static void cmd_status(ctrl_channel_t ch)
{
    sdlog_status_t sd;
    sdlog_get_status(&sd);
    gnss_state_t g;
    gnss_get(&g);
    char node[64] = "";
    size_t nsz = sizeof(node);
    config_get_str(CONFIG_INDEX_NODEID, node, &nsz);
    reply(ch,
          "{\"t\":\"status\",\"node\":\"%s\",\"fw\":\"%s\",\"board\":\"%s\",\"up_s\":%lld,"
          "\"clock\":%s,\"ble\":\"%s\",\"usb_host\":%s,"
          "\"sd\":{\"ok\":%s,\"cur\":%lu,\"free_mb\":%llu,\"total_mb\":%llu,\"drop\":%lu},"
          "\"gnss\":{\"on\":%s,\"present\":%s,\"sleep\":%s,\"fix\":%u,\"sats\":%u},"
          "\"client_pos\":%s}",
          node, esp_app_get_description()->version, CONFIG_HW_VARIANT,
          (long long)(esp_timer_get_time() / 1000000),
          clock_valid() ? (clock_source() == CLOCK_SRC_GNSS ? "\"gnss\"" : "\"client\"") : "null",
          bt_stream_mode_name(), usb_stream_host_connected() ? "true" : "false",
          sd.mounted ? "true" : "false", (unsigned long)sd.cur_id,
          (unsigned long long)(sd.free_bytes >> 20), (unsigned long long)(sd.total_bytes >> 20),
          (unsigned long)sd.dropped,
          g.enabled ? "true" : "false", g.present ? "true" : "false", g.asleep ? "true" : "false",
          g.fix, g.sats, gnss_client_fresh() ? "true" : "false");
}

static void cmd_log_list(ctrl_channel_t ch)
{
    static sdlog_file_t files[64];
    int n = sdlog_list(files, 64);
    sdlog_status_t sd;
    sdlog_get_status(&sd);

    /* One record may hold ~64 entries of ~40 B; keep it under 3 KB */
    char *js = malloc(3072);
    if (!js) return;
    int p = snprintf(js, 3072, "{\"t\":\"loglist\",\"sd\":%s,\"free_mb\":%llu,\"files\":[",
                     sd.mounted ? "true" : "false", (unsigned long long)(sd.free_bytes >> 20));
    for (int i = 0; i < n && p < 3000; i++) {
        p += snprintf(js + p, 3072 - p, "%s{\"id\":%lu,\"size\":%lu%s}", i ? "," : "",
                      (unsigned long)files[i].id, (unsigned long)files[i].size,
                      files[i].current ? ",\"cur\":true" : "");
    }
    p += snprintf(js + p, 3072 - p, "]}");
    ctrl_send_json(ch, js, (uint16_t)p);
    free(js);
}

static void cmd_log_get(ctrl_channel_t ch, const char *args)
{
    unsigned long id = 0, off = 0, len = 0;
    if (sscanf(args, "%lu:%lu:%lu", &id, &off, &len) != 3 || id == 0) {
        reply(ch, "{\"t\":\"err\",\"cmd\":\"LOG_GET\",\"msg\":\"args\"}");
        return;
    }
    size_t max = (ch == CTRL_CH_USB) ? USB_CHUNK : BLE_CHUNK;
    if (len == 0 || len > max) len = max;
    if (ch == CTRL_CH_BLE) bt_stream_bulk_activity();

    uint8_t *buf = malloc(REC_LOG_HDR + len + REC_LOG_TRAILER);
    if (!buf) return;
    int n = sdlog_read((uint32_t)id, (uint32_t)off, buf + REC_LOG_HDR, len);
    if (n < 0) {
        free(buf);
        reply(ch, "{\"t\":\"err\",\"cmd\":\"LOG_GET\",\"id\":%lu,\"msg\":\"read\"}", id);
        return;
    }
    memcpy(buf, REC_MAGIC_LOG, 4);
    rec_put_u32(buf + 4, (uint32_t)id);
    rec_put_u32(buf + 8, (uint32_t)off);
    rec_put_u16(buf + 12, (uint16_t)n);
    rec_put_u32(buf + REC_LOG_HDR + n, crc32_ieee(buf + REC_LOG_HDR, n));
    send_raw(ch, buf, REC_LOG_HDR + n + REC_LOG_TRAILER);
    free(buf);
}

static double fld(char **p)
{
    char *s = *p;
    if (!s) return NAN;
    char *c = strchr(s, ',');
    if (c) { *c = '\0'; *p = c + 1; } else { *p = NULL; }
    return *s ? atof(s) : NAN;
}

static void cmd_pos(ctrl_channel_t ch, char *args)
{
    char *p = args;
    double t   = fld(&p);
    double lat = fld(&p), lon = fld(&p), alt = fld(&p);
    double spd = fld(&p), hdg = fld(&p), acc = fld(&p);
    gnss_client_position(isnan(t) ? 0 : (int64_t)t, lat, lon,
                         isnan(alt) ? 0.f : (float)alt, isnan(spd) ? 0.f : (float)spd,
                         isnan(hdg) ? -1.f : (float)hdg, isnan(acc) ? 0.f : (float)acc,
                         ch == CTRL_CH_USB ? "usb" : "ble");
}

static void handle(ctrl_msg_t *m)
{
    char *line = m->line;
    ctrl_channel_t ch = m->ch;

    if (!strncmp(line, "POS:", 4))          { cmd_pos(ch, line + 4); return; }
    if (!strncmp(line, "TIME:", 5))         {
        clock_set_unix_ms(atoll(line + 5), CLOCK_SRC_CLIENT);
        return;
    }
    if (!strncmp(line, "LOG_GET:", 8))      { cmd_log_get(ch, line + 8); return; }
    if (!strcmp(line, "LOG_LIST"))          { cmd_log_list(ch); return; }
    if (!strcmp(line, "STATUS"))            { cmd_status(ch); return; }
    if (!strncmp(line, "LOG_DEL:", 8)) {
        const char *a = line + 8;
        uint32_t id = (!strcmp(a, "ALL")) ? 0 : (uint32_t)strtoul(a, NULL, 10);
        int n = sdlog_delete(id);
        reply(ch, "{\"t\":\"logdel\",\"id\":%lu,\"deleted\":%d}", (unsigned long)id, n);
        return;
    }
    if (!strcmp(line, "LOG_ROTATE")) {
        uint32_t id = sdlog_rotate();
        reply(ch, "{\"t\":\"logrotate\",\"closed\":%lu}", (unsigned long)id);
        return;
    }
    if (!strncmp(line, "HELLO:", 6)) {
        bool ok = bt_stream_hello(line + 6, ch == CTRL_CH_BLE);
        reply(ch, "{\"t\":\"hello\",\"ok\":%s}", ok ? "true" : "false");
        return;
    }
    if (!strcmp(line, "BLE_ON"))     { bt_stream_ble_on();  reply(ch, "{\"t\":\"ble\",\"mode\":\"%s\"}", bt_stream_mode_name()); return; }
    if (!strcmp(line, "BLE_OFF"))    { bt_stream_ble_off(); reply(ch, "{\"t\":\"ble\",\"mode\":\"%s\"}", bt_stream_mode_name()); return; }
    if (!strcmp(line, "BLE_FORGET")) { bt_stream_forget(); reply(ch, "{\"t\":\"ble\",\"forgot\":true}"); return; }

    reply(ch, "{\"t\":\"err\",\"msg\":\"unknown command\"}");
}

static void ctrl_task(void *arg)
{
    (void)arg;
    static ctrl_msg_t m;
    for (;;) {
        if (xQueueReceive(s_q, &m, portMAX_DELAY) == pdTRUE) handle(&m);
    }
}

void ctrl_init(void)
{
    s_q = xQueueCreate(8, sizeof(ctrl_msg_t));
    xTaskCreate(ctrl_task, "ctrl", 6144, NULL, 3, NULL);
}

void ctrl_submit(const char *line, ctrl_channel_t ch)
{
    if (!s_q || !line || !*line) return;
    ctrl_msg_t m = { .ch = ch };
    strncpy(m.line, line, sizeof(m.line) - 1);
    if (xQueueSend(s_q, &m, 0) != pdTRUE) ESP_LOGW(TAG, "queue full, dropped: %.20s", line);
}
