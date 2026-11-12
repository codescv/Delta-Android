#!/usr/bin/env python3
"""Generates the 20 alternate Delta launcher icons (Settings > App Icon, design §5.5).

Dependency-free (stdlib only). Outputs, for every icon in ICONS:
  app/src/main/res/mipmap-<density>/ic_launcher_<name>.png   legacy 48 dp icon
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_<name>.xml  adaptive icon
Accent icons are the accent colour + a white Delta glyph (a shared vector,
mipmap-anydpi-v26/ic_launcher_delta_glyph.xml). The Glass "Default" icon (AppIcon) is
resampled from the real artwork in app/src/main/assets/AppIcons/AppIcon-1024.png.

Run from the repository root:  python3 tools/gen_app_icons.py
Keep ICONS in sync with com.codescv.delta.ui.settings.AppIcons.
"""

import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
APPICON_SRC = os.path.join(ROOT, "app", "src", "main", "assets", "AppIcons", "AppIcon-1024.png")

# (imageName, accent) in DeltaIconSection order; ui_spec §6.8.
ICONS = [
    ("AppIcon", 0x8A2BE2),
    ("ModernIcon", 0x7C3AED),
    ("DeltaRed", 0xEF4444),
    ("GBA4iOS", 0x6D28D9),
    ("BrokenIcon", 0x4C1D95),
    ("OriginalIcon", 0x9333EA),
    ("DeltaGold", 0xF59E0B),
    ("Halogenide", 0x3B82F6),
    ("KetchupIcon", 0xDC2626),
    ("MicrochipIcon", 0x10B981),
    ("NESIcon", 0xE11D48),
    ("SNESIcon", 0x7C3AED),
    ("N64Icon", 0x2563EB),
    ("GBCIcon", 0x6D28D9),
    ("GBCIcon_Dandelion", 0xEAB308),
    ("GBCIcon_Berry", 0xEC4899),
    ("GBCIcon_Teal", 0x0D9488),
    ("GBCIcon_Kiwi", 0x84CC16),
    ("GBIcon", 0x64748B),
    ("GBAIcon", 0x4F46E5),
]

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]


def res_name(image_name):
    return "ic_launcher_" + image_name.lower()


# ---------------------------------------------------------------- PNG I/O


def write_png(path, width, height, rgba):
    """rgba: bytearray of width*height*4."""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def read_png(path):
    """Decodes an 8-bit, non-interlaced RGB/RGBA PNG -> (w, h, rgba bytearray)."""
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, w = 8, bytearray(), 0
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and interlace == 0, "unsupported PNG"
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break
    bpp = 3 if ctype == 2 else 4
    raw = zlib.decompress(bytes(idat))
    stride = w * bpp
    out = bytearray(w * h * 4)
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        ft = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if ft == 1:
                line[x] = (line[x] + a) & 0xFF
            elif ft == 2:
                line[x] = (line[x] + b) & 0xFF
            elif ft == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 0xFF
            elif ft == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        for x in range(w):
            o = (y * w + x) * 4
            s = x * bpp
            out[o:o + 3] = line[s:s + 3]
            out[o + 3] = line[s + 3] if bpp == 4 else 255
        prev = line
    return w, h, out


def resample(src_w, src_h, src, size):
    """Box-filter downscale of a square RGBA image to size x size."""
    out = bytearray(size * size * 4)
    for oy in range(size):
        y0 = oy * src_h // size
        y1 = max(y0 + 1, (oy + 1) * src_h // size)
        for ox in range(size):
            x0 = ox * src_w // size
            x1 = max(x0 + 1, (ox + 1) * src_w // size)
            acc = [0, 0, 0, 0]
            for y in range(y0, y1):
                row = y * src_w
                for x in range(x0, x1):
                    o = (row + x) * 4
                    acc[0] += src[o]
                    acc[1] += src[o + 1]
                    acc[2] += src[o + 2]
                    acc[3] += src[o + 3]
            n = (y1 - y0) * (x1 - x0)
            o = (oy * size + ox) * 4
            out[o:o + 4] = bytes(v // n for v in acc)
    return out


# ---------------------------------------------------------------- geometry


def delta_triangles(cx, cy, side, stroke):
    """Outer and inner (hollow) equilateral triangles of the Delta glyph, centroid at (cx, cy)."""
    h = side * math.sqrt(3) / 2
    outer = [(cx, cy - 2 * h / 3), (cx + side / 2, cy + h / 3), (cx - side / 2, cy + h / 3)]
    r = h / 3  # inradius (centroid == incenter)
    k = max(0.0, (r - stroke) / r)
    inner = [(cx + (x - cx) * k, cy + (y - cy) * k) for (x, y) in outer]
    return outer, inner


def in_triangle(px, py, tri):
    (x1, y1), (x2, y2), (x3, y3) = tri
    d1 = (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2)
    d2 = (px - x3) * (y2 - y3) - (x2 - x3) * (py - y3)
    d3 = (px - x1) * (y3 - y1) - (x3 - x1) * (py - y1)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


def in_round_rect(px, py, x0, y0, x1, y1, r):
    if px < x0 or px > x1 or py < y0 or py > y1:
        return False
    cx = min(max(px, x0 + r), x1 - r)
    cy = min(max(py, y0 + r), y1 - r)
    return (px - cx) ** 2 + (py - cy) ** 2 <= r * r


# Glyph geometry in the 108-unit adaptive-icon viewport (inside the 66-unit safe zone).
GLYPH_SIDE = 42.0
GLYPH_STROKE = 6.5


def legacy_accent_icon(size, accent, ss=3):
    """48 dp legacy icon: r(10/44) rounded square of the accent colour + white Delta glyph."""
    ar, ag, ab = (accent >> 16) & 0xFF, (accent >> 8) & 0xFF, accent & 0xFF
    margin = size * 2.0 / 48.0
    x0, y0, x1, y1 = margin, margin, size - margin, size - margin
    radius = (x1 - x0) * 10.0 / 44.0
    scale = (x1 - x0) / 72.0  # card == visible 72-unit adaptive area
    outer, inner = delta_triangles(size / 2, size / 2, GLYPH_SIDE * scale, GLYPH_STROKE * scale)
    out = bytearray(size * size * 4)
    n = ss * ss
    for y in range(size):
        for x in range(size):
            card = glyph = 0
            for sy in range(ss):
                py = y + (sy + 0.5) / ss
                for sx in range(ss):
                    px = x + (sx + 0.5) / ss
                    if in_round_rect(px, py, x0, y0, x1, y1, radius):
                        card += 1
                        if in_triangle(px, py, outer) and not in_triangle(px, py, inner):
                            glyph += 1
            if card == 0:
                continue
            g = glyph / card
            # subtle top-to-bottom shading like iOS `color.gradient`
            shade = 1.08 - 0.16 * (y / size)
            r = min(255, ar * shade) * (1 - g) + 255 * g
            gg = min(255, ag * shade) * (1 - g) + 255 * g
            b = min(255, ab * shade) * (1 - g) + 255 * g
            o = (y * size + x) * 4
            out[o:o + 4] = bytes((int(r), int(gg), int(b), round(255 * card / n)))
    return out


def mask_round_rect(rgba, size, inset_frac, radius_frac):
    out = bytearray(rgba)
    margin = size * inset_frac
    x0, y0, x1, y1 = margin, margin, size - margin, size - margin
    radius = (x1 - x0) * radius_frac
    ss = 3
    for y in range(size):
        for x in range(size):
            cov = 0
            for sy in range(ss):
                for sx in range(ss):
                    if in_round_rect(x + (sx + 0.5) / ss, y + (sy + 0.5) / ss, x0, y0, x1, y1, radius):
                        cov += 1
            o = (y * size + x) * 4 + 3
            out[o] = out[o] * cov // (ss * ss)
    return out


def glyph_vector():
    outer, inner = delta_triangles(54.0, 54.0, GLYPH_SIDE, GLYPH_STROKE)

    def path(tri):
        (a, b, c) = tri
        return "M%.2f,%.2f L%.2f,%.2f L%.2f,%.2f Z" % (a + b + c)

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/gen_app_icons.py: white Delta glyph for adaptive launcher icons. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        "    <path\n"
        '        android:fillColor="#FFFFFFFF"\n'
        '        android:fillType="evenOdd"\n'
        '        android:pathData="%s %s" />\n'
        "</vector>\n" % (path(outer), path(inner))
    )


def adaptive_xml(background, foreground, monochrome=None):
    mono = '    <monochrome android:drawable="%s" />\n' % monochrome if monochrome else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/gen_app_icons.py -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        "    <background>\n"
        '        <color android:color="%s" />\n'
        "    </background>\n"
        '    <foreground android:drawable="%s" />\n'
        "%s"
        "</adaptive-icon>\n" % (background, foreground, mono)
    )


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    write_text(os.path.join(anydpi, "ic_launcher_delta_glyph.xml"), glyph_vector())

    src = read_png(APPICON_SRC) if os.path.isfile(APPICON_SRC) else None
    for image_name, accent in ICONS:
        name = res_name(image_name)
        for density, k in DENSITIES:
            size = int(round(48 * k))
            folder = os.path.join(RES, "mipmap-" + density)
            if image_name == "AppIcon" and src is not None:
                art = resample(src[0], src[1], src[2], size)
                write_png(os.path.join(folder, name + ".png"), size, size, mask_round_rect(art, size, 2.0 / 48, 10.0 / 44))
                # Adaptive foreground: artwork scaled to 76 of 108 units, centred.
                fg_size = int(round(108 * k))
                inner = int(round(76 * k))
                art_fg = resample(src[0], src[1], src[2], inner)
                fg = bytearray(fg_size * fg_size * 4)
                off = (fg_size - inner) // 2
                for y in range(inner):
                    row = ((y + off) * fg_size + off) * 4
                    fg[row:row + inner * 4] = art_fg[y * inner * 4:(y + 1) * inner * 4]
                write_png(os.path.join(folder, name + "_foreground.png"), fg_size, fg_size, fg)
            else:
                write_png(os.path.join(folder, name + ".png"), size, size, legacy_accent_icon(size, accent))
        if image_name == "AppIcon" and src is not None:
            xml = adaptive_xml("#FF000000", "@mipmap/%s_foreground" % name, "@mipmap/ic_launcher_delta_glyph")
        else:
            xml = adaptive_xml("#FF%06X" % accent, "@mipmap/ic_launcher_delta_glyph", "@mipmap/ic_launcher_delta_glyph")
        write_text(os.path.join(anydpi, name + ".xml"), xml)
        print("generated", name, file=sys.stderr)


if __name__ == "__main__":
    main()
