#!/usr/bin/env python3
"""Render a waveform PNG so audio can be *looked at* rather than only measured.

Numbers told me a chunk had 360 ms of trailing silence; a picture shows whether the
first word is clipped, whether a seam is really gapless, and whether an inserted
pause landed where it should. Written with zlib and struct only — no matplotlib or
Pillow — so it runs anywhere numpy does.

Usage:
    tools/waveform.py out.wav --out wave.png
    tools/waveform.py out.wav --out onset.png --start 0 --end 0.25 --height 220
    tools/waveform.py out.wav --out seams.png --mark 3.1 --mark 7.4
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

import numpy as np


# --------------------------------------------------------------------- reading

def read_audio(path: Path) -> tuple[np.ndarray, int]:
    """Return (mono float samples in [-1, 1], sample rate). Handles WAV and raw PCM."""
    data = path.read_bytes()
    if data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        pos = 12
        rate, channels, bits = 24000, 1, 16
        payload = b""
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
        samples = np.frombuffer(payload, dtype="<i2").astype(np.float32) / 32768.0
        if channels > 1:
            samples = samples.reshape(-1, channels).mean(axis=1)
        return samples, rate
    # Raw little-endian mono PCM: assume the engine's 24 kHz.
    samples = np.frombuffer(data[:len(data) // 2 * 2], dtype="<i2").astype(np.float32)
    return samples / 32768.0, 24000


# --------------------------------------------------------------------- drawing

class Canvas:
    """Tiny RGB canvas with just the primitives a waveform needs."""

    def __init__(self, width: int, height: int, background=(18, 18, 22)):
        self.w = width
        self.h = height
        self.px = np.zeros((height, width, 3), dtype=np.uint8)
        self.px[:, :] = background

    def vline(self, x: int, y0: int, y1: int, colour) -> None:
        if not (0 <= x < self.w):
            return
        y0 = max(0, min(self.h - 1, y0))
        y1 = max(0, min(self.h - 1, y1))
        if y0 > y1:
            y0, y1 = y1, y0
        self.px[y0:y1 + 1, x] = colour

    def hline(self, y: int, x0: int, x1: int, colour) -> None:
        if not (0 <= y < self.h):
            return
        self.px[y, max(0, x0):min(self.w, x1)] = colour

    def write_png(self, path: Path) -> None:
        raw = b"".join(b"\x00" + self.px[y].tobytes() for y in range(self.h))

        def chunk(tag: bytes, payload: bytes) -> bytes:
            return (struct.pack(">I", len(payload)) + tag + payload
                    + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

        png = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(raw, 6))
               + chunk(b"IEND", b""))
        path.write_bytes(png)


def render(samples: np.ndarray, rate: int, width: int, height: int,
           marks: list[float], threshold: float, title_bands: bool) -> Canvas:
    """Draw min/max envelope per column, plus a silence band along the bottom."""
    canvas = Canvas(width, height)
    mid = height // 2
    canvas.hline(mid, 0, width, (70, 70, 80))

    n = len(samples)
    if n == 0:
        return canvas

    edges = np.linspace(0, n, width + 1).astype(np.int64)
    for x in range(width):
        a, b = edges[x], edges[x + 1]
        if b <= a:
            continue
        seg = samples[a:b]
        lo = int(seg.min() * (mid - 2))
        hi = int(seg.max() * (mid - 2))
        peak = float(np.abs(seg).max())
        if peak < threshold:
            colour = (60, 60, 70)          # silence: dim, so gaps are obvious
        else:
            colour = (90, 220, 150)        # speech
        canvas.vline(x, mid - hi, mid - lo if lo != hi else mid - lo - 1, colour)

    for t in marks:
        x = int(t * rate / n * width) if n else 0
        canvas.vline(x, 0, height - 1, (235, 120, 90))

    if title_bands:
        # A solid band where the signal is below threshold, so pauses read at a glance.
        for x in range(width):
            a, b = edges[x], edges[x + 1]
            if b <= a:
                continue
            if float(np.abs(samples[a:b]).max()) < threshold:
                canvas.hline(height - 3, x, x + 1, (235, 120, 90))
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
    ap.add_argument("--threshold", type=float, default=200 / 32768.0,
                    help="amplitude below which a sample counts as silence")
    args = ap.parse_args()

    samples, rate = read_audio(args.audio)
    total = len(samples) / rate

    lo = int((args.start or 0) * rate)
    hi = int(args.end * rate) if args.end else len(samples)
    view = samples[max(0, lo):min(len(samples), hi)]

    marks = [t for t in args.mark if (args.start or 0) <= t <= (args.end or total)]
    shifted = [t - (args.start or 0) for t in marks]

    canvas = render(view, rate, args.width, args.height, shifted,
                    args.threshold, title_bands=True)
    canvas.write_png(args.out)

    silent = float(np.mean(np.abs(view) < args.threshold)) * 100 if len(view) else 0
    print(f"{args.audio.name}: {total:.2f}s total, drawing "
          f"{(args.start or 0):.2f}-{(args.end or 0) if args.end else total:.2f}s "
          f"({silent:.1f}% of the drawn span is silence) -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
