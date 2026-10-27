#pragma once

#include <stdbool.h>
#include <stddef.h>

#include "cmd_sniffer.h"

void mqtt_init(void);
void mqtt_handle_packet(sniffer_packet_info_t *packet);

/** Frames waiting in the offline buffer (CONFIG_MQTT_BUFFER_KB). */
size_t mqtt_backlog(void);

/** True while the broker connection is up. */
bool mqtt_is_connected(void);
