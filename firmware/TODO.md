# Firmware – tervezett fejlesztések

## 1. Folyamatos vétel USB-n (nincs BLE időosztás)

**Probléma.** A `coex_window_task` (`main/bt_stream.c`) bootkor feltétel
nélkül indul, és USB-s használatnál is felváltva adja a rádiót a 802.11p
vételnek és a BLE-nek: kliens nélkül 1000 ms vétel / 1000 ms BLE, kliens
mellett 500/400 ms. Minden szünetnél `sniffer_pause()` → `esp_wifi_stop()`,
folytatáskor újraindítás és újrahangolás, a coex preferencia pedig
`ESP_COEX_PREFER_BT`. USB-n így a vételi idő ~50%-a (a gyakorlatban
kevesebb) elvész: a CAM-ok kb. fele kimarad, a SPATEM fázisváltás akár
1 s-mal késik, a frame/perc kb. a valós fele.

**Megoldás.**
- `usb_serial_jtag_is_connected()` alapján (aktív USB-host, SOF-forgalom):
  - USB aktív, nincs BLE kliens → folyamatos vétel, `ESP_COEX_PREFER_WIFI`;
    BLE csak egy rövid (~200 ms) hirdetési ablakot kap 10 s-onként.
    Várható vételi arány ~98%.
  - USB aktív, van BLE kliens → marad a mostani időosztás.
  - Nincs USB-host (powerbank, autós töltő) → marad az 50/50, mert a BLE
    az egyetlen kimenet.
- Új NVS kulcs: `usbexclusive` (u8). 1 esetén USB-módban a BLE teljesen
  kikapcsol (100% vétel). Kerüljön a `CFG:` USB-csatornára is.
- Kábel be-/kihúzáskor ~1 s-on belül váltson (a coex task 200 ms-os
  `chunked_delay` lépéseiben ellenőrizve).

**Kapcsolódó hiba.** Ha a dongle PC-n van, de senki nem olvassa a portot,
`usb_stream_publish_packet()` frame-enként 2×50 ms-ig blokkol a
`usb_serial_jtag_write_bytes()`-ban, és visszafogja a sniffer taskot.
Javítás: 0 timeout, ha nem fér be a TX bufferbe, a frame eldobása
(és egy számláló az eldobottakra).

**Mérés.** Ugyanazon a helyen, élő forgalommal a kijelző frame/perc
értékét összevetni előtte és utána.

## 2. MQTT Wi-Fi-n is

A dongle saját MQTT kliense (`main/mqtt.c`) csak
`APP_ETHERNET_MGMT_INTERFACE_GOT_IP` eseményre indul; a `wifi_manager`
`IP_EVENT_STA_GOT_IP`-je nincs rákötve. A T-Dongle-on nincs Ethernet, így
az MQTT ott nem indul el. Javítás: Wi-Fi IP-re is `mqtt_start()`,
IP-vesztésre leállítás; ciklikus Wi-Fi módban a csomagok pufferelése a
Wi-Fi ablakig.

## 3. PCAP SD-kártyára

A T-Dongle build a stock `sdkconfig`-ból örökli a
`CONFIG_SNIFFER_PCAP_DESTINATION_JTAG=y`-t, így a `sniffer -P` / `pcap`
a JTAG app-trace-re ír, nem az SD-kártyára. Javítás:
`sdkconfig.defaults.tdongle-c5`-be `CONFIG_SNIFFER_PCAP_DESTINATION_SD=y`,
és automatikus `mount sd` bootkor, ha van kártya.
