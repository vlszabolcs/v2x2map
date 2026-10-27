#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Standalone SD-card logging. While a card is mounted every captured frame
 * and every meta record (time sync, position) is appended to
 * /sdcard/its/NNNNNNNN.ITS using the same record framing as the live stream
 * (see records.h), so a downloaded file can be replayed by the same parser.
 *
 * All functions are safe to call when no card is present (they do nothing). */

typedef struct {
    bool     mounted;
    uint32_t cur_id;        /* 0 when no file is open */
    uint32_t cur_bytes;
    uint64_t free_bytes;
    uint64_t total_bytes;
    uint32_t dropped;       /* records lost because the buffer was full */
} sdlog_status_t;

typedef struct {
    uint32_t id;
    uint32_t size;
    bool     current;
} sdlog_file_t;

/** Mount the card (if CONFIG_ENABLE_SD) and start the writer task. */
void sdlog_init(void);

void sdlog_write_frame(uint32_t sec, uint32_t usec, const void *payload, uint16_t len, int8_t rssi);
void sdlog_write_json(const char *json, uint16_t len);

void sdlog_get_status(sdlog_status_t *st);

/** Fill up to max entries, oldest first; returns the number of files. */
int  sdlog_list(sdlog_file_t *out, int max);

/** Read part of a log file. For the open file only flushed data is returned.
 *  Returns bytes read, 0 at EOF, -1 on error. */
int  sdlog_read(uint32_t id, uint32_t offset, void *buf, size_t len);

/** Delete one file (id) or all closed files (id == 0). The open file is
 *  rotated first when it is the target. */
int  sdlog_delete(uint32_t id);

/** Close the current file and start a new one. Returns the closed file id. */
uint32_t sdlog_rotate(void);
