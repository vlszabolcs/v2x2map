#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* BLE link state as driven by the operating-mode machine:
 *   OFF        no advertising, sniffer runs continuously
 *   OPEN       connectable for a limited window (boot, double press, BLE_ON)
 *   CONNECTED  a client is connected (radio time-shared with the sniffer)
 *   RECONNECT  client lost by range: short, increasingly rare adverts; only
 *              the known client (HELLO id) is accepted, for up to 60 min */
typedef enum {
    BLE_MODE_OFF = 0,
    BLE_MODE_OPEN,
    BLE_MODE_CONNECTED,
    BLE_MODE_RECONNECT,
} ble_mode_t;

void bt_stream_init(void);
void bt_stream_publish_packet(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec);

/** Queue a pre-framed record (ITSJ / ITSL) on the notify stream. */
void bt_stream_send_record(const uint8_t *buf, size_t len);

/** Stop BLE advertising (frees the 2.4 GHz radio for WiFi scanning). */
void bt_stream_pause(void);
/** Restart BLE advertising after bt_stream_pause(). */
void bt_stream_resume(void);

ble_mode_t  bt_stream_mode(void);
const char *bt_stream_mode_name(void);
/** Seconds left in the OPEN window (0 otherwise). */
int         bt_stream_window_left_s(void);

void bt_stream_ble_on(void);      /* open a connect window */
void bt_stream_ble_off(void);     /* drop client, stop advertising */
void bt_stream_ble_toggle(void);  /* double press */
void bt_stream_forget(void);      /* clear the remembered client id */

/** A client identified itself; returns true when accepted. */
bool bt_stream_hello(const char *client_id, bool via_ble);

/** Mark a BLE bulk transfer (log download): keeps the radio on BLE. */
void bt_stream_bulk_activity(void);
