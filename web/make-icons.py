#!/usr/bin/env python3
"""Regenerates web/icons/*.png — the same gauge as the Android launcher icon. Needs Pillow."""
import math
import os

from PIL import Image, ImageDraw

BG, TRACK, ACCENT, WHITE = "#0B0F14", "#2A3440", "#3DDC97", "#FFFFFF"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons")


def point(cx, cy, r, deg):
    return cx + r * math.cos(math.radians(deg)), cy + r * math.sin(math.radians(deg))


def arc(draw, c, r, start, end, width, color):
    draw.arc([c - r, c - r, c + r, c + r], start, end, fill=color, width=width)
    for deg in (start, end):  # round caps
        x, y = point(c, c, r - width / 2, deg)
        draw.ellipse([x - width / 2, y - width / 2, x + width / 2, y + width / 2], fill=color)


def icon(size):
    s = size * 4  # supersample, then shrink for smooth edges
    img = Image.new("RGB", (s, s), BG)
    d = ImageDraw.Draw(img)
    c, r, w = s / 2, s * 0.30, int(s * 0.07)
    arc(d, c, r, 135, 405, w, TRACK)
    arc(d, c, r, 135, 300, w, ACCENT)
    tip = point(c, c, r * 0.62, 300)
    d.line([c, c, tip[0], tip[1]], fill=WHITE, width=int(s * 0.04))
    for x, y, rad in ((c, c, s * 0.04), (tip[0], tip[1], s * 0.02)):
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=WHITE)
    return img.resize((size, size), Image.LANCZOS)


os.makedirs(OUT, exist_ok=True)
for size in (180, 192, 512):
    icon(size).save(os.path.join(OUT, f"icon-{size}.png"), optimize=True)
    print("icon", size)
