#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Wall-clock handling. The dongle has no RTC: time comes from GNSS or from a
 * connected client (TIME:/POS: commands). Frames are stamped with uptime at
 * capture and converted here. */

typedef enum {
    CLOCK_SRC_NONE = 0,
    CLOCK_SRC_CLIENT,   /* app / PC */
    CLOCK_SRC_GNSS,
} clock_src_t;

/** Set wall time; a GNSS source is never overridden by a client source
 *  while GNSS time is fresh. Writes a time-sync record to the SD log. */
void clock_set_unix_ms(int64_t unix_ms, clock_src_t src);

bool clock_valid(void);
clock_src_t clock_source(void);

/** Convert an esp_timer uptime (µs) to the record timestamp: Unix time when
 *  the clock is valid, otherwise seconds since boot. */
void clock_stamp(int64_t uptime_us, uint32_t *sec, uint32_t *usec);

/** Current Unix time in ms, or 0 when unknown. */
int64_t clock_unix_ms(void);
