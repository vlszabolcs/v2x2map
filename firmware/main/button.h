#pragma once

/* BOOT button (GPIO28 on both supported boards):
 *   single press  → display on/off
 *   double press  → BLE on for the connect window / BLE off
 *   long press    → reset display counters (≥ 1.5 s) */
void button_init(void);
