#include "sdkconfig.h"

#include "esp_log.h"
#include "driver/gpio.h"
#include "driver/spi_common.h"

#include "spi.h"

#ifdef CONFIG_ETHERNET_SPI_SUPPORT
#define PIN_NUM_MOSI CONFIG_ETHERNET_SPI_MOSI_GPIO
#define PIN_NUM_MISO CONFIG_ETHERNET_SPI_MISO_GPIO
#define PIN_NUM_CLK CONFIG_ETHERNET_SPI_SCLK_GPIO
#elif defined(CONFIG_SNIFFER_SD_SPI_MODE)
#define PIN_NUM_MISO CONFIG_SNIFFER_PIN_MISO
#define PIN_NUM_MOSI CONFIG_SNIFFER_PIN_MOSI
#define PIN_NUM_CLK  CONFIG_SNIFFER_PIN_CLK
#elif defined(CONFIG_ENABLE_LCD)
#define PIN_NUM_MISO -1
#define PIN_NUM_MOSI CONFIG_LCD_PIN_MOSI
#define PIN_NUM_CLK  CONFIG_LCD_PIN_CLK
#endif

static const char TAG[] = "SPI";
static bool s_bus_ok;

bool spi_bus_ready(void)
{
    return s_bus_ok;
}

// Initialize SPI bus common to SD card, Ethernet and LCD
void initialize_spi(void)
{
#if CONFIG_BOARD_LILYGO_T_DONGLE_C5
    /* The ST7735 shares SCK/MOSI with the SD card: keep its CS deasserted
     * (and the backlight off) even if the display driver is disabled, so SD
     * traffic is never interpreted as LCD commands. */
    gpio_config_t io = {
        .pin_bit_mask = (1ULL << 10) | (1ULL << 0),
        .mode = GPIO_MODE_OUTPUT,
    };
    gpio_config(&io);
    gpio_set_level(10, 1);
    gpio_set_level(0, 1);
#endif

#if CONFIG_SNIFFER_SD_SPI_MODE || CONFIG_ETHERNET_SPI_SUPPORT || CONFIG_ENABLE_LCD
    spi_bus_config_t bus_cfg = {
        .mosi_io_num = PIN_NUM_MOSI,
        .miso_io_num = PIN_NUM_MISO,
        .sclk_io_num = PIN_NUM_CLK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 4096,
    };

    // TODO: maybe make SPI host configurable
    int ret = spi_bus_initialize(1, &bus_cfg, SPI_DMA_CH_AUTO);
    if (ret != ESP_OK)
        ESP_LOGE(TAG, "Failed to initialize bus.");
    else
        s_bus_ok = true;
#endif
}
