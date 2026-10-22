#pragma once

#include <stdint.h>

/* Text command channel shared by USB (line-based, next to the CFG: wizard
 * protocol) and BLE (writes to the control characteristic). Replies are
 * framed records (records.h) sent back on the channel the command came from.
 *
 *   HELLO:<client-id>                      BLE: identify a known client
 *   TIME:<unix_ms>                         set the clock
 *   POS:<unix_ms>,<lat>,<lon>,<alt>,<spd>,<hdg>,<acc>
 *   STATUS
 *   LOG_LIST
 *   LOG_GET:<id>:<offset>:<len>            → one ITSL chunk
 *   LOG_DEL:<id>|ALL
 *   LOG_ROTATE
 *   BLE_ON | BLE_OFF | BLE_FORGET
 */

typedef enum {
    CTRL_CH_USB = 0,
    CTRL_CH_BLE = 1,
} ctrl_channel_t;

void ctrl_init(void);

/** Queue a command line (without newline) for execution. */
void ctrl_submit(const char *line, ctrl_channel_t ch);

/** Build and send an ITSJ record on a channel. */
void ctrl_send_json(ctrl_channel_t ch, const char *json, uint16_t len);
