#!/usr/bin/env python3
"""Render the Play Store 1024x500 feature graphic.

The app's route-lines mark and wordmark on the left, and on the right a card of three
departure rows styled like the app's list (line pills in the official TfL colors, as
LinePill draws them). The mark mirrors scripts/render-store-icon.py. The rows are real
services from King's Cross St. Pancras (the 390 bus, the Victoria line southbound, the Circle
line clockwise) in the icon's red, blue and yellow order, with made-up countdowns, not
anyone's watched stops.

Deterministic for a given font, so the committed docs/play-store/feature-graphic.png
should match a fresh run. Needs Pillow (`pip install Pillow`) and the Inter font from
@fontsource/inter 5.3.0 (OFL):

    npm pack @fontsource/inter@5.3.0 && tar xzf fontsource-inter-5.3.0.tgz
    python3 scripts/render-feature-graphic.py package/files
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "docs", "play-store", "feature-graphic.png")
FONTS = sys.argv[1] if len(sys.argv) > 1 else "package/files"

W, H, SS = 1024, 500, 4
BG = "#FFFFFF"
INK = "#1D1B20"         # on-surface
INK_MUTED = "#49454F"   # on-surface-variant
OUTLINE = "#CAC4D0"     # card border and row divider

# (code, fill, border, label, destination, countdown)
ROWS = [
    ("390", "#DC241F", "#EA7C78", "#FFFFFF", "Victoria", "1 min"),
    ("VIC", "#0098D4", "#6FC3E6", "#FFFFFF", "Brixton", "3 min"),
    ("CIR", "#FFD300", "#7A6500", "#000000", "Edgware Road", "8 min"),
]


def font(weight, size):
    return ImageFont.truetype(os.path.join(FONTS, f"inter-latin-{weight}-normal.woff"), size * SS)


def s(v):
    return v * SS


img = Image.new("RGB", (s(W), s(H)), BG)
d = ImageDraw.Draw(img)

# The route-lines mark (the icon's 22..84 x 43..65 design units), scaled to MARK_W wide.
MARK_X, MARK_Y, MARK_W = 64, 132, 104
u = MARK_W / 62.0


def mx(x):
    return s(MARK_X + (x - 22) * u)


def my(y):
    return s(MARK_Y + (y - 39.5) * u)


for cy, col in [(43, "#E32017"), (54, "#0098D4"), (65, "#FFD300")]:
    d.rounded_rectangle([mx(22), my(cy - 3.5), mx(66), my(cy + 3.5)], radius=s(3.5 * u), fill=col)
aw = int(round(s(7 * u)))
d.line([mx(64), my(54), mx(84), my(54)], fill="#000000", width=aw)
d.line([mx(77), my(47), mx(84), my(54), mx(77), my(61)], fill="#000000", width=aw, joint="curve")
for x, y in [(77, 47), (77, 61), (64, 54)]:
    r = aw / 2
    d.ellipse([mx(x) - r, my(y) - r, mx(x) + r, my(y) + r], fill="#000000")

# Wordmark and tagline.
d.text((s(64), s(208)), "LDNGo", font=font(700, 72), fill=INK)
tag = font(500, 30)
d.text((s(64), s(308)), "Live London departures,", font=tag, fill=INK_MUTED)
d.text((s(64), s(348)), "at a glance", font=tag, fill=INK_MUTED)

# The place header and its departures card.
CX1, CY1, CX2 = 464, 108, 960
d.text((s(CX1 + 8), s(CY1 - 16)), "King's Cross St. Pancras", font=font(500, 24), fill=INK_MUTED,
       anchor="ls")
ROW_H = 104
CY2 = CY1 + ROW_H * len(ROWS)
d.rounded_rectangle([s(CX1), s(CY1), s(CX2), s(CY2)], radius=s(24), fill=BG, outline=OUTLINE, width=s(2))
pill_font, row_font = font(700, 24), font(500, 30)
for i, (code, fill, border, label, dest, mins) in enumerate(ROWS):
    top = CY1 + i * ROW_H
    mid = top + ROW_H / 2
    if i:
        d.line([s(CX1), s(top), s(CX2), s(top)], fill=OUTLINE, width=s(2))
    px1, px2 = CX1 + 28, CX1 + 28 + 96
    d.rounded_rectangle([s(px1), s(mid - 24), s(px2), s(mid + 24)], radius=s(12), fill=fill,
                        outline=border, width=s(2))
    d.text((s((px1 + px2) / 2), s(mid)), code, font=pill_font, fill=label, anchor="mm")
    d.text((s(px2 + 20), s(mid)), dest, font=row_font, fill=INK, anchor="lm")
    d.text((s(CX2 - 28), s(mid)), mins, font=row_font, fill=INK, anchor="rm")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.resize((W, H), Image.LANCZOS).save(OUT)
print("wrote", os.path.normpath(OUT))
