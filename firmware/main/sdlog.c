#include "sdkconfig.h"

#include "sdlog.h"

#if CONFIG_ENABLE_SD && CONFIG_SDLOG_ENABLE

#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_vfs_fat.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/stream_buffer.h"
#include "freertos/task.h"

#include "config.h"
#include "records.h"
#include "sdcard.h"

static const char TAG[] = "SDLOG";

#define LOG_DIR        CONFIG_SNIFFER_MOUNT_POINT "/its"
#define BUF_SIZE       (32 * 1024)
#define CHUNK          4096
#define FLUSH_MS       2000
#define ROTATE_BYTES   ((uint32_t)CONFIG_SDLOG_ROTATE_MB * 1024u * 1024u)
#define ROTATE_US      ((int64_t)CONFIG_SDLOG_ROTATE_MIN * 60LL * 1000000LL)
#define MIN_FREE_BYTES ((uint64_t)CONFIG_SDLOG_MIN_FREE_MB * 1024u * 1024u)

static bool               s_mounted;
static StreamBufferHandle_t s_sb;
static SemaphoreHandle_t  s_in_mtx;     /* serialises producers */
static SemaphoreHandle_t  s_file_mtx;   /* guards s_fp / file ops */
static FILE              *s_fp;
static uint32_t           s_cur_id;
static uint32_t           s_cur_bytes;
static uint32_t           s_flushed_bytes;
static int64_t            s_cur_started_us;
static uint32_t           s_next_id = 1;
static volatile uint32_t  s_dropped;
static volatile bool      s_rotate_req;

static void path_for(uint32_t id, char *out, size_t n)
{
    snprintf(out, n, LOG_DIR "/%08lu.ITS", (unsigned long)id);
}

static bool parse_name(const char *name, uint32_t *id)
{
    /* NNNNNNNN.ITS (FAT may report it in either case) */
    if (strlen(name) != 12) return false;
    if (strcasecmp(name + 8, ".ITS") != 0) return false;
    char *end;
    unsigned long v = strtoul(name, &end, 10);
    if (end != name + 8) return false;
    *id = (uint32_t)v;
    return true;
}

static int list_ids(uint32_t *ids, int max)
{
    DIR *d = opendir(LOG_DIR);
    if (!d) return 0;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        uint32_t id;
        if (!parse_name(e->d_name, &id)) continue;
        if (n < max) ids[n++] = id;
    }
    closedir(d);
    /* sort ascending (small n, insertion sort) */
    for (int i = 1; i < n; i++) {
        uint32_t v = ids[i]; int j = i - 1;
        while (j >= 0 && ids[j] > v) { ids[j + 1] = ids[j]; j--; }
        ids[j + 1] = v;
    }
    return n;
}

static void ensure_free_space(void)
{
    uint64_t total = 0, free_b = 0;
    if (esp_vfs_fat_info(CONFIG_SNIFFER_MOUNT_POINT, &total, &free_b) != ESP_OK) return;
    uint64_t want = MIN_FREE_BYTES;
    if (total / 20 > want) want = total / 20;          /* at least 5 % */

    uint32_t ids[256];
    int n = list_ids(ids, 256);
    for (int i = 0; i < n && free_b < want; i++) {
        if (ids[i] == s_cur_id) continue;
        char p[64]; path_for(ids[i], p, sizeof(p));
        struct stat st;
        uint64_t sz = (stat(p, &st) == 0) ? (uint64_t)st.st_size : 0;
        if (unlink(p) == 0) {
            ESP_LOGW(TAG, "card nearly full, deleted %s", p);
            free_b += sz;
        }
    }
}

/* caller holds s_file_mtx */
static void close_current(void)
{
    if (!s_fp) return;
    fflush(s_fp);
    fsync(fileno(s_fp));
    fclose(s_fp);
    s_fp = NULL;
    ESP_LOGI(TAG, "closed %08lu.ITS (%lu bytes)", (unsigned long)s_cur_id, (unsigned long)s_cur_bytes);
    s_cur_id = 0;
    s_cur_bytes = s_flushed_bytes = 0;
}

/* caller holds s_file_mtx */
static bool open_next(void)
{
    ensure_free_space();
    char p[64];
    path_for(s_next_id, p, sizeof(p));
    s_fp = fopen(p, "ab");
    if (!s_fp) {
        ESP_LOGE(TAG, "fopen %s failed: %d", p, errno);
        return false;
    }
    s_cur_id = s_next_id++;
    s_cur_bytes = s_flushed_bytes = 0;
    s_cur_started_us = esp_timer_get_time();

    /* File header record */
    char node[64] = "";
    size_t nsz = sizeof(node);
    config_get_str(CONFIG_INDEX_NODEID, node, &nsz);
    const esp_app_desc_t *app = esp_app_get_description();
    char js[200];
    int n = snprintf(js, sizeof(js),
                     "{\"t\":\"log\",\"v\":1,\"file\":%lu,\"node\":\"%s\",\"fw\":\"%s\",\"board\":\"%s\",\"up_us\":%lld}",
                     (unsigned long)s_cur_id, node, app->version, CONFIG_HW_VARIANT,
                     (long long)s_cur_started_us);
    uint8_t h[REC_JSON_HDR];
    rec_json_hdr(h, (uint16_t)n);
    fwrite(h, 1, sizeof(h), s_fp);
    fwrite(js, 1, n, s_fp);
    s_cur_bytes += sizeof(h) + n;
    ESP_LOGI(TAG, "logging to %s", p);
    return true;
}

static void writer_task(void *arg)
{
    (void)arg;
    static uint8_t chunk[CHUNK];
    int64_t last_flush = esp_timer_get_time();

    for (;;) {
        size_t n = xStreamBufferReceive(s_sb, chunk, sizeof(chunk), pdMS_TO_TICKS(500));
        int64_t now = esp_timer_get_time();

        xSemaphoreTake(s_file_mtx, portMAX_DELAY);
        if (s_rotate_req || (s_fp && (s_cur_bytes >= ROTATE_BYTES || now - s_cur_started_us >= ROTATE_US))) {
            s_rotate_req = false;
            close_current();
        }
        if (n > 0) {
            if (!s_fp) open_next();
            if (s_fp) {
                size_t w = fwrite(chunk, 1, n, s_fp);
                s_cur_bytes += w;
                if (w != n) ESP_LOGE(TAG, "short write (%u/%u)", (unsigned)w, (unsigned)n);
            }
        }
        if (s_fp && now - last_flush >= (int64_t)FLUSH_MS * 1000) {
            fflush(s_fp);
            fsync(fileno(s_fp));
            s_flushed_bytes = s_cur_bytes;
            last_flush = now;
        }
        xSemaphoreGive(s_file_mtx);
    }
}

void sdlog_init(void)
{
    if (!sdcard_mount_auto()) {
        ESP_LOGW(TAG, "no SD card – standalone logging disabled");
        return;
    }
    mkdir(LOG_DIR, 0775);

    uint32_t ids[256];
    int n = list_ids(ids, 256);
    if (n > 0) s_next_id = ids[n - 1] + 1;

    s_sb = xStreamBufferCreate(BUF_SIZE, 1);
    s_in_mtx = xSemaphoreCreateMutex();
    s_file_mtx = xSemaphoreCreateMutex();
    if (!s_sb || !s_in_mtx || !s_file_mtx) {
        ESP_LOGE(TAG, "out of memory");
        return;
    }
    s_mounted = true;
    xTaskCreate(writer_task, "sdlog", 4096, NULL, 2, NULL);
    ESP_LOGI(TAG, "SD logging ready, next file %08lu", (unsigned long)s_next_id);
}

static void put_record(const uint8_t *hdr, size_t hlen, const void *body, size_t blen)
{
    if (!s_mounted) return;
    if (xSemaphoreTake(s_in_mtx, pdMS_TO_TICKS(5)) != pdTRUE) { s_dropped++; return; }
    if (xStreamBufferSpacesAvailable(s_sb) < hlen + blen) {
        s_dropped++;
    } else {
        xStreamBufferSend(s_sb, hdr, hlen, 0);
        if (blen) xStreamBufferSend(s_sb, body, blen, 0);
    }
    xSemaphoreGive(s_in_mtx);
}

void sdlog_write_frame(uint32_t sec, uint32_t usec, const void *payload, uint16_t len, int8_t rssi)
{
    uint8_t h[REC_RSSI_LEN + REC_FRAME_HDR];
    size_t n = rec_rssi(h, rssi, usec);
    rec_frame_hdr(h + n, sec, usec, len);
    put_record(h, n + REC_FRAME_HDR, payload, len);
}

void sdlog_write_json(const char *json, uint16_t len)
{
    uint8_t h[REC_JSON_HDR];
    rec_json_hdr(h, len);
    put_record(h, sizeof(h), json, len);
}

void sdlog_get_status(sdlog_status_t *st)
{
    memset(st, 0, sizeof(*st));
    st->mounted = s_mounted;
    if (!s_mounted) return;
    st->cur_id = s_cur_id;
    st->cur_bytes = s_cur_bytes;
    st->dropped = s_dropped;
    esp_vfs_fat_info(CONFIG_SNIFFER_MOUNT_POINT, &st->total_bytes, &st->free_bytes);
}

int sdlog_list(sdlog_file_t *out, int max)
{
    if (!s_mounted) return 0;
    uint32_t ids[256];
    int n = list_ids(ids, 256);
    int k = 0;
    for (int i = 0; i < n && k < max; i++) {
        char p[64]; path_for(ids[i], p, sizeof(p));
        struct stat st;
        out[k].id = ids[i];
        out[k].current = (ids[i] == s_cur_id);
        out[k].size = out[k].current ? s_flushed_bytes
                                     : (stat(p, &st) == 0 ? (uint32_t)st.st_size : 0);
        k++;
    }
    return k;
}

int sdlog_read(uint32_t id, uint32_t offset, void *buf, size_t len)
{
    if (!s_mounted) return -1;
    xSemaphoreTake(s_file_mtx, portMAX_DELAY);
    uint32_t limit = UINT32_MAX;
    if (id == s_cur_id && s_fp) {
        fflush(s_fp);
        s_flushed_bytes = s_cur_bytes;
        limit = s_flushed_bytes;
    }
    int ret = -1;
    char p[64]; path_for(id, p, sizeof(p));
    FILE *f = fopen(p, "rb");
    if (f) {
        if (offset >= limit) {
            ret = 0;
        } else {
            if (limit != UINT32_MAX && offset + len > limit) len = limit - offset;
            if (fseek(f, offset, SEEK_SET) == 0) ret = (int)fread(buf, 1, len, f);
        }
        fclose(f);
    }
    xSemaphoreGive(s_file_mtx);
    return ret;
}

uint32_t sdlog_rotate(void)
{
    if (!s_mounted) return 0;
    xSemaphoreTake(s_file_mtx, portMAX_DELAY);
    uint32_t id = s_cur_id;
    close_current();
    xSemaphoreGive(s_file_mtx);
    return id;
}

int sdlog_delete(uint32_t id)
{
    if (!s_mounted) return -1;
    xSemaphoreTake(s_file_mtx, portMAX_DELAY);
    int deleted = 0;
    if (id == 0) {
        uint32_t ids[256];
        int n = list_ids(ids, 256);
        for (int i = 0; i < n; i++) {
            if (ids[i] == s_cur_id) continue;
            char p[64]; path_for(ids[i], p, sizeof(p));
            if (unlink(p) == 0) deleted++;
        }
    } else {
        if (id == s_cur_id) close_current();
        char p[64]; path_for(id, p, sizeof(p));
        if (unlink(p) == 0) deleted = 1;
    }
    xSemaphoreGive(s_file_mtx);
    return deleted;
}

#else /* no SD logging */

void sdlog_init(void) {}
void sdlog_write_frame(uint32_t sec, uint32_t usec, const void *payload, uint16_t len, int8_t rssi)
{ (void)sec; (void)usec; (void)payload; (void)len; (void)rssi; }
void sdlog_write_json(const char *json, uint16_t len) { (void)json; (void)len; }
void sdlog_get_status(sdlog_status_t *st) { *st = (sdlog_status_t){0}; }
int  sdlog_list(sdlog_file_t *out, int max) { (void)out; (void)max; return 0; }
int  sdlog_read(uint32_t id, uint32_t offset, void *buf, size_t len)
{ (void)id; (void)offset; (void)buf; (void)len; return -1; }
int  sdlog_delete(uint32_t id) { (void)id; return -1; }
uint32_t sdlog_rotate(void) { return 0; }

#endif
