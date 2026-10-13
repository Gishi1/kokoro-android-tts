#!/usr/bin/env python3
"""Render a waveform PNG so audio can be *looked at* rather than only measured.

Numbers told me a chunk had 360 ms of trailing silence; a picture shows whether the
first word is clipped, whether a seam is really gapless, and whether an inserted
pause landed where it should.

Deliberately dependency-free: **standard library only**, so it runs under any
python3. An earlier version imported numpy, which exists only inside
`.tooling/kokoro-venv`, and so failed with a bare ModuleNotFoundError for anyone
running it the obvious way.

Usage:
    python3 tools/waveform.py out.wav --out wave.png
    python3 tools/waveform.py out.wav --out onset.png --start 0 --end 0.25
    python3 tools/waveform.py out.wav --out seams.png --mark 3.1 --mark 7.4
"""

from __future__ import annotations

import argparse
import array
import struct
import sys
import zlib
from pathlib import Path

SILENCE = 200          # int16 amplitude below which a sample counts as silence


def read_audio(path: Path) -> tuple[array.array, int]:
    """Return (mono int16 samples, sample rate). Handles WAV and raw PCM."""
    try:
        data = path.read_bytes()
    except OSError as e:
        raise SystemExit(f"cannot read {path}: {e}")

    rate, channels, bits = 24000, 1, 16
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        pos, payload = 12, b""
        while pos + 8 <= len(data):
            cid = data[pos:pos + 4]
            size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
            if cid == b"fmt ":
                _, channels, rate, _, _, bits = struct.unpack(
                    "<HHIIHH", data[pos + 8:pos + 24])
            elif cid == b"data":
                payload = data[pos + 8:pos + 8 + size]
                break
            pos += 8 + size + (size & 1)
        if bits != 16:
            raise SystemExit(f"only 16-bit PCM is supported, got {bits}")
    else:
        payload = data[:len(data) // 2 * 2]      # raw little-endian mono PCM

    samples = array.array("h")
    samples.frombytes(payload)
    if sys.byteorder == "big":
        samples.byteswap()
    if channels > 1:
        mono = array.array("h", [0]) * (len(samples) // channels)
        for i in range(len(mono)):
            mono[i] = samples[i * channels]
        samples = mono
    return samples, rate


class Canvas:
    """Tiny RGB canvas with just the primitives a waveform needs."""

    def __init__(self, width: int, height: int, background=(18, 18, 22)):
        self.w, self.h = width, height
        # One bytearray per row is far cheaper than a list of pixel tuples.
        self.rows = [bytearray(background * width) for _ in range(height)]

    def vline(self, x: int, y0: int, y1: int, colour) -> None:
        if not (0 <= x < self.w):
            return
        y0 = max(0, min(self.h - 1, y0))
        y1 = max(0, min(self.h - 1, y1))
        if y0 > y1:
            y0, y1 = y1, y0
        px = bytes(colour)
        for y in range(y0, y1 + 1):
            self.rows[y][3 * x:3 * x + 3] = px

    def hline(self, y: int, x0: int, x1: int, colour) -> None:
        if not (0 <= y < self.h):
            return
        x0 = max(0, x0)
        x1 = min(self.w, x1)
        if x1 > x0:
            self.rows[y][3 * x0:3 * x1] = bytes(colour) * (x1 - x0)

    def write_png(self, path: Path) -> None:
        raw = b"".join(b"\x00" + bytes(r) for r in self.rows)

        def chunk(tag: bytes, payload: bytes) -> bytes:
            return (struct.pack(">I", len(payload)) + tag + payload
                    + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

        png = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(raw, 6))
               + chunk(b"IEND", b""))
        path.write_bytes(png)


def render(samples: array.array, rate: int, width: int, height: int,
           marks) -> Canvas:
    canvas = Canvas(width, height)
    mid = height // 2
    canvas.hline(mid, 0, width, (70, 70, 80))

    n = len(samples)
    if n == 0:
        return canvas

    for x in range(width):
        a = n * x // width
        b = max(a + 1, n * (x + 1) // width)
        seg = samples[a:b]
        lo, hi = min(seg), max(seg)
        peak = max(abs(lo), abs(hi))
        colour = (60, 60, 70) if peak < SILENCE else (90, 220, 150)
        y_hi = mid - (hi * (mid - 2)) // 32768
        y_lo = mid - (lo * (mid - 2)) // 32768
        canvas.vline(x, y_hi, y_lo, colour)
        if peak < SILENCE:
            canvas.hline(height - 3, x, x + 1, (235, 120, 90))

    for t in marks:
        canvas.vline(int(t * rate * width / n), 0, height - 1, (235, 120, 90))
    return canvas


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("audio", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--start", type=float, default=None, help="seconds")
    ap.add_argument("--end", type=float, default=None, help="seconds")
    ap.add_argument("--width", type=int, default=1000)
    ap.add_argument("--height", type=int, default=260)
    ap.add_argument("--mark", type=float, action="append", default=[],
                    help="draw a vertical line at this time in seconds (repeatable)")
    args = ap.parse_args()

    samples, rate = read_audio(args.audio)
    total = len(samples) / rate

    lo = int((args.start or 0) * rate)
    hi = int(args.end * rate) if args.end else len(samples)
    view = samples[max(0, lo):min(len(samples), hi)]

    offset = args.start or 0
    marks = [t - offset for t in args.mark if offset <= t <= (args.end or total)]

    render(view, rate, args.width, args.height, marks).write_png(args.out)

    silent = (sum(1 for v in view if abs(v) < SILENCE) / len(view) * 100) if view else 0
    print(f"{args.audio.name}: {total:.2f}s total, drawing "
          f"{offset:.2f}-{args.end if args.end else total:.2f}s "
          f"({silent:.1f}% of the drawn span is silence) -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
