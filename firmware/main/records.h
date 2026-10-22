#pragma once

/* Wire / log record framing shared by the USB stream, the BLE notify stream
 * and the SD-card log files. Every record starts with a 4-byte ASCII magic so
 * a reader can resync on any of them:
 *
 *   "ITS5"  sec u32 | usec u32 | len u16 | payload[len]
 *           One captured 802.11 frame. sec >= 1e9 → Unix time (clock synced),
 *           otherwise seconds since boot.
 *
 *   "ITSJ"  len u16 | json[len]
 *           Control/meta record: command replies, status, time sync, position.
 *           Always a single JSON object with a "t" (type) field.
 *
 *   "ITSL"  file u32 | offset u32 | len u16 | data[len] | crc32 u32
 *           One chunk of a log file (reply to LOG_GET). The CRC covers data.
 *
 * All integers little-endian. */

#include <stdint.h>
#include <string.h>

#define REC_MAGIC_FRAME  "ITS5"
#define REC_MAGIC_JSON   "ITSJ"
#define REC_MAGIC_LOG    "ITSL"

#define REC_FRAME_HDR    14
#define REC_JSON_HDR     6
#define REC_LOG_HDR      14
#define REC_LOG_TRAILER  4

static inline void rec_put_u16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
static inline void rec_put_u32(uint8_t *p, uint32_t v)
{
    p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}

static inline void rec_frame_hdr(uint8_t *h, uint32_t sec, uint32_t usec, uint16_t len)
{
    memcpy(h, REC_MAGIC_FRAME, 4);
    rec_put_u32(h + 4, sec);
    rec_put_u32(h + 8, usec);
    rec_put_u16(h + 12, len);
}

static inline void rec_json_hdr(uint8_t *h, uint16_t len)
{
    memcpy(h, REC_MAGIC_JSON, 4);
    rec_put_u16(h + 4, len);
}
