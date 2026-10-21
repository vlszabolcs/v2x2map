#pragma once

#include <stdint.h>

/* Minimal bit-banged driver for a single onboard APA102 (LilyGO T-Dongle-C5).
 * Thread/ISR-context safe: the whole frame is clocked out inside a spinlock. */
void apa102_init(void);
void apa102_set(uint8_t r, uint8_t g, uint8_t b);
