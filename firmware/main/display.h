#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Status screen for the LilyGO T-Dongle-C5's 0.96" ST7735 (80x160).
 * All functions are no-ops when CONFIG_ENABLE_LCD is off, and are cheap
 * enough to call from the sniffer / BLE / event tasks. */
void display_init(void);
void display_on_packet(const void *payload, uint32_t len, int8_t rssi);
void display_set_sniffer(bool running, uint32_t freq_mhz);
void display_set_ble_connected(bool connected);
void display_toggle_power(void);   /* button: single press */
void display_reset_stats(void);    /* button: long press */
