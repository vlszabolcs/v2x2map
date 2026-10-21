#include "sdkconfig.h"

#if CONFIG_STATUS_LED_APA102

#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"

#include "apa102.h"

#define PIN_DI  CONFIG_APA102_DATA_PIN
#define PIN_CI  CONFIG_APA102_CLK_PIN

static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static bool s_init;

static inline void shift_byte(uint8_t v)
{
    for (int i = 7; i >= 0; --i) {
        gpio_set_level(PIN_DI, (v >> i) & 1);
        gpio_set_level(PIN_CI, 1);
        gpio_set_level(PIN_CI, 0);
    }
}

void apa102_init(void)
{
    if (s_init) return;
    gpio_config_t io = {
        .pin_bit_mask = (1ULL << PIN_DI) | (1ULL << PIN_CI),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&io);
    gpio_set_level(PIN_CI, 0);
    gpio_set_level(PIN_DI, 0);
    s_init = true;
    apa102_set(0, 0, 0);
}

void apa102_set(uint8_t r, uint8_t g, uint8_t b)
{
    if (!s_init) return;
    portENTER_CRITICAL_SAFE(&s_lock);
    /* start frame */
    for (int i = 0; i < 4; ++i) shift_byte(0x00);
    /* LED frame: 111 + 5-bit global brightness, then B, G, R */
    shift_byte(0xE0 | (CONFIG_APA102_GLOBAL_BRIGHTNESS & 0x1F));
    shift_byte(b);
    shift_byte(g);
    shift_byte(r);
    /* end frame */
    for (int i = 0; i < 4; ++i) shift_byte(0xFF);
    portEXIT_CRITICAL_SAFE(&s_lock);
}

#else

#include "apa102.h"
void apa102_init(void) {}
void apa102_set(uint8_t r, uint8_t g, uint8_t b) { (void)r; (void)g; (void)b; }

#endif
