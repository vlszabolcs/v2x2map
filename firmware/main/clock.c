#include "clock.h"

#include <stdio.h>
#include <sys/time.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"

#include "sdlog.h"

static const char TAG[] = "CLOCK";

/* unix_us = uptime_us + s_offset_us */
static volatile int64_t s_offset_us;
static volatile bool    s_valid;
static volatile clock_src_t s_src = CLOCK_SRC_NONE;
static volatile int64_t s_last_gnss_us;
static int64_t s_last_log_us;

void clock_set_unix_ms(int64_t unix_ms, clock_src_t src)
{
    if (unix_ms < 1600000000000LL) return;          /* before 2020: bogus */
    int64_t now_us = esp_timer_get_time();

    /* Prefer GNSS: ignore client time while GNSS time is younger than 30 s */
    if (src == CLOCK_SRC_CLIENT && s_src == CLOCK_SRC_GNSS && now_us - s_last_gnss_us < 30000000LL)
        return;
    if (src == CLOCK_SRC_GNSS) s_last_gnss_us = now_us;

    int64_t new_off = unix_ms * 1000LL - now_us;
    int64_t jump = s_valid ? (new_off - s_offset_us) : 0;
    bool first = !s_valid;

    s_offset_us = new_off;
    s_src = src;
    s_valid = true;

    struct timeval tv = { .tv_sec = unix_ms / 1000, .tv_usec = (unix_ms % 1000) * 1000 };
    settimeofday(&tv, NULL);

    /* Log a sync record on the first fix, on jumps > 50 ms, and once a minute */
    if (first || jump > 50000 || jump < -50000 || now_us - s_last_log_us > 60000000LL) {
        s_last_log_us = now_us;
        char js[128];
        int n = snprintf(js, sizeof(js),
                         "{\"t\":\"time\",\"up_us\":%lld,\"unix_ms\":%lld,\"src\":\"%s\"}",
                         (long long)now_us, (long long)unix_ms,
                         src == CLOCK_SRC_GNSS ? "gnss" : "client");
        if (n > 0) sdlog_write_json(js, (uint16_t)n);
        if (first) ESP_LOGI(TAG, "clock set from %s", src == CLOCK_SRC_GNSS ? "GNSS" : "client");
    }
}

bool clock_valid(void) { return s_valid; }
clock_src_t clock_source(void) { return s_src; }

void clock_stamp(int64_t uptime_us, uint32_t *sec, uint32_t *usec)
{
    int64_t t = s_valid ? uptime_us + s_offset_us : uptime_us;
    if (t < 0) t = 0;
    *sec  = (uint32_t)(t / 1000000LL);
    *usec = (uint32_t)(t % 1000000LL);
}

int64_t clock_unix_ms(void)
{
    if (!s_valid) return 0;
    return (esp_timer_get_time() + s_offset_us) / 1000LL;
}
