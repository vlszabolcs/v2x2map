#include "sdkconfig.h"

#ifdef CONFIG_ENABLE_TEMPERATURE

#include <math.h>
#include <stdbool.h>

#include "esp_err.h"
#include "esp_log.h"
#include "esp_timer.h"
#if CONFIG_TEMPERATURE_INTERNAL
#include "driver/temperature_sensor.h"
#else
#include "driver/i2c_master.h"
#include "lm75.h"
#define I2C_PORT 0
#endif

static const char TAG[] = "TEMPERATURE";

#if CONFIG_TEMPERATURE_INTERNAL
static temperature_sensor_handle_t tsens;
#else
static i2c_dev_t dev;
#endif
static bool initialized;
static esp_timer_handle_t timer_handle;

static float temperature = NAN;

static void temperature_read(void *)
{
    if (initialized)
    {
        float value;
#if CONFIG_TEMPERATURE_INTERNAL
        esp_err_t res = temperature_sensor_get_celsius(tsens, &value);
#else
        esp_err_t res = lm75_read_temperature(&dev, &value);
#endif

        if (res == ESP_OK)
        {
            ESP_LOGD(TAG, "temperature: %f", value);
            temperature = value;
            return;
        }
    }

    temperature = NAN;
}

void temperature_init(void)
{
#if CONFIG_TEMPERATURE_INTERNAL
    temperature_sensor_config_t tcfg = TEMPERATURE_SENSOR_CONFIG_DEFAULT(-10, 80);
    esp_err_t res = temperature_sensor_install(&tcfg, &tsens);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "temperature_sensor_install failed: %s", esp_err_to_name(res));
        return;
    }
    res = temperature_sensor_enable(tsens);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "temperature_sensor_enable failed: %s", esp_err_to_name(res));
        return;
    }
#else
    esp_err_t res = i2cdev_init();
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "i2cdev_init failed: %s", esp_err_to_name(res));
        return;
    }

    res = lm75_init_desc(&dev, LM75_I2C_ADDRESS_DEFAULT, I2C_PORT, CONFIG_I2CDEV_DEFAULT_SDA_PIN, CONFIG_I2CDEV_DEFAULT_SCL_PIN);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "lm75_init_desc failed: %s", esp_err_to_name(res));
        return;
    }

    lm75_config_t config = {0};
    res = lm75_init(&dev, config);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "lm75_init failed: %s", esp_err_to_name(res));
        return;
    }
#endif

    esp_timer_create_args_t create_args = {
        .callback = temperature_read,
        .arg = NULL,
        .name = "temperature_read"
    };

    res = esp_timer_create(&create_args, &timer_handle);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "esp_timer_create failed: %s", esp_err_to_name(res));
        return;
    }

    res = esp_timer_start_periodic(timer_handle, 10000000);
    if (res != ESP_OK)
    {
        ESP_LOGW(TAG, "esp_timer_create failed: %s", esp_err_to_name(res));
        return;
    }

    initialized = true;
    temperature_read(NULL);
}

float temperature_get(void)
{
    return temperature;
}

#endif // CONFIG_ENABLE_TEMPERATURE
