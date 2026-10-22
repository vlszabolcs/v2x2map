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

## 4. Önálló SD-kártyás naplózás, utólagos letöltés (USB + BLE)

**Cél.** A dongle telefon/PC nélkül, bármilyen USB-tápról (powerbank,
autós töltő) naplózzon SD-kártyára, és a naplók utólag letölthetők
legyenek az appba vagy PC-re, USB-n és BLE-n.

**Döntések (2026-10-22).**
- Formátum: nyers ITS5 keretek (ugyanaz, mint az USB/BLE stream), így az
  app visszajátszhatja; Wiresharkhoz a bridge/app konvertál PCAP-ra.
- Indítás: mindig naplóz, ha van kártya.
- Letöltés: USB-n (app és PC/bridge) és BLE-n.

**Firmware.**
- Bootkor automatikus `mount sd`; ha nincs kártya vagy hibás, a naplózás
  kimarad (kijelzőn „SD –”), a vétel megy tovább.
- Munkamenetenként egy fájl: `/sdcard/its/NNNNNN.its` (folyamatos
  sorszám NVS-ben, mert nincs RTC). Új fájl bootkor és X MB-onként
  (pl. 16 MB) vagy óránként.
- Fájlfejléc: `ITS5LOG1` magic, formátumverzió, node ID / MAC, firmware
  verzió, boot-sorszám.
- Rekordok: a meglévő `ITS5` keret változatlanul (14 B fejléc + frame),
  plusz egy új `ITST` időszinkron-rekord: boot óta eltelt µs ↔ valós
  Unix-idő. Ilyen akkor kerül a fájlba, amikor app/PC csatlakozik és
  elküldi a pontos időt (`CFG:time=` USB-n, BLE config karakterisztikán).
  Letöltéskor az app ebből számolja vissza a valós időbélyegeket; ha egy
  munkamenetben nincs `ITST`, relatív idővel jelenik meg.
- Írás külön taskban, RAM-pufferrel (pl. 32 KB), `fflush`/`fsync`
  néhány másodpercenként → áramszünetnél legfeljebb pár másodperc vész el.
  Az utolsó, csonka rekordot az olvasó eldobja.
- Tele kártya: a legrégebbi fájlok törlése, amíg van X% szabad hely.
  Letöltött-és-nyugtázott fájlok törlése az appból kérhető.
- Az SD és az LCD közös SPI buszon van: buszhozzáférés mutexszel,
  a kijelzőfrissítés ne blokkolja az írást.
- Kijelző: „SD 1,2 GB szabad · REC 00042.its” sor.

**Letöltő protokoll (USB `CFG`-csatorna mellé és BLE-n ugyanaz).**
- `LOG_LIST` → fájlnév, méret, első/utolsó időbélyeg, van-e `ITST`.
- `LOG_GET:<név>:<offset>:<hossz>` → darabolt bináris válasz, offsettel
  folytatható (megszakadt letöltés után is).
- `LOG_DEL:<név>`, `LOG_ROTATE` (aktuális fájl lezárása letöltés előtt).
- USB: ~1 MB/s körül → egy óra (~5–20 MB) néhány másodperc.
- BLE: külön karakterisztika a letöltésre, nagy MTU-val; ~10–30 kB/s,
  egy óra naplója több perc. Letöltés alatt a sniffer szüneteljen
  (a rádió a BLE-é), utána álljon vissza.
- Letöltés közben az élő USB/BLE stream szüneteljen vagy keretezve
  keveredjen, hogy az app szét tudja választani.

**App / bridge.**
- „Naplók a vevőn” képernyő: lista, letöltés folyamatjelzővel, törlés.
- Letöltött munkamenet visszajátszása a térképre (időcsúszkával),
  exportálás PCAP-ba (link type 105) és opcionálisan MQTT-re.
- Python bridge: `--download-logs` kapcsoló, PCAP-konverzióval.

**Összefüggések.** A TODO 1 (folyamatos USB-vétel) után a naplózás
mindhárom üzemmódban fusson. A TODO 3 (PCAP SD-re) ezzel részben
kiváltható; marad a konzolos `sniffer -P` útvonalra.

## 3. PCAP SD-kártyára

A T-Dongle build a stock `sdkconfig`-ból örökli a
`CONFIG_SNIFFER_PCAP_DESTINATION_JTAG=y`-t, így a `sniffer -P` / `pcap`
a JTAG app-trace-re ír, nem az SD-kártyára. Javítás:
`sdkconfig.defaults.tdongle-c5`-be `CONFIG_SNIFFER_PCAP_DESTINATION_SD=y`,
és automatikus `mount sd` bootkor, ha van kártya.
