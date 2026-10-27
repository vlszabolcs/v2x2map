#!/usr/bin/env python3
"""
Download the standalone SD-card logs from a V2X2MAP receiver over USB.

    python download_logs.py                 # auto-detect port, fetch new logs
    python download_logs.py --list          # only list what is on the card
    python download_logs.py --delete        # delete each log after a verified download
    python download_logs.py --port COM7 --out logs/

Each log is saved as <out>/<node>/NNNNNNNN.its (raw record stream, resumable)
and converted to NNNNNNNN.pcap (radiotap + 802.11 with RSSI, Wireshark). Frames recorded
before the receiver knew the time are fixed up with the log's time-sync record.

Wire format (little-endian), shared with the live stream:
    "ITS5" sec u32 usec u32 len u16 payload       captured frame
    "ITSJ" len u16 json                           reply / meta record
    "ITSL" file u32 offset u32 len u16 data crc32 log chunk
    "ITSR" rssi i8 usec_lo u16                    RSSI of the next frame
"""

import argparse
import json
import os
import struct
import sys
import time
import zlib

import serial
import serial.tools.list_ports

ESP_VID = 0x303A
CHUNK = 4096


# ── record parsing ─────────────────────────────────────────────────────────

class RecordReader:
    """Resync parser yielding ('frame', sec, usec, payload, rssi) /
    ('json', obj) / ('chunk', file, offset, data, crc_ok). rssi is the dBm
    value from a preceding ITSR record, or None."""

    def __init__(self):
        self.buf = bytearray()
        self.pending = None

    def feed(self, data: bytes):
        self.buf += data
        out = []
        while True:
            idx = -1
            for m in (b"ITS5", b"ITSJ", b"ITSL", b"ITSR"):
                i = self.buf.find(m)
                if i >= 0 and (idx < 0 or i < idx):
                    idx = i
            if idx < 0:
                del self.buf[:-3]
                return out
            del self.buf[:idx]
            kind = self.buf[3:4]
            if kind == b"5":
                if len(self.buf) < 14:
                    return out
                sec, usec, ln = struct.unpack_from("<IIH", self.buf, 4)
                if ln > 4096 or usec >= 1_000_000:
                    del self.buf[:4]; continue
                if len(self.buf) < 14 + ln:
                    return out
                p, self.pending = self.pending, None
                rssi = p[0] if p and p[1] == (usec & 0xFFFF) else None
                out.append(("frame", sec, usec, bytes(self.buf[14:14 + ln]), rssi))
                del self.buf[:14 + ln]
            elif kind == b"R":
                if len(self.buf) < 7:
                    return out
                self.pending = struct.unpack_from("<bH", self.buf, 4)
                del self.buf[:7]
            elif kind == b"J":
                if len(self.buf) < 6:
                    return out
                (ln,) = struct.unpack_from("<H", self.buf, 4)
                if ln == 0 or ln > 4096:
                    del self.buf[:4]; continue
                if len(self.buf) < 6 + ln:
                    return out
                try:
                    out.append(("json", json.loads(self.buf[6:6 + ln].decode("utf-8"))))
                except ValueError:
                    pass
                del self.buf[:6 + ln]
            else:  # "L"
                if len(self.buf) < 14:
                    return out
                fid, off, ln = struct.unpack_from("<IIH", self.buf, 4)
                if ln > 4096:
                    del self.buf[:4]; continue
                if len(self.buf) < 14 + ln + 4:
                    return out
                data = bytes(self.buf[14:14 + ln])
                (crc,) = struct.unpack_from("<I", self.buf, 14 + ln)
                out.append(("chunk", fid, off, data, zlib.crc32(data) == crc))
                del self.buf[:14 + ln + 4]


def its_to_pcap(src: str, dst: str) -> int:
    """Convert a downloaded .its log to pcap; returns the frame count."""
    rr = RecordReader()
    with open(src, "rb") as f:
        recs = rr.feed(f.read())
    offset_us = None
    for r in recs:
        if r[0] == "json" and r[1].get("t") == "time":
            offset_us = int(r[1]["unix_ms"]) * 1000 - int(r[1]["up_us"])
            break
    n = 0
    with open(dst, "wb") as out:
        # radiotap + 802.11, so Wireshark shows the RSSI of each frame
        out.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 127))
        for r in recs:
            if r[0] != "frame":
                continue
            _, sec, usec, payload, rssi = r
            t = sec * 1_000_000 + usec
            if sec < 1_000_000_000 and offset_us is not None:
                t += offset_us
            rt = (struct.pack("<BBHI", 0, 0, 8, 0) if rssi is None
                  else struct.pack("<BBHIb", 0, 0, 9, 0x20, rssi))
            ln = len(rt) + len(payload)
            out.write(struct.pack("<IIII", t // 1_000_000, t % 1_000_000, ln, ln))
            out.write(rt)
            out.write(payload)
            n += 1
    return n


# ── device session ─────────────────────────────────────────────────────────

class Device:
    def __init__(self, ser: serial.Serial):
        self.ser = ser
        self.rr = RecordReader()
        self.pending = []

    def send(self, cmd: str):
        self.ser.write((cmd + "\n").encode("ascii"))

    def wait(self, pred, timeout=3.0):
        end = time.time() + timeout
        while time.time() < end:
            for i, r in enumerate(self.pending):
                if pred(r):
                    return self.pending.pop(i)
            data = self.ser.read(self.ser.in_waiting or 1)
            if data:
                self.pending += [r for r in self.rr.feed(data) if r[0] != "frame"]
                del self.pending[:-64]
        return None

    def json(self, cmd: str, t: str, timeout=3.0):
        self.send(cmd)
        r = self.wait(lambda r: r[0] == "json" and r[1].get("t") in (t, "err"), timeout)
        return r[1] if r else None

    def download(self, fid: int, size: int, path: str, progress=None) -> bool:
        off = os.path.getsize(path) if os.path.exists(path) else 0
        with open(path, "ab") as f:
            while off < size:
                for attempt in range(6):
                    self.send(f"LOG_GET:{fid}:{off}:{CHUNK}")
                    r = self.wait(lambda r: r[0] == "chunk" and r[1] == fid and r[2] == off, 3.0)
                    if r and r[4]:
                        break
                else:
                    return False
                data = r[3]
                if not data:
                    break
                f.write(data)
                off += len(data)
                if progress:
                    progress(off, size)
        return True


def find_port():
    for p in serial.tools.list_ports.comports():
        if p.vid == ESP_VID:
            return p.device
    return None


def run(port=None, out="logs", list_only=False, delete=False, baud=115200) -> int:
    port = port or find_port()
    if not port:
        print("No receiver found (VID 0x303A). Use --port.")
        return 1
    ser = serial.Serial(port, baud, timeout=0.2)
    try:
        ser.dtr = False
        ser.rts = False
    except Exception:
        pass
    dev = Device(ser)
    dev.send(f"TIME:{int(time.time() * 1000)}")

    st = dev.json("STATUS", "status") or {}
    node = (st.get("node") or "receiver").replace(":", "_").replace("/", "_")
    lst = dev.json("LOG_LIST", "loglist")
    if not lst or not lst.get("sd"):
        print("No SD card / no answer from the receiver." if lst else "No answer – old firmware?")
        return 1
    files = lst.get("files", [])
    print(f"{port}: node {node}, {len(files)} log(s), {lst.get('free_mb')} MB free")
    for f in files:
        print(f"  #{f['id']:>6}  {f['size'] / 1048576:7.2f} MB{'  (recording)' if f.get('cur') else ''}")
    if list_only:
        return 0

    # Close the file being written so it can be fetched completely
    if any(f.get("cur") for f in files):
        dev.json("LOG_ROTATE", "logrotate")
        files = (dev.json("LOG_LIST", "loglist") or {}).get("files", files)

    outdir = os.path.join(out, node)
    os.makedirs(outdir, exist_ok=True)
    for f in files:
        fid, size = int(f["id"]), int(f["size"])
        if f.get("cur") or size == 0:
            continue
        its = os.path.join(outdir, f"{fid:08d}.its")
        if os.path.exists(its) and os.path.getsize(its) >= size:
            print(f"#{fid}: already downloaded")
        else:
            t0 = time.time()

            def prog(done, total):
                sys.stdout.write(f"\r#{fid}: {done * 100 // max(total, 1):3d}%  "
                                 f"{done / max(time.time() - t0, 0.01) / 1024:6.0f} KB/s")
                sys.stdout.flush()

            ok = dev.download(fid, size, its, prog)
            print()
            if not ok:
                print(f"#{fid}: download failed (resume by running again)")
                continue
        n = its_to_pcap(its, its[:-4] + ".pcap")
        print(f"#{fid}: {n} frames → {its[:-4]}.pcap")
        if delete:
            r = dev.json(f"LOG_DEL:{fid}", "logdel")
            print(f"#{fid}: deleted on receiver" if r and r.get("deleted") else f"#{fid}: delete failed")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port")
    ap.add_argument("--out", default="logs")
    ap.add_argument("--list", action="store_true", help="only list the logs on the card")
    ap.add_argument("--delete", action="store_true", help="delete each log after a verified download")
    ap.add_argument("--baud", type=int, default=115200)
    a = ap.parse_args()
    return run(a.port, a.out, a.list, a.delete, a.baud)


if __name__ == "__main__":
    sys.exit(main())
