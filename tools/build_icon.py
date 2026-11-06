"""Abalone Push icon (design-package appIcon): deep-sea teal #1F4E5F disc, a light-wood #E2C08D hexagonal board outline,
three pearl #F2EEE6 marbles in a line pushing an ink #141518 marble that is going over the lower-right rim, with a
small amber #D99A2B arc. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
TEAL = (0x1F, 0x4E, 0x5F)
WOOD = (0xE2, 0xC0, 0x8D)
PEARL = (0xF2, 0xEE, 0xE6)
INK = (0x14, 0x15, 0x18)
AMBER = (0xD9, 0x9A, 0x2B)
SHADE = (0xC9, 0xC2, 0xB4)


def circle(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def hexagon(cx, cy, r):
    return [(cx + r * math.cos(math.radians(60 * i)), cy + r * math.sin(math.radians(60 * i))) for i in range(6)]


C = (54.0, 54.0)
HEX_OUT, HEX_IN = hexagon(C[0], C[1], 34.0), hexagon(C[0], C[1], 30.0)
# three pearl marbles on a line toward the lower right (the 60° direction), the ink one past the rim
D = (math.cos(math.radians(30)), math.sin(math.radians(30)))
MR = 7.2
PEARLS = [circle(C[0] - 14 + D[0] * 15 * i, C[1] - 4 + D[1] * 15 * i, MR) for i in range(3)]
PEARL_SHADE = [circle(C[0] - 14 + D[0] * 15 * i + 1.6, C[1] - 4 + D[1] * 15 * i + 1.6, MR * 0.72) for i in range(3)]
INK_C = (C[0] - 14 + D[0] * 47, C[1] - 4 + D[1] * 47 + 3.0)
INK_M = circle(INK_C[0], INK_C[1], MR)
INK_HI = circle(INK_C[0] - 2.4, INK_C[1] - 2.4, 1.8)


def arc(cx, cy, r, a0, a1, w, n=24):
    outer = [(cx + (r + w / 2) * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + (r + w / 2) * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]
    inner = [(cx + (r - w / 2) * math.cos(math.radians(a1 - (a1 - a0) * i / n)), cy + (r - w / 2) * math.sin(math.radians(a1 - (a1 - a0) * i / n))) for i in range(n + 1)]
    return outer + inner


ARC = arc(INK_C[0] - 4, INK_C[1] - 6, 11.0, -100, -10, 2.4)


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=TEAL + (255,)))


def layer_board():
    def d(dr, k):
        dr.polygon(sc(HEX_OUT, k), fill=WOOD + (255,))
        dr.polygon(sc(HEX_IN, k), fill=(0, 0, 0, 0))
    return big(d)


def layer_marbles():
    def d(dr, k):
        for p, sh in zip(PEARLS, PEARL_SHADE):
            dr.polygon(sc(p, k), fill=PEARL + (255,))
        dr.polygon(sc(ARC, k), fill=AMBER + (255,))
        dr.polygon(sc(INK_M, k), fill=INK + (255,))
        dr.polygon(sc(INK_HI, k), fill=(0x5A, 0x6A, 0x70, 255))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    ring = path(HEX_OUT) + path(list(reversed(HEX_IN)))
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Abalone Push launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:fillType="evenOdd" android:pathData="%s" />' % (hexc(WOOD), ring),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(PEARL), "".join(path(p) for p in PEARLS)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(AMBER), path(ARC)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(INK), path(INK_M)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_board(), layer_marbles()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
