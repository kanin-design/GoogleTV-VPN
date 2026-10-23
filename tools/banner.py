"""Draws the Android TV banner: the app's lit tunnel on the right, the mark and the app's name on the left.

The name is appName in gradle.properties; run this again after changing it. The tunnel uses the same
geometry as ui/tv/Tunnel.kt (1280 x 720 design canvas), in its connected green, frozen at one moment.
Rendered at 2x and scaled down into app/src/main/res/drawable-xhdpi and drawable-xxxhdpi.

usage: python3 tools/banner.py FONT
FONT is Roboto, the TV's own font: adb pull /system/fonts/Roboto-Regular.ttf
"""
import math
import pathlib
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = sys.argv[1]
ROOT = pathlib.Path(__file__).resolve().parent.parent
NAME = next(line.split("=", 1)[1].strip() for line in (ROOT / "gradle.properties").read_text().splitlines() if line.startswith("appName="))
RES = ROOT / "app/src/main/res"
SS = 2                      # supersampling
W, H = 1280 * SS, 720 * SS
VX, VY, U = 1030 * SS, 360 * SS, 250 * SS
N, SP = 18, 0.75
ZMAX = N * SP
TONE = np.array([96, 240, 190], float) / 255
CORE = TONE + (1 - TONE) * 0.45

img = np.zeros((H, W, 3), float)
img[:] = np.array([3, 5, 8], float) / 255


def smooth(a, b, x):
    t = min(max((x - a) / (b - a), 0), 1)
    return t * t * (3 - 2 * t)


def add(mask_img, color, alpha):
    m = np.asarray(mask_img, float) / 255
    img[:] += m[..., None] * color * alpha


def radial(x, y, r, color, alpha):
    yy, xx = np.ogrid[:H, :W]
    d = np.sqrt((xx - x) ** 2 + (yy - y) ** 2) / r
    g = np.clip(1 - d, 0, 1)
    img[:] += g[..., None] * color * alpha


radial(VX, VY, 640 * SS, TONE, 0.2)
radial(VX, VY, 260 * SS, TONE, 0.32)

# The floor: a faint band at the horizon that darkens toward the bottom.
yy = np.arange(H)[:, None]
t = np.clip((yy - (VY - 6 * SS)) / (H - (VY - 6 * SS)), 0, 1)
floor = np.where(t < 0.04, t / 0.04 * 0.05, np.where(t < 0.3, 0.05 * (1 - (t - 0.04) / 0.26), 0))
img[:] += floor[..., None] * TONE
dark = np.clip((t - 0.3) / 0.7, 0, 1) * 0.9 * (yy > VY)
img[:] *= (1 - dark[..., None] * 0.9)


def arch(draw, s, w, mirror):
    half = 1.15 * s * U
    floor_y = VY + 0.95 * s * U
    wall = s * U
    cy = floor_y + wall if mirror else floor_y - wall
    top = floor_y if mirror else floor_y - wall
    for x in (VX - half, VX + half):
        draw.line([(x, top), (x, top + wall)], fill=255, width=max(1, int(round(w))))
    box = [VX - half, cy - half, VX + half, cy + half]
    draw.arc(box, 0 if mirror else 180, 180 if mirror else 360, fill=255, width=max(1, int(round(w))))


for i in range(N):
    z = 0.5 + i * SP + 0.3          # a moment of the drift
    zn = z / ZMAX
    alpha = smooth(0.42, 1.25, z) * (1 - smooth(0.72, 1, zn)) * (0.22 + 0.78 * zn ** 0.75)
    if alpha < 0.005:
        continue
    s = 1 / z
    lw = min(max(0.012 * s * U, 0.6 * SS), 7 * SS)
    for mirror, k in ((True, 0.2), (False, 1.0)):
        for width, color, a in ((lw * 7, TONE, 0.045), (lw * 2.6, TONE, 0.16), (lw, CORE, 0.85)):
            m = Image.new("L", (W, H), 0)
            arch(ImageDraw.Draw(m), s, width, mirror)
            if width > lw:
                m = m.filter(ImageFilter.GaussianBlur(width / 3))
            add(m, color, a * alpha * k)

# The two rays along the floor.
for side in (-1, 1):
    m = Image.new("L", (W, H), 0)
    ImageDraw.Draw(m).line([(VX, VY), (VX + side * 1.15 * 3 * U, VY + 0.95 * 3 * U)], fill=255, width=2 * SS)
    yy2, xx2 = np.ogrid[:H, :W]
    fade = np.clip(1 - np.sqrt((xx2 - VX) ** 2 + (yy2 - VY) ** 2) / (3.5 * U), 0, 1)
    add(Image.fromarray((np.asarray(m, float) * fade).astype(np.uint8)), TONE, 0.22)

radial(VX, VY - 4 * SS, 90 * SS, CORE, 0.55)
radial(VX, VY - 4 * SS, 26 * SS, np.ones(3), 0.85)

# Vignette, then the hero scrim on the left so the name reads.
yy3, xx3 = np.ogrid[:H, :W]
d = np.sqrt((xx3 - W / 2) ** 2 + (yy3 - H / 2) ** 2)
vig = np.clip((d - 300 * SS) / (520 * SS), 0, 1) * 0.55
img[:] *= (1 - vig[..., None])
xs = np.arange(W)[None, :] / W
scrim = np.where(xs < 0.4, 0.6 - (0.6 - 0.18) * xs / 0.4, np.where(xs < 0.6, 0.18 * (1 - (xs - 0.4) / 0.2), 0))
img[:] = img * (1 - scrim[..., None]) + np.array([2, 3, 7], float) / 255 * scrim[..., None]

out = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), "RGB").convert("RGBA")

# The mark: a rounded square with the logo's two arches, then the name.
ms = 136 * SS
mx, my = 92 * SS, (720 * SS - ms) // 2
glass = Image.new("RGBA", out.size, (0, 0, 0, 0))
ImageDraw.Draw(glass).rounded_rectangle([mx, my, mx + ms, my + ms], radius=int(ms * 0.31), fill=(255, 255, 255, 24), outline=(255, 255, 255, 38), width=3 * SS)
out = Image.alpha_composite(out, glass)
draw = ImageDraw.Draw(out)
k = ms / 24 * 0.62
ox, oy = mx + (ms - 24 * k) / 2, my + (ms - 24 * k) / 2


def logo_arch(x0, x1, top, bottom, r):
    """One arch of the logo as a single round brush stroke: up a wall, over the half circle, down."""
    w = 2.1 * k
    cx, cy = (x0 + x1) / 2, top + r
    path = [(x0, bottom), (x0, cy)]
    path += [(cx - r * math.cos(math.pi * i / 96), cy - r * math.sin(math.pi * i / 96)) for i in range(1, 96)]
    path += [(x1, cy), (x1, bottom)]
    # Dabs every quarter of a pixel along the path make a stroke with round caps and joins.
    for (xa, ya), (xb, yb) in zip(path, path[1:]):
        steps = max(1, int(math.hypot(xb - xa, yb - ya) * k * 4))
        for i in range(steps + 1):
            x = ox + (xa + (xb - xa) * i / steps) * k
            y = oy + (ya + (yb - ya) * i / steps) * k
            draw.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill="white")


logo_arch(4, 20, 3, 20, 8)        # M4 20v-9a8 8 0 0 1 16 0v9
logo_arch(8.5, 15.5, 8.5, 20, 3.5)  # M8.5 20v-8a3.5 3.5 0 0 1 7 0v8

# The name, as large as fits before the tunnel's light.
tx = mx + ms + 40 * SS
size = 132
while True:
    font = ImageFont.truetype(FONT, size * SS)
    font.set_variation_by_name("Bold")
    box = draw.textbbox((0, 0), NAME, font=font)
    if tx + box[2] <= 800 * SS or size <= 48:
        break
    size -= 4
ty = (720 * SS - (box[3] - box[1])) // 2 - box[1]
draw.text((tx, ty), NAME, font=font, fill="white")

out = out.convert("RGB")
for density, size in (("xhdpi", (320, 180)), ("xxxhdpi", (640, 360))):
    out.resize(size, Image.LANCZOS).save(RES / f"drawable-{density}" / "banner.png")
print(f"Banner for {NAME} written to drawable-xhdpi and drawable-xxxhdpi")
