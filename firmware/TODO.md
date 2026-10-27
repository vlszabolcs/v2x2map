# Firmware – tervezett fejlesztések

## Állapot (2026-10-27, commit 86de4a3)

| # | Fejlesztés | Állapot |
|---|---|---|
| 1 | Folyamatos vétel USB-n | **kész** – a 6. pont része: BLE nélkül nincs időosztás; USB-írás csak hosttal, atomikusan |
| 2 | MQTT Wi-Fi-n | **kész** – `IP_EVENT_STA_GOT_IP`-re indul; ciklikus módban a pufferelés még hiányzik |
| 3 | PCAP SD-re | **kész** – T-Dongle overlay: `SNIFFER_PCAP_DESTINATION_SD`, kártya bootkor csatolva |
| 4 | SD-napló + letöltés | **kész** – firmware, app („Naplók a vevőn”), `bridge/download_logs.py` |
| 5 | GNSS | **kész (kód)** – `sdkconfig.defaults.tdongle-c5-gnss`; modullal még nincs tesztelve |
| 6 | Működési módok | **kész** – eltérés a tervtől: BLE-párosítás (bond) helyett az app egyedi azonosítót küld (`HELLO:`), az újracsatlakozási módban csak azt engedi be. Így iOS-en és régi Androidon is működik, és nem kell a véletlen BLE-címekkel (RPA) foglalkozni |

**Hardveres teszt (2026-10-27):** a módosítások LilyGO T-Dongle-C5-ön
kipróbálva, működnek. A GNSS-t modullal még nem teszteltük. A protokollt
emellett gépi teszt is ellenőrzi (C rekordok → Python olvasó, virtuális
eszköz → letöltő).

**Nyitott:** MQTT-pufferelés ciklikus Wi-Fi módban; RSSI továbbítása az
appnak (a frame-fejlécben nincs hely, külön rekord kellene); app-oldali
lefedettségi térkép, GPX/KML export, RSU-helybecslés (TODO 5 app része);
visszajátszás időcsúszkával (most egyben tölt be).

---

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

## 5. GNSS a naplók kiegészítésére (önálló mobil sniffer)

**Cél.** Telefon nélkül, autóban/gyalog hordozva a napló minden
munkamenete kapjon pontos UTC-időt és a vevő saját pozícióját/mozgását.
Ez a TODO 4 két hiányát is megoldja: nincs RTC (→ GNSS-idő), és nincs
saját pozíció (→ távolságok, lefedettségi térkép, RSU-k helye akkor is,
ha a frame-ben nincs pozíció vagy aláírt és nem dekódolható).

**Hardver.**
- Modul: UART-os GNSS, 3,3 V, NMEA (vagy u-blox UBX) kimenettel.
  Jelöltek: u-blox MAX-M10S / NEO-M10 (alacsony fogyasztás, 10 Hz),
  SAM-M10Q (beépített antennával), olcsó ATGM336H. Aktív patch-antenna
  IPEX/SMA csatlakozóval, a 5,9 GHz-es antennától elválasztva.
- Bekötés: a T-Dongle-C5-ön kevés a szabad láb. Kézenfekvő a UART0
  padok (GPIO11 TX / GPIO12 RX) használata a GNSS-re → a konzol
  (REPL) GNSS-módban kikapcsol; a beállítás a USB `CFG:` csatornán és
  BLE-n marad. Ellenőrizni a kapcsolási rajzon, van-e további kivezetett
  GPIO (PPS-hez és/vagy a konzol megtartásához).
- PPS (opcionális, 1 további GPIO): µs-os időpontosság a frame
  időbélyegekhez; NMEA-idő nélküle is ~10–50 ms pontos, a naplóhoz elég.
- Táp: ~25–40 mA a USB 5 V → 3,3 V ágról; hidegindítás ~30 s,
  meleg ~1–2 s. Backup-akku/szuperkapacitás a modulon gyorsítja az
  újraindulást.
- Mechanika: a USB-botra szerelve nem fér el, rövid kábelen vagy közös
  3D-nyomtatott házban (antenna felül, szabad égbolttal).

**Firmware.**
- Kconfig: `ENABLE_GNSS`, UART-szám, RX/TX/PPS láb, baud (9600 / 38400),
  frissítési ráta (1 / 5 / 10 Hz). T-Dongle overlayben alapból ki.
- NMEA-parser (RMC, GGA, GSA, VTG elég) külön taskban; induláskor
  opcionális UBX-konfig (ráta, csak a szükséges mondatok, autós
  dinamikai modell).
- Első érvényes UTC-időnél `settimeofday()` → a frame-ek valós
  időbélyeget kapnak; a napló automatikusan `ITST` rekordot kap
  (TODO 4), utána periodikusan (pl. percenként) is a drift követésére.
- Új naplórekord: `ITSG` – UTC, lat, lon, magasság, sebesség, irány,
  fix típus, műholdszám, HDOP; a beállított rátával (1–10 Hz), csak
  változáskor/fix mellett. Fix nélkül is megy a naplózás.
- Élő streambe is (USB/BLE) mehet az `ITSG`, így az app a dongle
  pozícióját is mutathatja, ha a telefon GPS-e gyenge (opcionális).
- Kijelző: „GNSS 3D · 9 sat · 52 km/h” sor; fix nélkül „GNSS keres”.
- LED: fix nélkül lassú sárga villanás a háttérben (opcionális).

**App / bridge.**
- Visszajátszásnál a vevő útvonala a napló `ITSG` rekordjaiból,
  távolság minden állomáshoz, sebesség/irány a vevőhöz képest.
- Lefedettségi térkép: hol, milyen RSSI-vel/rátával volt vétel
  (a TODO 1 utáni RSSI-továbbítással együtt értelmes).
- RSU-k becsült helye több vételi pontból, ha a frame nem hordoz
  pozíciót.
- Export: GPX (saját útvonal), PCAP + GNSS oldalfájl, KML a
  lefedettséghez.

**Sorrend.** TODO 1 → TODO 4 → TODO 5. A GNSS a TODO 4 naplóformátumára
épül, az idő-szinkron ott kiváltható vele.

## 6. Működési módok (standalone / BLE / USB host)

**Szabály (2026-10-22).**
- Bekapcsolás (bármilyen USB-táp) után **1 percig** BLE-hirdetés,
  ezalatt a telefon csatlakozhat. Ha 1 percen belül nem csatlakozik
  senki, a BLE teljesen kikapcsol.
- **Dupla gombnyomás** (BOOT, GPIO28) BLE-kapcsolóként működik:
  - ha a BLE ki van kapcsolva → hirdetés **1 percre** (standalone és
    USB host módban is);
  - ha a BLE be van kapcsolva (hirdet, kliens csatlakozva, vagy
    újracsatlakozásra vár) → **BLE azonnal ki**: kliens bontása,
    hirdetés leállítása, újracsatlakozási ablak törlése, folyamatos vétel.
- Kliens elhagyja a hatókört → **újracsatlakozás csak az ismert
  telefonnak, csökkenő gyakoriságú hirdetéssel** (lásd lent).

**Újracsatlakozás hatókörvesztés után.**
- Első csatlakozáskor egyszeri párosítás (bond, „Just Works”), a dongle
  NVS-ben eltárolja a telefon identitását (IRK). A telefonok
  véletlenszerű (RPA) címet használnak, ezért kell a bond; enélkül a
  dongle nem ismeri fel újra a telefont.
- Lecsatlakozás okának vizsgálata: ha supervision timeout (hatókör
  vesztése) → újracsatlakozási mód; ha a kliens szándékosan bontott
  (az appban „Bontás”) → BLE ki, nincs újracsatlakozás.
- Újracsatlakozási mód: **csak a párosított telefon** csatlakozhat
  (whitelist / filter accept list), más eszköz nem látja/nem kapcsolódhat.
  A hirdetés rövid (~30 ms) burstökben megy, csökkenő gyakorisággal,
  hogy a vételt alig zavarja:
  - 0–2 perc: 2 s-onként (vétel ~98%)
  - 2–15 perc: 10 s-onként
  - 15–60 perc: 30 s-onként
  - 60 perc után BLE ki (újra dupla nyomással)
- Telefon oldalon az app `autoConnect=true`-val (háttér-csatlakozás)
  vár a dongle-ra: amint hatókörbe ér és elkap egy hirdetést,
  magától visszakapcsolódik, a felhasználónak nem kell semmit tennie.
- Kiesés alatt a dongle SD-re naplóz (TODO 4); visszacsatlakozáskor
  az app a hiányzó időszakot letöltheti a naplóból (`LOG_GET` a
  bontás időpontjától) → nincs adatvesztés a térképen.
- Az időzítések NVS-ben állíthatók; az app „Elfelejtés” gombja törli a
  párosítást.

**Állapotok.**

| Állapot | Vétel | BLE | GNSS | Helyadat a naplóban |
|---|---|---|---|---|
| Standalone, hirdetési ablak (1 perc) | ~90% (200 ms hirdetés / 2 s) | hirdet | be | GNSS |
| Standalone | 100% | ki | be | GNSS |
| BLE kliens csatlakozva | ~55% (egy rádió) | kapcsolat | alvó* | kliens (`POS`) |
| USB host aktív | 100% | ki (dupla nyomásra 1 perc) | alvó* | kliens (`POS:`) |

\* Ha 10 s-ig nem jön friss pozíció a klienstől (pl. PC-s bridge,
kikapcsolt telefonos helymeghatározás), a GNSS automatikusan felébred.

**Firmware.**
- USB host felismerése: `usb_serial_jtag_is_connected()` (SOF-forgalom),
  ~1 s-os váltás. Powerbanknál/töltőnél nincs host → standalone.
- BLE: hirdetés-időzítő (60 s); lejáratkor `ble_gap_adv_stop()`, a
  coex időosztás leáll, `ESP_COEX_PREFER_WIFI`, folyamatos vétel.
  A TODO 1 időosztó logikáját ez váltja ki.
- Hirdetési ablak alatt nem 50/50 időosztás, hanem 2 s-onként ~200 ms
  BLE-ablak (vétel ~90%).
- Gomb: dupla nyomás = BLE be (1 perc) / BLE ki (ha be volt); felismerés:
  két felengedés ≤ 400 ms-on belül;
  az egyszeri nyomás (kijelző ki/be) ezért ~300 ms késéssel reagál;
  hosszú nyomás (≥ 1,5 s, számlálók nullázása) marad.
- Kliens-pozíció: új BLE karakterisztika és USB `POS:` parancs
  (UTC-idő, lat, lon, magasság, sebesség, irány, pontosság), ~1 Hz.
  A napló `ITSG` rekordot kap, forrásjelöléssel (gnss / ble / usb).
  Az idő is innen szinkronizálódik (`ITST`), ha nincs GNSS.
- GNSS altatás/ébresztés UBX-paranccsal a UART-on (nem kell plusz láb).
- Kijelző: mód és hátralévő hirdetési idő, pl. „BLE 0:42”,
  „STANDALONE · GNSS 3D”, „USB · POS kliens”.
- LED: kék villogás hirdetés alatt, ritka kék villanás
  újracsatlakozási módban, türkiz BLE-kapcsolatnál, rövid piros
  villanás BLE kikapcsolásakor.

**App.**
- BLE-n és USB-n másodpercenként pozíció + idő küldése a dongle-nak.
- Ha a dongle nem található: tipp a dupla gombnyomásra.

**Megkötések.** BLE-kapcsolat alatt a vétel ~55% marad (egyetlen
rádió); legjobb vétel USB-n vagy standalone. Powerbank-lekapcsolást
alacsony áramnál a gyakorlatban ellenőrizni kell.

**Sorrend.** Ez a TODO 1 (USB folyamatos vétel) bővített változata;
a TODO 4 (SD-napló) és TODO 5 (GNSS) erre épül.

## 3. PCAP SD-kártyára

A T-Dongle build a stock `sdkconfig`-ból örökli a
`CONFIG_SNIFFER_PCAP_DESTINATION_JTAG=y`-t, így a `sniffer -P` / `pcap`
a JTAG app-trace-re ír, nem az SD-kártyára. Javítás:
`sdkconfig.defaults.tdongle-c5`-be `CONFIG_SNIFFER_PCAP_DESTINATION_SD=y`,
és automatikus `mount sd` bootkor, ha van kártya.
