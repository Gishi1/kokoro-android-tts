"""Minimal PNG decode/encode and compositing, standard library only.

`tools/waveform.py` deliberately has no third-party dependencies, and this follows
it so the repository needs no Pillow or ImageMagick to rebuild its documentation
figures. It supports what those figures actually are: 8-bit non-interlaced PNG,
greyscale/RGB/RGBA, all five scanline filters.

Commands:

    # one wide figure holding every panel, N per row
    python3 tools/pngtool.py montage --out fig.png --cols 3 --width 440 a.png b.png ...

    # blank out a rectangle, e.g. before publishing a screenshot
    python3 tools/pngtool.py redact --in raw.png --out clean.png --box 30,455,690,510

    # what is in a file
    python3 tools/pngtool.py info fig.png

Colours are `RRGGBB` or `RRGGBBAA`. Boxes are `x0,y0,x1,y1`, x1/y1 exclusive.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

__all__ = ["read_png", "write_png", "fill_rect", "resize", "paste", "montage"]

CHANNELS = {0: 1, 2: 3, 4: 2, 6: 4}


def _unfilter(raw: bytes, w: int, h: int, channels: int) -> list:
    """Reverse the per-scanline PNG filters; returns one bytearray per row."""
    stride = w * channels
    rows = []
    prev = bytearray(stride)
    pos = 0
    for _ in range(h):
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if ftype == 0:
            pass
        elif ftype == 1:                       # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:                       # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:                       # Average
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:                       # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                c = prev[i - channels] if i >= channels else 0
                b = prev[i]
                pa = abs(b - c)
                pb = abs(a - c)
                pc = abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        else:
            raise ValueError(f"unknown scanline filter {ftype}")
        rows.append(line)
        prev = line
    return rows


def read_png(path) -> tuple:
    """(width, height, bytearray RGBA). Everything is widened to RGBA."""
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path}: not a PNG")
    pos, idat = 8, bytearray()
    w = h = depth = ctype = interlace = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctag == b"IHDR":
            w, h, depth, ctype, _comp, _filt, interlace = struct.unpack(">IIBBBBB", body)
        elif ctag == b"IDAT":
            idat += body
        elif ctag == b"IEND":
            break
    if depth != 8:
        raise ValueError(f"{path}: only 8-bit PNG is supported (got {depth})")
    if interlace:
        raise ValueError(f"{path}: interlaced PNG is not supported")
    if ctype not in CHANNELS:
        raise ValueError(f"{path}: unsupported colour type {ctype}")

    channels = CHANNELS[ctype]
    rows = _unfilter(zlib.decompress(bytes(idat)), w, h, channels)

    out = bytearray(w * h * 4)
    for y, line in enumerate(rows):
        base = y * w * 4
        if channels == 4:
            out[base:base + w * 4] = line
        elif channels == 3:
            for x in range(w):
                o, i = base + x * 4, x * 3
                out[o] = line[i]
                out[o + 1] = line[i + 1]
                out[o + 2] = line[i + 2]
                out[o + 3] = 255
        elif channels == 1:
            for x in range(w):
                v, o = line[x], base + x * 4
                out[o] = out[o + 1] = out[o + 2] = v
                out[o + 3] = 255
        else:                                  # greyscale + alpha
            for x in range(w):
                v, o = line[x * 2], base + x * 4
                out[o] = out[o + 1] = out[o + 2] = v
                out[o + 3] = line[x * 2 + 1]
    return w, h, out


def write_png(path, w: int, h: int, rgba) -> None:
    """Write an RGBA buffer as an 8-bit PNG, one filter-0 scanline at a time."""
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag: bytes, body: bytes) -> bytes:
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF))

    Path(path).write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b""))


def fill_rect(rgba, w: int, h: int, x0: int, y0: int, x1: int, y1: int,
              colour: str = "FFFFFF") -> None:
    """Paint a solid rectangle, clipped to the image."""
    rgba_col = parse_colour(colour)
    x0, x1 = max(0, min(x0, x1)), min(w, max(x0, x1))
    y0, y1 = max(0, min(y0, y1)), min(h, max(y0, y1))
    row = bytes(rgba_col) * (x1 - x0)
    for y in range(y0, y1):
        base = (y * w + x0) * 4
        rgba[base:base + (x1 - x0) * 4] = row


def parse_colour(text: str) -> tuple:
    t = text.lstrip("#")
    if len(t) == 6:
        t += "FF"
    if len(t) != 8:
        raise ValueError(f"colour must be RRGGBB or RRGGBBAA, got {text!r}")
    return tuple(int(t[i:i + 2], 16) for i in (0, 2, 4, 6))


def resize(rgba, w: int, h: int, new_w: int, new_h: int):
    """Nearest-neighbour resample. Fine for UI screenshots; it never invents pixels."""
    out = bytearray(new_w * new_h * 4)
    xmap = [min(w - 1, (x * w) // new_w) * 4 for x in range(new_w)]
    for y in range(new_h):
        src_y = min(h - 1, (y * h) // new_h)
        src_base = src_y * w * 4
        dst_base = y * new_w * 4
        for x in range(new_w):
            o, i = dst_base + x * 4, src_base + xmap[x]
            out[o:o + 4] = rgba[i:i + 4]
    return out


def paste(dst, dw: int, dh: int, src, sw: int, sh: int, x: int, y: int) -> None:
    """Copy src into dst at (x, y), clipped."""
    for row in range(sh):
        dy = y + row
        if not (0 <= dy < dh):
            continue
        sx0 = max(0, -x)
        dx0 = max(0, x)
        span = min(sw - sx0, dw - dx0)
        if span <= 0:
            continue
        s = (row * sw + sx0) * 4
        d = (dy * dw + dx0) * 4
        dst[d:d + span * 4] = src[s:s + span * 4]


def montage(paths, cols: int, cell_w: int = 0, gap: int = 12, margin: int = 12,
            background: str = "FFFFFF"):
    """Lay panels out in a grid, at most `cols` per row, scaled to a common width.

    Cell height follows each panel's own aspect ratio, and every row is as tall as
    its tallest panel, so uneven panels stay top-aligned rather than stretched.
    """
    panels = []
    for p in paths:
        w, h, px = read_png(p)
        target_w = cell_w or w
        target_h = max(1, round(h * target_w / w))
        if (target_w, target_h) != (w, h):
            px = resize(px, w, h, target_w, target_h)
        panels.append((target_w, target_h, px))

    rows = [panels[i:i + cols] for i in range(0, len(panels), cols)]
    row_heights = [max(p[1] for p in r) for r in rows]
    out_w = margin * 2 + cols * max(p[0] for p in panels) + gap * (cols - 1)
    out_h = margin * 2 + sum(row_heights) + gap * (len(rows) - 1)

    out = bytearray(parse_colour(background) * (out_w * out_h))
    for r, row in enumerate(rows):
        y = margin + sum(row_heights[:r]) + gap * r
        for c, (pw, ph, px) in enumerate(row):
            x = margin + c * (max(p[0] for p in panels) + gap)
            paste(out, out_w, out_h, px, pw, ph, x, y)
    return out_w, out_h, out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    m = sub.add_parser("montage", help="combine panels into one grid image")
    m.add_argument("panels", nargs="+")
    m.add_argument("--out", required=True)
    m.add_argument("--cols", type=int, default=3)
    m.add_argument("--width", type=int, default=0,
                   help="scale each panel to this width (default: leave as-is)")
    m.add_argument("--gap", type=int, default=12)
    m.add_argument("--margin", type=int, default=12)
    m.add_argument("--background", default="FFFFFF")

    r = sub.add_parser("redact", help="paint a solid rectangle over part of an image")
    r.add_argument("--in", dest="src", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--box", required=True, help="x0,y0,x1,y1 (x1/y1 exclusive)")
    r.add_argument("--colour", default="FFFFFF")

    i = sub.add_parser("info", help="print size and format")
    i.add_argument("src")

    args = ap.parse_args()

    if args.cmd == "montage":
        w, h, px = montage(args.panels, args.cols, args.width, args.gap,
                           args.margin, args.background)
        write_png(args.out, w, h, px)
        print(f"{args.out}: {w}x{h} from {len(args.panels)} panels, {args.cols} per row")
    elif args.cmd == "redact":
        x0, y0, x1, y1 = (int(v) for v in args.box.split(","))
        w, h, px = read_png(args.src)
        fill_rect(px, w, h, x0, y0, x1, y1, args.colour)
        write_png(args.out, w, h, px)
        print(f"{args.out}: blanked {x1 - x0}x{y1 - y0} at ({x0},{y0})")
    else:
        w, h, _ = read_png(args.src)
        print(f"{args.src}: {w}x{h} RGBA")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        sys.exit(1)
