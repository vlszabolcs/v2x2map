# V2X2MAP on the LilyGO T-Dongle-C5

The firmware builds for two boards from the same source tree. The Waveshare
ESP32-C5-WIFI6-KIT stays the default; the LilyGO T-Dongle-C5 is selected with an
sdkconfig overlay. The USB data protocol (`ITS5` frames over USB-Serial-JTAG,
VID 0x303A) and the BLE GATT service are unchanged, so the Android app, the
Python bridge and the setup wizard work without modification.

## What differs on the dongle

| Function | Waveshare kit | T-Dongle-C5 |
|---|---|---|
| Flash | 4 MB (`partitions_4M.csv`) | 16 MB (`partitions_16M.csv`) |
| Status LED | WS2812 on GPIO27 | APA102: DI = GPIO5, CI = GPIO4 (bit-banged) |
| SD card (SPI) | MOSI 7, MISO 2, CLK 6, CS 10 | MOSI/CMD 2, MISO/D0 7, CLK 6, CS 23 |
| Display | none | ST7735 0.96" 80×160: CS 10, DC 3, RST 1, BL 0 (active low), shares SCK/MOSI with SD |
| Button | none | BOOT (GPIO28): short press = screen on/off, hold 1.5 s = reset counters |
| Ethernet | optional W5500 | disabled (its CS/INT pins collide with the LED and LCD) |
| Temperature | optional LM75 on I2C | ESP32-C5 internal sensor |
| USB | USB-Serial-JTAG | USB-Serial-JTAG on the USB-A plug |

The console REPL stays on UART0 (GPIO11/12 pads), exactly as on the kit, so the
USB-Serial-JTAG port carries only the binary frame stream and the `CFG:` setup
channel.

## Status screen

```
V2X2MAP          RX 5900MHz   <- PAUSED in orange while BLE cycle mode holds the radio
4821                          <- total frames received
                     frames
312/min              Stn 7    <- frames in the last 60 s / distinct transmitters in the last 60 s
-81dBm              2s ago    <- RSSI and age of the last frame
G5 4790 sec 96%        47C    <- GeoNetworking frames, share with secured header, chip temperature
BLE linked         1:23:45    <- BLE client state, uptime
```

The screen is redrawn twice a second from a low-priority task. Packet
statistics are updated under a spinlock from the sniffer task and cost a few
compares per frame.

## Build

ESP-IDF v6.1 (the version the upstream `sdkconfig` was generated with).

```bash
cd firmware
idf.py -B build-tdongle \
       -D SDKCONFIG=build-tdongle/sdkconfig \
       -D SDKCONFIG_DEFAULTS="sdkconfig;sdkconfig.defaults.tdongle-c5" \
       set-target esp32c5
idf.py -B build-tdongle -D SDKCONFIG=build-tdongle/sdkconfig build
```

The stock Waveshare build is unchanged: `idf.py build`.

## Flash

Hold BOOT while plugging the dongle in, then:

```bash
idf.py -B build-tdongle -D SDKCONFIG=build-tdongle/sdkconfig -p PORT flash
# or the single merged image:
python -m esptool --chip esp32c5 -p PORT -b 460800 write-flash 0x0 v2x2map-tdongle-c5-merged.bin
```

On Windows, LilyGO's `flash_download_tool` (chip ESP32C5, USB download) also
works with the merged image at offset 0x0. After flashing, unplug and replug
without BOOT pressed.

## Antenna

The 5.9 GHz band is at the edge of what the onboard antenna is tuned for. For
real reception range, move the RF resistor to the IPEX position and fit an
external 5–6 GHz antenna (see the T-Dongle-C5 readme, FAQ 1).

## Configuration knobs

All options live in `menuconfig → Example Configuration`:

- `Target board` — picks the board defaults
- `Status LED type`, `APA102_*` pins and hardware brightness (default 4/31)
- `Enable ST7735 status display`, LCD pins, offsets, button pin
- `Temperature source` — LM75 or internal sensor
