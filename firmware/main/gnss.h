#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Position for the SD log: from an optional UART GNSS module (NMEA), or from
 * a connected client (app/PC) via the POS: command. While the client supplies
 * fresh positions the GNSS module is put to sleep (u-blox UBX-RXM-PMREQ). */

typedef struct {
    bool     enabled;      /* CONFIG_ENABLE_GNSS */
    bool     present;      /* NMEA received within the last 5 s */
    bool     asleep;
    uint8_t  fix;          /* 0 none, 2 = 2D, 3 = 3D */
    uint8_t  sats;
    float    hdop;
    double   lat, lon;
    float    alt_m, speed_mps, course_deg;
    int64_t  updated_us;   /* esp_timer time of last valid fix */
} gnss_state_t;

void gnss_init(void);
void gnss_get(gnss_state_t *out);

/** Position reported by a client. Logged, used for time sync, and keeps the
 *  GNSS asleep while it stays fresh (< 10 s). */
void gnss_client_position(int64_t unix_ms, double lat, double lon, float alt_m,
                          float speed_mps, float course_deg, float acc_m, const char *via);

/** True while a client position younger than 10 s exists. */
bool gnss_client_fresh(void);
