#include "bt_stream.h"

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_coexist.h"
#include "esp_wifi.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "driver/usb_serial_jtag.h"

#include "esp_timer.h"

#include "cmd_sniffer.h"
#include "ctrl.h"
#include "display.h"
#include "led.h"
#include "records.h"
#include "usb_stream.h"

/* Private NimBLE API: forces the controller's public address. Used here so
 * old Android stacks (Galaxy S6 / AOSP) accept the advertiser — they ignore
 * adv from random-static addresses they have not paired with.
 */
extern int ble_hs_id_set_pub(const uint8_t *pub_addr);

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs_mbuf.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char TAG[] = "BT_STREAM";

#define DEVICE_NAME            "ITS-G5-RX"
#define HEADER_LEN             14
#define MAX_PAYLOAD            2048
#define FRAME_QUEUE_DEPTH      16

/* Vendor UUIDs (NimBLE stores them little-endian, so the bytes below are
 * reversed vs. the canonical string form):
 *   Service:  b6e57e90-12d8-4a47-9b21-3f0000000001
 *   Notify :  b6e57e90-12d8-4a47-9b21-3f0000000002
 *   Config :  b6e57e90-12d8-4a47-9b21-3f0000000003 (8 B = 4×u16 LE)
 */
static const ble_uuid128_t SVC_UUID = BLE_UUID128_INIT(
    0x01, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

static const ble_uuid128_t CHR_UUID = BLE_UUID128_INIT(
    0x02, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

static const ble_uuid128_t CFG_UUID = BLE_UUID128_INIT(
    0x03, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

/* Read-only: the factory BT MAC (6 bytes, big-endian as printed on the label).
 * iOS/CoreBluetooth hides BLE addresses from apps, so the receiver's identity
 * (e.g. for per-device MQTT node IDs) has to come from a characteristic. */
static const ble_uuid128_t MAC_UUID = BLE_UUID128_INIT(
    0x04, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

/* Write-only command channel (same text protocol as USB, see ctrl.h).
 * Replies come back as ITSJ / ITSL records on the notify characteristic. */
static const ble_uuid128_t CTRL_UUID = BLE_UUID128_INIT(
    0x05, 0x00, 0x00, 0x00, 0x00, 0x3f, 0x21, 0x9b,
    0x47, 0x4a, 0xd8, 0x12, 0x90, 0x7e, 0xe5, 0xb6);

typedef struct {
    uint16_t total_len;        /* HEADER_LEN + payload_len */
    uint8_t  data[];           /* preformatted ITS5 frame */
} qframe_t;

static uint8_t        s_addr_type;
static uint16_t       s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t       s_chr_val_handle;
static uint16_t       s_cfg_val_handle;
static volatile bool  s_notify_enabled;
static bool           s_ready;
static QueueHandle_t  s_queue;

/* Coex cycle parameters, runtime-tunable via the config characteristic.
 * Values are milliseconds; volatile because read by radio_task and
 * written from the GATT access callback.
 *   0..1: discovery cycle (no client connected)
 *   2..3: connected cycle  (client present)
 */
/* connSniff (index 2) MUST stay below the BLE central's supervision timeout.
 * During a connected sniff window the BLE radio is fully dark; if that lasts
 * longer than the supervision timeout (~700 ms on iOS/macOS — the firmware's
 * requested 5 s update is NOT honored by the host) the link drops right after
 * connect. A connSniff sweep on an ESP32-C5 showed: stable up to 680 ms, hard
 * cliff at 700 ms. 500 ms is chosen for a ~28 % safety margin (for weaker
 * field RF / other centrals) while still sniffing ~56 % of the time.
 * Discovery (index 0..1) tuned for fast (re)connect: a connect-time sweep found
 * discSniff=1000/discBle=1000 → ~1.4 s connect (vs ~12 s at 10000/2000); discBle
 * below ~500 ms hurts (advert windows get missed). The iOS app exposes 3 presets
 * — Stabil(connSniff 300) / Ausgewogen(500) / Mehr Daten(600) — and this
 * compiled default equals the Ausgewogen preset. */
static volatile uint16_t s_cycle_ms[4] = { 1000, 1000, 500, 400 };
#define CYC_DISC_SNIFF  s_cycle_ms[0]
#define CYC_DISC_BLE    s_cycle_ms[1]
#define CYC_CONN_SNIFF  s_cycle_ms[2]
#define CYC_CONN_BLE    s_cycle_ms[3]
#define CFG_NS          "btstream"
#define CFG_KEY         "cycle"

static void cfg_load(void);
static void cfg_save(void);

static int gap_event_cb(struct ble_gap_event *ev, void *arg);

/* Inject ASCII diagnostics into the USB stream. The frame reader (resync on
 * the "ITS5" magic) drops every byte that is not part of a real frame, so we
 * can piggy-back \r\n-terminated lines without disturbing the parser. */
static void dbg_printf(const char *fmt, ...)
{
    char line[160];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof(line) - 2, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof(line) - 2) n = sizeof(line) - 2;
    line[n++] = '\r';
    line[n++] = '\n';
    usb_stream_send_record((const uint8_t *)line, n);
}

static int chr_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return BLE_ATT_ERR_READ_NOT_PERMITTED;
    }
    return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
}

static int cfg_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t buf[8];
        for (int i = 0; i < 4; i++) {
            uint16_t v = s_cycle_ms[i];
            buf[i*2]     = (uint8_t)(v        & 0xff);
            buf[i*2 + 1] = (uint8_t)((v >> 8) & 0xff);
        }
        int rc = os_mbuf_append(ctxt->om, buf, sizeof(buf));
        return (rc == 0) ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint16_t total = OS_MBUF_PKTLEN(ctxt->om);
        if (total != 8) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        uint8_t buf[8];
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), NULL) != 0) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        for (int i = 0; i < 4; i++) {
            uint16_t v = (uint16_t)buf[i*2] | ((uint16_t)buf[i*2 + 1] << 8);
            /* Sanity: clamp to 100..60000 ms; rejecting silently is more
             * forgiving than returning an error and locking the client out. */
            if (v < 100)    v = 100;
            if (v > 60000)  v = 60000;
            s_cycle_ms[i] = v;
        }
        cfg_save();
        dbg_printf("[bt] cfg write: %u/%u %u/%u",
                   CYC_DISC_SNIFF, CYC_DISC_BLE,
                   CYC_CONN_SNIFF, CYC_CONN_BLE);
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static int mac_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle; (void)attr_handle; (void)arg;
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t mac[6] = {0};
        if (esp_read_mac(mac, ESP_MAC_BT) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        int rc = os_mbuf_append(ctxt->om, mac, sizeof(mac));
        return (rc == 0) ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static uint16_t s_mac_val_handle;
static uint16_t s_ctrl_val_handle;

static int ctrl_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle; (void)attr_handle; (void)arg;
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_UNLIKELY;
    char line[200];
    uint16_t n = 0;
    if (ble_hs_mbuf_to_flat(ctxt->om, line, sizeof(line) - 1, &n) != 0) n = 0;
    while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) n--;
    line[n] = '\0';
    if (n) ctrl_submit(line, CTRL_CH_BLE);
    return 0;
}

static const struct ble_gatt_chr_def chr_defs[] = {
    {
        .uuid       = &CHR_UUID.u,
        .access_cb  = chr_access_cb,
        .flags      = BLE_GATT_CHR_F_NOTIFY,
        .val_handle = &s_chr_val_handle,
    },
    {
        .uuid       = &CFG_UUID.u,
        .access_cb  = cfg_access_cb,
        .flags      = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
        .val_handle = &s_cfg_val_handle,
    },
    {
        .uuid       = &MAC_UUID.u,
        .access_cb  = mac_access_cb,
        .flags      = BLE_GATT_CHR_F_READ,
        .val_handle = &s_mac_val_handle,
    },
    {
        .uuid       = &CTRL_UUID.u,
        .access_cb  = ctrl_access_cb,
        .flags      = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
        .val_handle = &s_ctrl_val_handle,
    },
    { 0 },
};

static void cfg_load(void)
{
    nvs_handle_t h;
    if (nvs_open(CFG_NS, NVS_READONLY, &h) != ESP_OK) return;
    size_t sz = sizeof(s_cycle_ms);
    uint16_t tmp[4];
    if (nvs_get_blob(h, CFG_KEY, tmp, &sz) == ESP_OK && sz == sizeof(tmp)) {
        for (int i = 0; i < 4; i++) {
            uint16_t v = tmp[i];
            if (v >= 100 && v <= 60000) s_cycle_ms[i] = v;
        }
    }
    nvs_close(h);
}

static void cfg_save(void)
{
    nvs_handle_t h;
    if (nvs_open(CFG_NS, NVS_READWRITE, &h) != ESP_OK) return;
    uint16_t tmp[4];
    for (int i = 0; i < 4; i++) tmp[i] = s_cycle_ms[i];
    nvs_set_blob(h, CFG_KEY, tmp, sizeof(tmp));
    nvs_commit(h);
    nvs_close(h);
}

static const struct ble_gatt_svc_def svc_defs[] = {
    {
        .type            = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid            = &SVC_UUID.u,
        .characteristics = chr_defs,
    },
    { 0 },
};

/* ── operating-mode state ─────────────────────────────────────────────── */

static volatile ble_mode_t s_mode = BLE_MODE_OPEN;
static volatile uint32_t   s_mode_gen;          /* bumps on every change */
static volatile int64_t    s_mode_since_us;
static volatile int64_t    s_open_until_us;
static volatile int64_t    s_bulk_until_us;
static volatile bool       s_user_off;          /* we dropped the link on purpose */
static volatile bool       s_scan_hold;         /* Wi-Fi scan owns the radio */
static volatile bool       s_conn_hello_ok;     /* current client sent a known HELLO */
static volatile bool       s_conn_needs_hello;  /* connected during RECONNECT */
static volatile int64_t    s_hello_deadline_us;
static char                s_known_client[40];

#define OPEN_WINDOW_US   ((int64_t)CONFIG_BLE_WINDOW_S * 1000000LL)
#define RECONNECT_MAX_US (60LL * 60LL * 1000000LL)

static void set_mode(ble_mode_t m)
{
    if (s_mode == m) return;
    s_mode = m;
    s_mode_since_us = esp_timer_get_time();
    s_mode_gen++;
    led_status_ble_mode((int)m);
    display_set_ble_connected(m == BLE_MODE_CONNECTED);
}

static void open_window(void)
{
    s_open_until_us = esp_timer_get_time() + OPEN_WINDOW_US;
    s_user_off = false;
    if (s_mode != BLE_MODE_CONNECTED) set_mode(BLE_MODE_OPEN);
    s_mode_gen++;
}

static void known_client_load(void)
{
    nvs_handle_t h;
    if (nvs_open(CFG_NS, NVS_READONLY, &h) != ESP_OK) return;
    size_t sz = sizeof(s_known_client);
    if (nvs_get_str(h, "client", s_known_client, &sz) != ESP_OK) s_known_client[0] = '\0';
    nvs_close(h);
}

static void known_client_save(const char *id)
{
    strncpy(s_known_client, id, sizeof(s_known_client) - 1);
    s_known_client[sizeof(s_known_client) - 1] = '\0';
    nvs_handle_t h;
    if (nvs_open(CFG_NS, NVS_READWRITE, &h) != ESP_OK) return;
    if (id[0]) nvs_set_str(h, "client", s_known_client);
    else       nvs_erase_key(h, "client");
    nvs_commit(h);
    nvs_close(h);
}

static void start_advertising(void)
{
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)DEVICE_NAME;
    fields.name_len = strlen(DEVICE_NAME);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_set_fields rc=%d", rc);
        dbg_printf("[bt] adv_set_fields rc=%d", rc);
        return;
    }

    /* Put the 128-bit service UUID in the scan response so the adv packet
     * stays under the 31-byte legacy limit. */
    struct ble_hs_adv_fields scan_rsp = {0};
    scan_rsp.uuids128 = (ble_uuid128_t *)&SVC_UUID;
    scan_rsp.num_uuids128 = 1;
    scan_rsp.uuids128_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&scan_rsp);
    if (rc != 0) {
        ESP_LOGW(TAG, "adv_rsp_set_fields rc=%d", rc);
    }

    struct ble_gap_adv_params p = {0};
    p.conn_mode = BLE_GAP_CONN_MODE_UND;
    p.disc_mode = BLE_GAP_DISC_MODE_GEN;
    /* 30 ms / 50 ms in 0.625 ms units — fast enough that Samsung-S6 BLE
     * scan windows reliably catch us. Defaults are slower. */
    p.itvl_min = 0x30;
    p.itvl_max = 0x50;
    rc = ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event_cb, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_start rc=%d", rc);
        dbg_printf("[bt] adv_start rc=%d", rc);
    } else {
        ESP_LOGI(TAG, "advertising as %s", DEVICE_NAME);
        dbg_printf("[bt] advertising as %s", DEVICE_NAME);
    }
}

static int gap_event_cb(struct ble_gap_event *ev, void *arg)
{
    (void)arg;
    switch (ev->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (ev->connect.status == 0) {
            s_conn_handle = ev->connect.conn_handle;
            s_notify_enabled = false;
            s_conn_hello_ok = false;
            /* During RECONNECT only the remembered client may stay */
            s_conn_needs_hello = (s_mode == BLE_MODE_RECONNECT) && s_known_client[0];
            s_hello_deadline_us = esp_timer_get_time() + 5000000LL;
            set_mode(BLE_MODE_CONNECTED);
            led_status_ble_connected(true);
            ESP_LOGI(TAG, "connect: handle=%d", s_conn_handle);
            /* Stretch the supervision timeout so the coex sniff windows (where
             * the BLE link is starved) don't tear the link down.
             *
             * iOS caps a peripheral's requested supervision timeout at 6 s
             * (Apple Bluetooth Accessory Design Guidelines: 2 s..6 s). Requesting
             * more (we used to ask for 30 s) makes iOS REJECT the whole update
             * and fall back to its ~0.7 s default, so every connected sniff
             * window (CYC_CONN_SNIFF, e.g. 800 ms) trips connectionTimeout and
             * the iPhone reconnect-loops. 5 s is within Apple's range and still
             * comfortably spans the sniff window. Keep CYC_CONN_SNIFF < ~5 s. */
            struct ble_gap_upd_params up = {
                .itvl_min            = 0x18,   /* 30 ms */
                .itvl_max            = 0x28,   /* 50 ms */
                .latency             = 0,
                .supervision_timeout = 500,    /* 5.0 s (units of 10 ms) */
                .min_ce_len          = 0,
                .max_ce_len          = 0,
            };
            ble_gap_update_params(s_conn_handle, &up);
        } else {
            ESP_LOGW(TAG, "connect failed status=%d", ev->connect.status);
            dbg_printf("[bt] connect failed status=%d", ev->connect.status);
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT: {
        int reason = ev->disconnect.reason;
        ESP_LOGI(TAG, "disconnect: reason=0x%x", reason);
        dbg_printf("[bt] disconnect reason=0x%x", reason);
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_notify_enabled = false;
        led_status_ble_connected(false);
        bool by_client = (reason == BLE_HS_HCI_ERR(BLE_ERR_REM_USER_CONN_TERM));
        if (s_user_off || by_client) {
            /* Deliberate disconnect (double press / BLE_OFF / app "disconnect") */
            set_mode(BLE_MODE_OFF);
        } else if (s_conn_hello_ok && s_known_client[0]) {
            /* Lost by range: wait for the known client to come back */
            set_mode(BLE_MODE_RECONNECT);
        } else {
            /* Client without HELLO (older app): a normal connect window */
            open_window();
        }
        s_conn_hello_ok = false;
        s_conn_needs_hello = false;
        break;
    }
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (ev->subscribe.attr_handle == s_chr_val_handle) {
            s_notify_enabled = ev->subscribe.cur_notify;
            ESP_LOGI(TAG, "notify subscription=%d", s_notify_enabled);
        }
        break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu: handle=%d value=%d",
                 ev->mtu.conn_handle, ev->mtu.value);
        break;
    default:
        break;
    }
    return 0;
}

static void on_sync(void)
{
    dbg_printf("[bt] on_sync");

    /* Override the controller-supplied address with the factory BT MAC so the
     * advertiser comes up as a Public address — random-static is silently
     * dropped by some older Android stacks (Galaxy S6 etc.). */
    uint8_t mac_be[6] = {0};
    if (esp_read_mac(mac_be, ESP_MAC_BT) == ESP_OK) {
        uint8_t mac_le[6] = {
            mac_be[5], mac_be[4], mac_be[3],
            mac_be[2], mac_be[1], mac_be[0],
        };
        int prc = ble_hs_id_set_pub(mac_le);
        dbg_printf("[bt] forced public mac %02X:%02X:%02X:%02X:%02X:%02X rc=%d",
                   mac_be[0], mac_be[1], mac_be[2], mac_be[3], mac_be[4], mac_be[5], prc);
    } else {
        dbg_printf("[bt] esp_read_mac BT failed");
    }
    s_addr_type = BLE_OWN_ADDR_PUBLIC;

    uint8_t addr[6] = {0};
    ble_hs_id_copy_addr(s_addr_type, addr, NULL);
    ESP_LOGI(TAG, "BLE addr=%02X:%02X:%02X:%02X:%02X:%02X",
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    dbg_printf("[bt] addr=%02X:%02X:%02X:%02X:%02X:%02X",
               addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    s_mode_gen++;   /* radio task (re)starts advertising as the mode requires */
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "stack reset: %d", reason);
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* Duty-cycle the Wi-Fi promiscuous mode while no BLE client is connected, so
 * passive BLE scanners (Galaxy S6, generic USB BT dongle) actually see the
 * adv between sniffer windows. The C5 has a single RF transceiver shared
 * between 5 GHz Wi-Fi and 2.4 GHz BLE — without a pause, promisc keeps the
 * radio glued to 5 GHz RX and BLE TX never hits air. iPhone catches the rare
 * legacy adv only because its scan duty cycle is exceptionally aggressive.
 *
 * Once connected, BLE has scheduled connection events that the SW coex layer
 * services correctly, so we leave promisc on continuously.
 */
/* The single ESP32-C5 RF transceiver can't service Wi-Fi promisc and BLE adv
 * simultaneously: while the sniffer is active (with phy_11p_set + 5.9 GHz
 * channel locked), BLE TX is starved and no scanner (Galaxy S6, USB BT
 * dongle) can find the device. iPhones are the rare exception because their
 * scan duty cycle is aggressive enough to catch the few packets that slip
 * through.
 *
 * Resolution: time-share the radio. Sniff for SNIFFER_ON_MS, then pause the
 * sniffer (releases promisc + 802.11p PHY + esp_wifi_stop) for BLE_ON_MS so
 * scanners see the adv and a connected client gets its notification queue
 * drained. While paused, captured frames sit in the BLE queue until the
 * window opens; while sniffing, the BLE link is starved (clients usually
 * survive the supervision-timeout window with a fresh adv arriving in time).
 */
/* The radio task sleeps in short chunks so it notices a mode change (connect,
 * disconnect, double press) within COEX_CHUNK_MS instead of holding the radio
 * for the full sniff window.
 *
 * Why this matters: in wardriving mode (sniff 8000 ms / BLE 400 ms), a
 * disconnect mid-sniff would otherwise leave the phone scanning blind for
 * up to 8 s before the next BLE window opens. Likewise, a fresh connect
 * mid-sniff would starve the link past the host supervision timeout (~5 s
 * on Android default) and trigger an immediate reconnect-loop. */
#define COEX_CHUNK_MS 200
/* Connected-mode interleaving (see radio_task): max radio-hold per
 * slice and the BLE breathing gap between slices. */
#define CONN_SNIFF_SLICE_MS 350
#define CONN_SNIFF_GAP_MS   60

static bool mode_changed(uint32_t gen) { return gen != s_mode_gen; }

/* Sleep in COEX_CHUNK_MS steps; return early when the mode changes. */
static void mode_delay(uint32_t total_ms, uint32_t gen)
{
    uint32_t left = total_ms;
    while (left > 0 && !mode_changed(gen)) {
        uint32_t step = (left > COEX_CHUNK_MS) ? COEX_CHUNK_MS : left;
        vTaskDelay(pdMS_TO_TICKS(step));
        left -= step;
    }
}

static void adv_ensure(bool on)
{
    if (s_scan_hold) return;
    bool active = ble_gap_adv_active();
    if (on && !active) start_advertising();
    else if (!on && active) ble_gap_adv_stop();
}

/* Interval between short advert windows while waiting for the known client */
static uint32_t reconnect_period_ms(int64_t elapsed_us)
{
    if (elapsed_us <  2LL * 60 * 1000000) return 2000;
    if (elapsed_us < 15LL * 60 * 1000000) return 10000;
    return 30000;
}

static void radio_task(void *arg)
{
    (void)arg;
    esp_coex_prefer_t pref = ESP_COEX_PREFER_BT;
    for (;;) {
        int64_t now = esp_timer_get_time();
        uint32_t gen = s_mode_gen;

        /* Expiries */
        if (CONFIG_BLE_WINDOW_S > 0 && s_mode == BLE_MODE_OPEN && now > s_open_until_us) {
            set_mode(BLE_MODE_OFF); continue;
        }
        if (s_mode == BLE_MODE_RECONNECT && now - s_mode_since_us > RECONNECT_MAX_US) {
            set_mode(BLE_MODE_OFF); continue;
        }
        if (s_mode == BLE_MODE_CONNECTED && s_conn_needs_hello && !s_conn_hello_ok &&
            now > s_hello_deadline_us && s_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
            dbg_printf("[bt] unknown client during reconnect window – dropping");
            s_conn_needs_hello = false;
            ble_gap_terminate(s_conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        }

        esp_coex_prefer_t want = (s_mode == BLE_MODE_OFF) ? ESP_COEX_PREFER_WIFI : ESP_COEX_PREFER_BT;
        if (want != pref) { esp_coex_preference_set(want); pref = want; }

        switch (s_mode) {
        case BLE_MODE_OFF:
            adv_ensure(false);
            sniffer_resume();
            mode_delay(500, gen);
            break;

        case BLE_MODE_OPEN:
            /* Connectable window: sniff most of the time, give BLE regular slots */
            adv_ensure(true);
            sniffer_resume();
            mode_delay(CYC_DISC_SNIFF, gen);
            if (mode_changed(gen)) break;
            sniffer_pause();
            mode_delay(CYC_DISC_BLE, gen);
            break;

        case BLE_MODE_RECONNECT: {
            adv_ensure(true);
            uint32_t period = reconnect_period_ms(now - s_mode_since_us);
            sniffer_resume();
            mode_delay(period - 300, gen);
            if (mode_changed(gen)) break;
            sniffer_pause();
            mode_delay(300, gen);
            break;
        }

        case BLE_MODE_CONNECTED: {
            adv_ensure(false);
            /* Freshly connected and not yet subscribed, or a log download in
             * progress: keep the radio on BLE. */
            if (!s_notify_enabled || now < s_bulk_until_us) {
                sniffer_pause();
                mode_delay(CYC_CONN_BLE, gen);
                break;
            }
            /* Interleaved sniffing: never hold the radio away from BLE for more
             * than ~350 ms (iOS supervision timeout ~700 ms in practice). */
            uint32_t sniff_ms = CYC_CONN_SNIFF;
            uint32_t nslices = (sniff_ms + CONN_SNIFF_SLICE_MS - 1) / CONN_SNIFF_SLICE_MS;
            if (nslices < 1) nslices = 1;
            uint32_t slice = sniff_ms / nslices;
            sniffer_resume();
            for (uint32_t i = 0; i < nslices && !mode_changed(gen); i++) {
                mode_delay(slice, gen);
                if (i + 1 == nslices || mode_changed(gen)) break;
                sniffer_pause();
                vTaskDelay(pdMS_TO_TICKS(CONN_SNIFF_GAP_MS));
                sniffer_resume();
            }
            if (mode_changed(gen)) break;
            sniffer_pause();
            mode_delay(CYC_CONN_BLE, gen);
            break;
        }
        }
    }
}

static void writer_task(void *arg)
{
    (void)arg;
    qframe_t *qf = NULL;
    for (;;) {
        if (xQueueReceive(s_queue, &qf, portMAX_DELAY) != pdTRUE) continue;

        if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || !s_notify_enabled) {
            free(qf);
            continue;
        }

        uint16_t mtu = ble_att_mtu(s_conn_handle);
        uint16_t chunk = (mtu > 3) ? (uint16_t)(mtu - 3) : 20;
        uint16_t sent = 0;
        while (sent < qf->total_len) {
            uint16_t n = qf->total_len - sent;
            if (n > chunk) n = chunk;
            struct os_mbuf *om = ble_hs_mbuf_from_flat(qf->data + sent, n);
            if (om == NULL) {
                ESP_LOGW(TAG, "mbuf alloc failed, dropping frame");
                break;
            }
            int rc = ble_gatts_notify_custom(s_conn_handle, s_chr_val_handle, om);
            if (rc != 0) {
                ESP_LOGW(TAG, "notify rc=%d, dropping", rc);
                /* om is consumed by NimBLE on success; on failure we leak —
                 * but there is no public API to free it explicitly here. */
                break;
            }
            led_pulse_ble_tx();
            sent += n;
        }
        free(qf);
    }
}

void bt_stream_init(void)
{
    if (s_ready) return;

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        err = nvs_flash_init();
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "nvs_flash_init: %s", esp_err_to_name(err));
    }

    cfg_load();
    dbg_printf("[bt] cfg load: %u/%u %u/%u",
               CYC_DISC_SNIFF, CYC_DISC_BLE, CYC_CONN_SNIFF, CYC_CONN_BLE);

    /* Wi-Fi promiscuous mode otherwise hogs the RF; with BALANCE the BLE adv
     * is so rare that BLE 4.x scanners (Galaxy S6, generic USB BT dongle)
     * miss every window. iPhone catches it because its scan duty cycle is
     * far higher than what Android exposes. PREFER_BT lets BLE pre-empt
     * Wi-Fi long enough to emit a complete adv packet. */
    esp_err_t cerr = esp_coex_preference_set(ESP_COEX_PREFER_BT);
    dbg_printf("[bt] coex_preference_set(PREFER_BT) rc=%d", cerr);

    dbg_printf("[bt] init: nimble_port_init…");
    err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init: %s", esp_err_to_name(err));
        dbg_printf("[bt] nimble_port_init FAIL: %s", esp_err_to_name(err));
        return;
    }
    dbg_printf("[bt] nimble_port_init ok");

    ble_hs_cfg.sync_cb  = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    int rc = ble_svc_gap_device_name_set(DEVICE_NAME);
    if (rc != 0) ESP_LOGW(TAG, "name_set rc=%d", rc);

    ble_svc_gap_init();
    ble_svc_gatt_init();

    rc = ble_gatts_count_cfg(svc_defs);
    if (rc != 0) {
        ESP_LOGE(TAG, "count_cfg rc=%d", rc);
        return;
    }
    rc = ble_gatts_add_svcs(svc_defs);
    if (rc != 0) {
        ESP_LOGE(TAG, "add_svcs rc=%d", rc);
        return;
    }

    s_queue = xQueueCreate(FRAME_QUEUE_DEPTH, sizeof(qframe_t *));
    if (s_queue == NULL) {
        ESP_LOGE(TAG, "queue create failed");
        return;
    }
    BaseType_t xrc = xTaskCreate(writer_task, "bt_writer", 4096, NULL, 5, NULL);
    if (xrc != pdPASS) {
        ESP_LOGE(TAG, "writer task create failed");
        return;
    }
    known_client_load();
    s_mode_since_us = esp_timer_get_time();
    open_window();                       /* connect window after power-on */
    xTaskCreate(radio_task, "bt_radio", 3072, NULL, 4, NULL);

    nimble_port_freertos_init(host_task);
    s_ready = true;
    ESP_LOGI(TAG, "initialized");
    dbg_printf("[bt] initialized, awaiting on_sync");
}

void bt_stream_pause(void)
{
    if (!s_ready) return;
    s_scan_hold = true;
    ble_gap_adv_stop();
    ESP_LOGI(TAG, "BLE advertising stopped for WiFi scan");
}

void bt_stream_resume(void)
{
    if (!s_ready) return;
    s_scan_hold = false;
    s_mode_gen++;
    ESP_LOGI(TAG, "BLE advertising restarted");
}

void bt_stream_publish_packet(const uint8_t *payload, uint16_t len, uint32_t sec, uint32_t usec, int8_t rssi)
{
    if (!s_ready) return;
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || !s_notify_enabled) return;
    if (len > MAX_PAYLOAD) return;

    /* The RSSI record travels in the same queue item, right before its frame */
    uint8_t rr[REC_RSSI_LEN];
    size_t rn = rec_rssi(rr, rssi, usec);
    size_t total = rn + REC_FRAME_HDR + len;
    qframe_t *qf = (qframe_t *)malloc(sizeof(qframe_t) + total);
    if (qf == NULL) return;
    qf->total_len = (uint16_t)total;

    uint8_t *p = qf->data;
    if (rn) memcpy(p, rr, rn);
    rec_frame_hdr(p + rn, sec, usec, len);
    if (len > 0) memcpy(p + rn + REC_FRAME_HDR, payload, len);

    if (xQueueSend(s_queue, &qf, 0) != pdTRUE) {
        free(qf);  /* queue full — drop newest, keep stream live */
    }
}

void bt_stream_send_record(const uint8_t *buf, size_t len)
{
    if (!s_ready || s_conn_handle == BLE_HS_CONN_HANDLE_NONE || !s_notify_enabled) return;
    if (len > HEADER_LEN + MAX_PAYLOAD) return;
    qframe_t *qf = (qframe_t *)malloc(sizeof(qframe_t) + len);
    if (!qf) return;
    qf->total_len = (uint16_t)len;
    memcpy(qf->data, buf, len);
    /* Control replies must not be dropped: wait briefly for queue space */
    if (xQueueSend(s_queue, &qf, pdMS_TO_TICKS(500)) != pdTRUE) free(qf);
}

ble_mode_t bt_stream_mode(void) { return s_mode; }

const char *bt_stream_mode_name(void)
{
    switch (s_mode) {
    case BLE_MODE_OFF:       return "off";
    case BLE_MODE_OPEN:      return "open";
    case BLE_MODE_CONNECTED: return "connected";
    case BLE_MODE_RECONNECT: return "reconnect";
    }
    return "?";
}

int bt_stream_window_left_s(void)
{
    if (s_mode != BLE_MODE_OPEN || CONFIG_BLE_WINDOW_S == 0) return 0;
    int64_t left = s_open_until_us - esp_timer_get_time();
    return left > 0 ? (int)(left / 1000000) : 0;
}

void bt_stream_ble_on(void)
{
    if (!s_ready) return;
    if (s_mode == BLE_MODE_CONNECTED) return;
    open_window();
    dbg_printf("[bt] BLE on for %d s", CONFIG_BLE_WINDOW_S);
}

void bt_stream_ble_off(void)
{
    if (!s_ready) return;
    s_user_off = true;
    if (s_conn_handle != BLE_HS_CONN_HANDLE_NONE)
        ble_gap_terminate(s_conn_handle, BLE_ERR_REM_USER_CONN_TERM);
    set_mode(BLE_MODE_OFF);
    led_flash_ble_off();
    dbg_printf("[bt] BLE off");
}

void bt_stream_ble_toggle(void)
{
    if (s_mode == BLE_MODE_OFF) bt_stream_ble_on();
    else                        bt_stream_ble_off();
}

void bt_stream_forget(void)
{
    known_client_save("");
}

bool bt_stream_hello(const char *client_id, bool via_ble)
{
    if (!via_ble || !client_id || !client_id[0]) return false;
    if (s_conn_needs_hello && strcmp(client_id, s_known_client) != 0) {
        dbg_printf("[bt] HELLO from unknown client during reconnect – dropping");
        if (s_conn_handle != BLE_HS_CONN_HANDLE_NONE)
            ble_gap_terminate(s_conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        return false;
    }
    if (strcmp(client_id, s_known_client) != 0) known_client_save(client_id);
    s_conn_hello_ok = true;
    s_conn_needs_hello = false;
    return true;
}

void bt_stream_bulk_activity(void)
{
    s_bulk_until_us = esp_timer_get_time() + 1500000LL;
}
