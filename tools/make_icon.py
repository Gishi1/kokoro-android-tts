"""Generate the app icon: a Kokoro heart carrying an audio waveform.

Kokoro is Japanese for "heart" (心), and the app turns text into speech, so the mark
is a heart whose fill is a graphic-equaliser waveform. It is drawn from scratch here
rather than reusing upstream artwork, which keeps the app free of any third-party
asset or trademark claim -- F-Droid's inclusion policy requires that artwork in a
submitted app be properly licensed.

Everything is rendered at 4x and box-filtered down, because `pngtool.resize` is
nearest-neighbour and would leave the curves jagged.

    python3 tools/make_icon.py            # regenerate android/res/
    python3 tools/make_icon.py --preview  # also write a big preview PNG to /tmp
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pngtool  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "android" / "res"

# Legacy launcher icons, and the same densities for the adaptive layers. An
# adaptive icon's canvas is 108dp with only the middle ~66dp guaranteed visible,
# so the foreground is drawn smaller than the legacy icon's artwork.
DENSITIES = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
             ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]

BG_INNER = (0x0B, 0x2B, 0x28)      # deep teal, centre of the radial gradient
BG_OUTER = (0x04, 0x14, 0x13)      # nearly black at the corners
HEART_DARK = (0x0E, 0x6B, 0x62)    # the heart where no bar covers it
HEART_MID = (0x18, 0x9E, 0x90)
HEART_BRIGHT = (0x5A, 0xE8, 0xD4)  # the waveform bars themselves
SS = 4                             # supersampling factor


def heart_sdf(x: float, y: float) -> float:
    """Implicit heart, negative inside. y points up, roughly [-1.3, 1.3]."""
    return (x * x + y * y - 1.0) ** 3 - x * x * y * y * y


def heart_bounds(samples: int = 400) -> tuple:
    """Bounding box of the heart curve, so it can be centred exactly."""
    xs, ys = [], []
    for i in range(samples):
        for j in range(samples):
            x = -1.6 + 3.2 * i / (samples - 1)
            y = -1.6 + 3.2 * j / (samples - 1)
            if heart_sdf(x, y) < 0:
                xs.append(x)
                ys.append(y)
    return min(xs), max(xs), min(ys), max(ys)


HB = heart_bounds()

# The equaliser: bar heights as a fraction of the heart's half-height. Fixed rather
# than random so the icon is reproducible, and shaped so it reads as speech.
BARS = [0.22, 0.42, 0.68, 0.96, 0.72, 0.48, 0.78, 0.54, 0.30]


def heart_layer(size: int, span: float):
    """Render the heart at `span` of the canvas, centred, on transparency."""
    n = size * SS
    px = bytearray(n * n * 4)
    cx = cy = n / 2.0
    half = n * span / 2.0

    # Normalise the heart's own bounding box onto a square of side 2*half.
    hx0, hx1, hy0, hy1 = HB
    hw, hh = hx1 - hx0, hy1 - hy0
    scale = (2.0 * half) / max(hw, hh)
    mid_x, mid_y = (hx0 + hx1) / 2.0, (hy0 + hy1) / 2.0

    for py in range(n):
        for pxi in range(n):
            o = (py * n + pxi) * 4
            # canvas -> heart space (flip y: the curve is defined with y up)
            hx = (pxi - cx) / scale + mid_x
            hy = -(py - cy) / scale + mid_y
            if heart_sdf(hx, hy) >= 0:
                continue

            # Which bar, if any, covers this pixel?
            u = (hx - hx0) / hw                      # 0..1 across the heart
            idx = min(len(BARS) - 1, max(0, int(u * len(BARS))))
            bar_c = (idx + 0.5) / len(BARS)
            v = (hy - hy0) / hh                      # 0..1 bottom..top
            in_bar = (abs(v - 0.5) * 2.0) < BARS[idx] and \
                     (abs(u - bar_c) * len(BARS)) < 0.34

            if in_bar:
                # Brighten towards the middle of a bar for a little depth.
                t = 1.0 - min(1.0, abs(v - 0.5) * 2.4)
                col = tuple(int(HEART_BRIGHT[i] * t + HEART_MID[i] * (1 - t))
                            for i in range(3))
            else:
                col = HEART_DARK
            px[o], px[o + 1], px[o + 2], px[o + 3] = col[0], col[1], col[2], 255

    return downsample(px, n, size)


def downsample(px, n: int, size: int):
    """Box-filter n x n RGBA down to size x size (n must be a multiple of size)."""
    factor = n // size
    out = bytearray(size * size * 4)
    area = factor * factor
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(factor):
                row = ((y * factor + dy) * n + x * factor) * 4
                for dx in range(factor):
                    o = row + dx * 4
                    r += px[o]
                    g += px[o + 1]
                    b += px[o + 2]
                    a += px[o + 3]
            o = (y * size + x) * 4
            out[o], out[o + 1], out[o + 2], out[o + 3] = (
                r // area, g // area, b // area, a // area)
    return out


def radial_bg(u: float, v: float) -> tuple:
    """Radial gradient, lit slightly up and to the left."""
    dx, dy = (u - 0.42) * 1.25, (v - 0.38) * 1.25
    t = min(1.0, (dx * dx + dy * dy) ** 0.5)
    return tuple(int(BG_INNER[i] + (BG_OUTER[i] - BG_INNER[i]) * t**1.15)
                 for i in range(3))


def rounded_square(size: int, radius_frac: float = 0.22):
    """Opaque rounded-square mask used as the legacy icon's background."""
    n = size * SS
    px = bytearray(n * n * 4)
    r = n * radius_frac
    for y in range(n):
        for x in range(n):
            dx = max(r - x, 0, x - (n - 1 - r))
            dy = max(r - y, 0, y - (n - 1 - r))
            if dx * dx + dy * dy > r * r:
                continue          # outside the rounded corner
            col = radial_bg(x / n, y / n)
            o = (y * n + x) * 4
            px[o], px[o + 1], px[o + 2], px[o + 3] = col[0], col[1], col[2], 255
    return downsample(px, n, size)


def compose(bg, fg, size: int):
    """Source-over `fg` (RGBA) onto `bg`."""
    out = bytearray(bg)
    for i in range(0, size * size * 4, 4):
        a = fg[i + 3]
        if not a:
            continue
        inv = 255 - a
        out[i] = (fg[i] * a + out[i] * inv) // 255
        out[i + 1] = (fg[i + 1] * a + out[i + 1] * inv) // 255
        out[i + 2] = (fg[i + 2] * a + out[i + 2] * inv) // 255
        out[i + 3] = 255
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="also write a large preview to /tmp/icon-preview.png")
    args = ap.parse_args()

    ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive icon (API 26+). The system masks whatever shape the launcher wants, so
  the background is full-bleed and the foreground stays inside the 66dp safe zone.
  Generated by tools/make_icon.py; edit that, not this.
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

    for name, legacy_px, adaptive_px in DENSITIES:
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)

        # Adaptive layers: full-bleed gradient background, heart inside the
        # safe zone (66/108 of the canvas) on a transparent foreground. The
        # gradient is smooth, so it needs no supersampling.
        bgimg = _full(radial_bg, adaptive_px)
        pngtool.write_png(d / "ic_launcher_background.png", adaptive_px, adaptive_px, bgimg)

        fg = heart_layer(adaptive_px, span=0.60)
        pngtool.write_png(d / "ic_launcher_foreground.png", adaptive_px, adaptive_px, fg)

        # Legacy icon (API 21-25): rounded square with the heart drawn larger,
        # since nothing masks it and no safe zone applies.
        plate = rounded_square(legacy_px)
        art = heart_layer(legacy_px, span=0.72)
        pngtool.write_png(d / "ic_launcher.png", legacy_px, legacy_px,
                          compose(plate, art, legacy_px))
        print(f"  mipmap-{name}: ic_launcher {legacy_px}px, "
              f"adaptive {adaptive_px}px (bg + fg)")

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(ADAPTIVE_XML, encoding="utf-8")
    print(f"  mipmap-anydpi-v26/ic_launcher.xml")

    if args.preview:
        size = 432
        plate = rounded_square(size)
        art = heart_layer(size, span=0.72)
        pngtool.write_png("/tmp/icon-preview.png", size, size,
                          compose(plate, art, size))
        print("  /tmp/icon-preview.png")

    return 0


def _full(fn, n: int):
    """Render a colour function over an n x n canvas (no transparency)."""
    px = bytearray(n * n * 4)
    for y in range(n):
        for x in range(n):
            r, g, b = fn(x / n, y / n)
            o = (y * n + x) * 4
            px[o], px[o + 1], px[o + 2], px[o + 3] = r, g, b, 255
    return px


if __name__ == "__main__":
    sys.exit(main())
