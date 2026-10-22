#include "sdkconfig.h"

#include "button.h"

#if CONFIG_BUTTON_PIN >= 0

#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "bt_stream.h"
#include "display.h"

static const char TAG[] = "BUTTON";

#define POLL_MS        20
#define DOUBLE_GAP_US  400000LL
#define LONG_US        1500000LL

static void button_task(void *arg)
{
    (void)arg;
    bool prev = false;
    int64_t down_at = 0, last_release = 0;
    int clicks = 0;

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(POLL_MS));
        bool pressed = gpio_get_level(CONFIG_BUTTON_PIN) == 0;
        int64_t now = esp_timer_get_time();

        if (pressed && !prev) down_at = now;
        if (!pressed && prev) {
            if (now - down_at >= LONG_US) {
                clicks = 0;
                ESP_LOGI(TAG, "long press");
                display_reset_stats();
            } else {
                clicks++;
                last_release = now;
                if (clicks == 2) {
                    clicks = 0;
                    ESP_LOGI(TAG, "double press");
                    bt_stream_ble_toggle();
                }
            }
        }
        /* a single click is confirmed once the double-press gap has passed */
        if (!pressed && clicks == 1 && now - last_release > DOUBLE_GAP_US) {
            clicks = 0;
            display_toggle_power();
        }
        prev = pressed;
    }
}

void button_init(void)
{
    gpio_config_t in = {
        .pin_bit_mask = 1ULL << CONFIG_BUTTON_PIN,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
    };
    gpio_config(&in);
    xTaskCreate(button_task, "button", 3072, NULL, 3, NULL);
}

#else

void button_init(void) {}

#endif
