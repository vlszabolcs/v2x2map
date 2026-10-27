#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

void usb_stream_init(void);
void usb_stream_publish_packet(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec, int8_t rssi);
void usb_stream_send_test_frame(void);

/**
 * Start the USB RX config channel task.
 * Listens for "CFG:key=value\n" commands from the setup wizard and writes
 * them to NVS. Acknowledges with "CFG_OK:key\n" or "CFG_ERR:msg\n".
 */
void usb_config_rx_start(void);

/** True while a USB-triggered WiFi scan is in progress. */
bool usb_cfg_is_scanning(void);

/** True while a USB host is actively talking to the port (SOF frames seen);
 *  false on a power bank / charger. */
bool usb_stream_host_connected(void);

/** Send a pre-framed record (ITSJ / ITSL / text) without interleaving. */
void usb_stream_send_record(const uint8_t *buf, size_t len);
