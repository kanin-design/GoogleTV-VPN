"""Draws the placeholder Android TV banner and launcher icon: "VPN" on a teal gradient.

As TV cards such as Plex's do it: a dark card and one heavy wordmark. The card fades from deep teal
at the top left to near-black at the bottom right. A placeholder until the app has a real logo.
The other looks (a flat card, a spotlight, a lit line, the Connect screen's network) stay for
comparing with --out.

Writes the banner into app/src/main/res/drawable-xhdpi and drawable-xxxhdpi, and the launcher
icon's layers into drawable-xxxhdpi. Drawn at 4x and scaled down.

usage: python3 tools/banner.py FONT [--look flat|spotlight|teal|line|web] [--out DIR]
FONT is a heavy, wide face, such as Archivo Expanded Black (SIL Open Font License).
--out writes the banner alone to DIR/banner-LOOK.png, to compare looks, and leaves the app alone.
"""
import math
import pathlib
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = sys.argv[1]
def arg(name, default):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default
LOOK = arg("--look", "teal")
OUT = arg("--out", None)
ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
MINT = np.array([75, 227, 161], float) / 255
CORE = MINT + (1 - MINT) * 0.5


def field(w, h):
    """The card: deep teal at the top left to near-black, with mint light low on the right."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    t = np.clip((xx / w * 0.55 + yy / h * 0.45), 0, 1)[..., None]
    img = np.array([15, 43, 42], float) / 255 * (1 - t) + np.array([3, 7, 10], float) / 255 * t
    glow = np.clip(1 - np.hypot(xx - w * 0.78, yy - h * 0.62) / (w * 0.6), 0, 1) ** 2
    return img + glow[..., None] * MINT * 0.16


def add(img, layer, color, alpha):
    img += (np.asarray(layer, float) / 255)[..., None] * color * alpha


def web(img, w, h, seed):
    """Nodes and links like the Connect screen's, and the lit route across them."""
    rnd = random.Random(seed)
    nodes = []
    while len(nodes) < 46:
        p = (rnd.uniform(-0.05, 1.05) * w, rnd.uniform(-0.05, 1.05) * h)
        if all(math.dist(p, q) > w * 0.085 for q in nodes):
            nodes.append(p)
    reach = w * 0.2
    links = Image.new("L", (w, h))
    d = ImageDraw.Draw(links)
    for i, a in enumerate(nodes):
        for b in nodes[i + 1:]:
            dist = math.dist(a, b)
            if dist < reach:
                d.line([a, b], fill=int(255 * (1 - dist / reach)), width=max(1, w // 640))
    add(img, links, MINT, 0.22)
    dots = Image.new("L", (w, h))
    d = ImageDraw.Draw(dots)
    r = w / 400
    for x, y in nodes:
        d.ellipse([x - r, y - r, x + r, y + r], fill=255)
    add(img, dots, CORE, 0.55)
    # The route: in at the left, up through the VPN at the top, out at the right.
    route = [(0.06, 0.74), (0.2, 0.66), (0.33, 0.47), (0.47, 0.3), (0.5, 0.22), (0.62, 0.33), (0.78, 0.44), (0.9, 0.62), (0.98, 0.7)]
    pts = [(x * w, y * h) for x, y in route]
    for width, blur, color, alpha in ((w / 70, w / 90, MINT, 0.22), (w / 260, 0, CORE, 0.7)):
        layer = Image.new("L", (w, h))
        ImageDraw.Draw(layer).line(pts, fill=255, width=max(1, int(width)), joint="curve")
        if blur:
            layer = layer.filter(ImageFilter.GaussianBlur(blur))
        add(img, layer, color, alpha)
    for x, y in pts[2::3]:
        layer = Image.new("L", (w, h))
        ImageDraw.Draw(layer).ellipse([x - w / 70, y - w / 70, x + w / 70, y + w / 70], fill=255)
        add(img, layer.filter(ImageFilter.GaussianBlur(w / 110)), MINT, 0.5)


def sheen(img, w, h):
    """A soft light across the top left of the card, as on a glossy TV card."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    s = np.clip(1 - (xx / w + yy / h) / 0.9, 0, 1) ** 2 * 0.1
    img += s[..., None]


def wordmark(base, w, h, size):
    """'VPN', white fading to mint at the foot, over a soft shadow so it reads on any card."""
    font = ImageFont.truetype(FONT, size)
    text = "VPN"
    box = ImageDraw.Draw(base).textbbox((0, 0), text, font=font)
    x = (w - (box[2] - box[0])) / 2 - box[0]
    y = (h - (box[3] - box[1])) / 2 - box[1]
    mask = Image.new("L", (w, h))
    ImageDraw.Draw(mask).text((x, y), text, font=font, fill=255)
    shadow = mask.filter(ImageFilter.GaussianBlur(size / 14))
    base = Image.composite(Image.new("RGB", (w, h), (2, 5, 7)), base, shadow.point(lambda v: int(v * 0.75)))
    yy = np.linspace(0, 1, h)[:, None, None]
    top, foot = np.array([255, 255, 255], float), np.array([168, 244, 212], float)
    t = np.clip((yy - (y + box[1]) / h) / ((box[3] - box[1]) / h), 0, 1)
    fill = Image.fromarray((top * (1 - t) + foot * t).repeat(w, axis=1).astype(np.uint8), "RGB")
    return Image.composite(fill, base, mask)


def plain(w, h, top, bottom):
    """A near-flat card: [top] fading to [bottom] down its height."""
    t = np.linspace(0, 1, h)[:, None, None]
    return (np.array(top, float) * (1 - t) + np.array(bottom, float) * t).repeat(w, axis=1) / 255


def card(w, h, seed=7):
    if LOOK == "flat":
        img = plain(w, h, (12, 16, 20), (5, 7, 10))
    elif LOOK == "spotlight":
        img = plain(w, h, (8, 11, 14), (4, 6, 8))
        yy, xx = np.mgrid[0:h, 0:w].astype(float)
        glow = np.clip(1 - np.hypot((xx - w / 2) / (w * 0.42), (yy - h * 0.52) / (h * 0.5)), 0, 1) ** 2
        img += glow[..., None] * MINT * 0.22
    elif LOOK == "teal":
        yy, xx = np.mgrid[0:h, 0:w].astype(float)
        t = np.clip(xx / w * 0.6 + yy / h * 0.4, 0, 1)[..., None]
        img = np.array([18, 58, 54], float) / 255 * (1 - t) + np.array([4, 9, 12], float) / 255 * t
    elif LOOK == "line":
        img = plain(w, h, (10, 13, 17), (5, 7, 10))
        pts = [(0.0, 0.78), (0.22, 0.78), (0.3, 0.7), (0.7, 0.7), (0.78, 0.78), (1.0, 0.78)]
        pts = [(x * w, y * h) for x, y in pts]
        for width, blur, color, alpha in ((w / 90, w / 120, MINT, 0.3), (max(2, w / 400), 0, CORE, 0.85)):
            layer = Image.new("L", (w, h))
            ImageDraw.Draw(layer).line(pts, fill=255, width=max(1, int(width)), joint="curve")
            if blur:
                layer = layer.filter(ImageFilter.GaussianBlur(blur))
            add(img, layer, color, alpha)
    else:
        img = field(w, h)
        web(img, w, h, seed)
        sheen(img, w, h)
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), "RGB")


# The banner: 320 x 180 at xhdpi, 640 x 360 at xxxhdpi.
W, H = 1280, 720
banner = wordmark(card(W, H), W, H, 330)
if OUT:
    banner.resize((640, 360), Image.LANCZOS).save(pathlib.Path(OUT) / f"banner-{LOOK}.png")
    sys.exit(0)
for folder, size in (("drawable-xhdpi", (320, 180)), ("drawable-xxxhdpi", (640, 360))):
    banner.resize(size, Image.LANCZOS).save(RES / folder / "banner.png")

# The launcher icon: a 108 dp adaptive icon, 432 px at xxxhdpi; the word inside its 66 dp safe zone.
S = 1728
background = card(S, S, seed=11)
background.resize((432, 432), Image.LANCZOS).save(RES / "drawable-xxxhdpi/ic_launcher_bg.png")
font = ImageFont.truetype(FONT, 400)
box = ImageDraw.Draw(background).textbbox((0, 0), "VPN", font=font)
size = int(400 * (S * 0.5) / (box[2] - box[0]))
mask = Image.new("L", (S, S))
font = ImageFont.truetype(FONT, size)
box = ImageDraw.Draw(mask).textbbox((0, 0), "VPN", font=font)
ImageDraw.Draw(mask).text(((S - (box[2] - box[0])) / 2 - box[0], (S - (box[3] - box[1])) / 2 - box[1]), "VPN", font=font, fill=255)
fg = Image.new("RGBA", (S, S), (255, 255, 255, 0))
fg.putalpha(mask)
fg.resize((432, 432), Image.LANCZOS).save(RES / "drawable-xxxhdpi/ic_launcher_fg.png")
print("banner and launcher icon written")
