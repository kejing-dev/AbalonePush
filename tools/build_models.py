"""Builds the Abalone board, marbles and direction pads as GLB, in Python + Pillow (no Blender on this machine).

  python3 tools/build_models.py

Writes app/src/main/assets/models/: board.glb, marble_black.glb, marble_white.glb, pad_{blue,amber1,amber2,amber1red,
amber2red,grey}.glb. Numbers match domain/Layout.kt and domain/Hex.kt (design gameSpec.board / theme.boardContrast):
  board   hexagon circumradius 0.30 m (corners left / right, a flat edge toward the user), 3 cm thick, origin on the field top
          centre; the field (deep-sea teal, matt) up to circumradius 0.235 with 61 spherical dimples (dia 3.8 cm, 6 mm deep)
          each ringed by a 4 mm light-wood lip (#E2C08D: black marbles read against it); then a 4.5 cm gutter trough 1.2 cm
          deep with 36 darker slot marks, a light-wood outer rim and walnut sides
  marble  sphere dia 3.4 cm, origin at its centre; glossy (roughness 0.08) ink black / pearl white with a faint inner swirl
  pad     arrow 3.2 x 2.8 x 0.4 cm pointing +X, origin at its centre; blue = move, amber + 1 / 2 white dots = push,
          a red rim under the amber ones = the push drops a marble, grey = can't (emissive so they read as signals)
All materials OPAQUE (profile D24 / B12).
"""
import io
import json
import math
import os
import random
import struct

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")

TEAL = (0x1F, 0x4E, 0x5F)
TEAL_DEEP = (0x16, 0x38, 0x44)
LIGHT_WOOD = (0xE2, 0xC0, 0x8D)
WALNUT = (0x6B, 0x4A, 0x2B)
WALNUT_DARK = (0x4E, 0x34, 0x1D)
INK = (0x14, 0x15, 0x18)
PEARL = (0xF2, 0xEE, 0xE6)
BLUE = (0x6F, 0xA8, 0xE6)   # lifted from the design #4C8DD2: 2.6:1 -> 3.6:1 on the teal field
AMBER = (0xD9, 0x9A, 0x2B)
GREY = (0x9A, 0x9C, 0xA0)
RED = (0xC8, 0x42, 0x3F)
WHITE = (0xFA, 0xF8, 0xF2)

P = 0.052                     # cell pitch
ROW = P * math.sqrt(3) / 2    # row pitch
R_BOARD, T = 0.30, 0.03
R_FIELD, R_GIN, R_GOUT = 0.235, 0.235, 0.28
DIMPLE_A, DIMPLE_H = 0.019, 0.006
LIP_W = 0.004
GUTTER_D = 0.012
MARBLE_R = 0.017


def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "abalone-push tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def add(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]
        self.nor += [tuple(n) for n in norms]
        self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def tri(self, mat, a, b, c, n=None, uv=(0.5, 0.5)):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        self.add(mat, [a, b, c], [n, n, n], [uv, uv, uv], [0, 1, 2])

    def face(self, mat, verts, n, uvs=None):
        """A convex polygon (fan) wound counter-clockwise around its normal [n] (front face = the side n points to)."""
        v = [tuple(p) for p in verts]
        a, b, c = v[0], v[1], v[2]
        u = [b[k] - a[k] for k in range(3)]; w = [c[k] - a[k] for k in range(3)]
        cr = (u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
        uvs = list(uvs) if uvs else [(0.5, 0.5)] * len(v)
        if sum(cr[k] * n[k] for k in range(3)) < 0:
            v = v[::-1]; uvs = uvs[::-1]
        idx = []
        for i in range(1, len(v) - 1):
            idx += [0, i, i + 1]
        self.add(mat, v, [tuple(n)] * len(v), uvs, idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-20s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))
        return tris


def mat(name, color=None, rough=0.6, tex=None, metal=0.0, emissive=None):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    m = {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}
    if emissive is not None:
        m["emissiveFactor"] = [srgb_lin(c) * emissive for c in (color or (255, 255, 255))]
    return m


# ---------------------------------------------------------------- board

def cells():
    for r in range(-4, 5):
        for q in range(-4, 5):
            if abs(q + r) <= 4:
                yield P * (q + r / 2.0), ROW * r


CELLS = list(cells())
DIMPLE_RS = (DIMPLE_A ** 2 + DIMPLE_H ** 2) / (2 * DIMPLE_H)   # sphere radius of a dimple


def nearest_cell(x, z):
    best, bd = None, 9.0
    # the lattice is regular: test the few nearest rows only
    r0 = round(z / ROW)
    for r in (r0 - 1, r0, r0 + 1):
        if abs(r) > 4:
            continue
        q0 = round(x / P - r / 2.0)
        for q in (q0 - 1, q0, q0 + 1):
            if abs(q) > 4 or abs(q + r) > 4:
                continue
            cx, cz = P * (q + r / 2.0), ROW * r
            d = (x - cx) ** 2 + (z - cz) ** 2
            if d < bd:
                best, bd = (cx, cz), d
    return best, math.sqrt(bd)


def field_height(x, z):
    c, d = nearest_cell(x, z)
    if c is None or d >= DIMPLE_A:
        return 0.0
    return (DIMPLE_RS - DIMPLE_H) - math.sqrt(DIMPLE_RS ** 2 - d * d) + 0.0  # 0 at the rim, -DIMPLE_H at the centre


def field_normal(x, z):
    e = 0.0004
    hx = (field_height(x + e, z) - field_height(x - e, z)) / (2 * e)
    hz = (field_height(x, z + e) - field_height(x, z - e)) / (2 * e)
    n = (-hx, 1.0, -hz)
    l = math.sqrt(sum(v * v for v in n))
    return tuple(v / l for v in n)


def hex_corner(k, rad):
    a = math.radians(-60.0 * k)
    return rad * math.cos(a), rad * math.sin(a)


def uv_of(x, z):
    return (x + R_BOARD) / (2 * R_BOARD), (z + R_BOARD * math.sqrt(3) / 2) / (R_BOARD * math.sqrt(3))


def field_texture(width=2048):
    h = int(width * math.sqrt(3) / 2)
    ppm = width / (2 * R_BOARD)
    im = Image.new("RGB", (width, h), TEAL)
    px = im.load()
    rnd = random.Random(5)
    # a soft felt / lacquer mottle so the teal is not a flat fill
    noise = Image.effect_noise((width // 8, h // 8), 12).resize((width, h), Image.BICUBIC)
    nz = noise.load()
    dr = ImageDraw.Draw(im)

    def to_px(x, z):
        u, v = uv_of(x, z)
        return u * width, v * h

    from PIL import ImageChops
    tint = Image.new("RGB", (width, h), TEAL)
    im = ImageChops.add(tint, noise.convert("RGB").point(lambda v: max(0, v - 128) // 6))
    dr = ImageDraw.Draw(im)
    for cx, cz in CELLS:
        x, y = to_px(cx, cz)
        rl = (DIMPLE_A + LIP_W) * ppm
        dr.ellipse((x - rl, y - rl, x + rl, y + rl), fill=LIGHT_WOOD)
        # the dimple floor: teal at the rim, deeper teal in the middle
        steps = 14
        for i in range(steps):
            t = i / (steps - 1)
            rr = DIMPLE_A * ppm * (1.0 - t * 0.92)
            col = tuple(int(TEAL[k] + (TEAL_DEEP[k] - TEAL[k]) * t) for k in range(3))
            dr.ellipse((x - rr, y - rr, x + rr, y + rr), fill=col)
    # the outer frame region of the texture (unused by the field mesh) stays teal
    _ = rnd, px, nz
    return im.filter(ImageFilter.SMOOTH)


def wood_texture(base, size=1024, seed=1, light=None):
    rnd = random.Random(seed)
    im = Image.new("RGB", (size, size), base)
    dr = ImageDraw.Draw(im)
    # grain: long wavy lines along u
    for i in range(160):
        y0 = rnd.uniform(0, size)
        amp = rnd.uniform(2, 9)
        freq = rnd.uniform(1, 4)
        shade = rnd.randint(-22, 14)
        col = tuple(max(0, min(255, c + shade)) for c in base)
        pts = [(x, y0 + amp * math.sin(2 * math.pi * freq * x / size + i)) for x in range(0, size + 8, 8)]
        dr.line(pts, fill=col, width=rnd.randint(1, 3))
    im = im.filter(ImageFilter.GaussianBlur(1.2))
    return im


def build_board():
    m = Mesh()
    # --- field top: 6 sectors (centre, corner k, corner k+1), each a barycentric grid of N rows
    N = 60
    for k in range(6):
        a = (0.0, 0.0)
        b = hex_corner(k, R_FIELD)
        c = hex_corner(k + 1, R_FIELD)
        verts, norms, uvs, idx = [], [], [], []
        index = {}
        for i in range(N + 1):
            for j in range(i + 1):
                # point = a + (b - a) * (i - j) / N + (c - a) * j / N
                u, v = (i - j) / N, j / N
                x = a[0] + (b[0] - a[0]) * u + (c[0] - a[0]) * v
                z = a[1] + (b[1] - a[1]) * u + (c[1] - a[1]) * v
                index[(i, j)] = len(verts)
                verts.append((x, field_height(x, z), z))
                norms.append(field_normal(x, z))
                uvs.append(uv_of(x, z))
        for i in range(N):
            for j in range(i + 1):
                p0, p1, p2 = index[(i, j)], index[(i + 1, j)], index[(i + 1, j + 1)]
                idx += [p0, p1, p2]          # counter-clockwise seen from +Y
                if j < i:
                    p3 = index[(i, j + 1)]
                    idx += [p0, p2, p3]
        m.add(0, verts, norms, uvs, idx)

    # --- frame: concentric hexagon rings (circumradius, height, material) from the field edge outward
    profile = [
        (R_GIN, 0.0, 1), (R_GIN + 0.003, -0.003, 1), (R_GIN + 0.008, -0.008, 2), (R_GIN + 0.015, -0.011, 2),
        ((R_GIN + R_GOUT) / 2, -GUTTER_D, 2), (R_GOUT - 0.015, -0.011, 2), (R_GOUT - 0.008, -0.008, 2),
        (R_GOUT - 0.003, -0.002, 1), (R_GOUT, 0.003, 1), (R_GOUT + 0.003, 0.005, 3), (R_BOARD - 0.003, 0.005, 3),
        (R_BOARD, 0.002, 1), (R_BOARD, -T, 1),
    ]
    cos30 = math.sqrt(3) / 2
    for k in range(6):
        # outward unit vector of side k (apothem direction) and the side's tangent
        a_mid = math.radians(-60.0 * k - 30.0)
        ox, oz = math.cos(a_mid), math.sin(a_mid)
        for s in range(len(profile) - 1):
            r0, y0, _ = profile[s]
            r1, y1, mi = profile[s + 1]
            # normal of the profile segment in (radial, y), radial measured along the apothem
            dr_ = (r1 - r0) * cos30
            dy = y1 - y0
            l = math.hypot(dr_, dy) or 1.0
            nr, ny = -dy / l, dr_ / l      # rotate (dr, dy) by +90°: points up / outward
            if r1 == r0 and y1 < y0:
                nr, ny = 1.0, 0.0
            n = (nr * ox, ny, nr * oz)
            p00 = hex_corner(k, r0); p01 = hex_corner(k + 1, r0)
            p10 = hex_corner(k, r1); p11 = hex_corner(k + 1, r1)
            v = [(p00[0], y0, p00[1]), (p01[0], y0, p01[1]), (p11[0], y1, p11[1]), (p10[0], y1, p10[1])]
            uvv = [(k / 6.0, s / 12.0), ((k + 1) / 6.0, s / 12.0), ((k + 1) / 6.0, (s + 1) / 12.0), (k / 6.0, (s + 1) / 12.0)]
            m.face(mi, v, n, uvv)
    # gutter slot marks: 36 small dark discs on the trough floor (6 per edge)
    gm = (R_GIN + R_GOUT) / 2
    for i in range(36):
        e, t = i // 6, (i % 6 + 0.5) / 6
        x0, z0 = hex_corner(e, gm); x1, z1 = hex_corner(e + 1, gm)
        sx, sz = x0 + (x1 - x0) * t, z0 + (z1 - z0) * t
        seg = 16
        for j in range(seg):
            a0, a1 = 2 * math.pi * j / seg, 2 * math.pi * (j + 1) / seg
            rr = 0.010
            m.face(4, [(sx, -GUTTER_D + 0.0004, sz), (sx + rr * math.cos(a1), -GUTTER_D + 0.0004, sz + rr * math.sin(a1)),
                       (sx + rr * math.cos(a0), -GUTTER_D + 0.0004, sz + rr * math.sin(a0))], (0, 1, 0))
    # bottom
    for k in range(6):
        p0 = hex_corner(k, R_BOARD); p1 = hex_corner(k + 1, R_BOARD)
        m.face(1, [(0, -T, 0), (p0[0], -T, p0[1]), (p1[0], -T, p1[1])], (0, -1, 0))
    tris = m.write("board", [
        mat("Field", tex=0, rough=0.7),
        mat("Walnut", tex=1, rough=0.55),
        mat("Gutter", tex=2, rough=0.7),
        mat("RimLight", tex=3, rough=0.5),
        mat("Slot", color=WALNUT_DARK, rough=0.8),
    ], [field_texture(), wood_texture(WALNUT, seed=2), wood_texture((0x5A, 0x3D, 0x22), seed=3), wood_texture(LIGHT_WOOD, seed=4)])
    assert tris <= 30000, tris


# ---------------------------------------------------------------- marbles

def swirl_texture(base, swirl, seed):
    """Equirectangular 512 x 256: the base glass colour with one soft curved band (a marble's inner swirl)."""
    w, h = 512, 256
    im = Image.new("RGB", (w, h), base)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    for band in range(2):
        ph = rnd.uniform(0, 2 * math.pi)
        amp = rnd.uniform(30, 60)
        y0 = h * (0.35 + 0.3 * band)
        pts = [(x, y0 + amp * math.sin(2 * math.pi * x / w + ph)) for x in range(0, w + 4, 4)]
        dr.line(pts, fill=swirl, width=rnd.randint(10, 18))
    return im.filter(ImageFilter.GaussianBlur(7))


def build_marble(name, base, swirl, seed):
    m = Mesh()
    SEG, RINGS = 32, 16
    verts, norms, uvs, idx = [], [], [], []
    for i in range(RINGS + 1):
        th = math.pi * i / RINGS
        for j in range(SEG + 1):
            ph = 2 * math.pi * j / SEG
            n = (math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph))
            verts.append(tuple(MARBLE_R * c for c in n)); norms.append(n); uvs.append((j / SEG, i / RINGS))
    for i in range(RINGS):
        for j in range(SEG):
            a = i * (SEG + 1) + j; b = a + SEG + 1
            idx += [a, a + 1, b, a + 1, b + 1, b]
    m.add(0, verts, norms, uvs, idx)
    m.write(name, [mat(name, tex=0, rough=0.08)], [swirl_texture(base, swirl, seed)])


# ---------------------------------------------------------------- pads

def arrow_outline(scale=1.0):
    """Arrow pointing +X in (x, z): head from x = 0.002 to 0.016 (half-width 0.014), shaft back to -0.016 (half-width 0.0065)."""
    pts = [(0.016, 0.0), (0.002, -0.014), (0.002, -0.0065), (-0.016, -0.0065), (-0.016, 0.0065), (0.002, 0.0065), (0.002, 0.014)]
    return [(x * scale, z * scale) for x, z in pts]


def extrude(m, mi, outline, y0, y1):
    """Top / bottom caps (head triangle + shaft quad) and the side walls, each wound to face outward."""
    o = outline
    top = lambda p: (p[0], y1, p[1])
    bot = lambda p: (p[0], y0, p[1])
    for poly in ((0, 6, 1), (2, 3, 4, 5)):
        m.face(mi, [top(o[i]) for i in poly], (0, 1, 0))
        m.face(mi, [bot(o[i]) for i in poly], (0, -1, 0))
    n = len(o)
    cx = sum(p[0] for p in o) / n; cz = sum(p[1] for p in o) / n
    for i in range(n):
        a, b = o[i], o[(i + 1) % n]
        dx, dz = b[0] - a[0], b[1] - a[1]
        l = math.hypot(dx, dz) or 1.0
        nn = (dz / l, 0.0, -dx / l)
        mx, mz = (a[0] + b[0]) / 2 - cx, (a[1] + b[1]) / 2 - cz
        if nn[0] * mx + nn[2] * mz < 0:
            nn = (-nn[0], 0.0, -nn[2])
        m.face(mi, [bot(a), bot(b), top(b), top(a)], nn)


def build_pad(name, colour, dots=0, red=False):
    m = Mesh()
    H = 0.004
    extrude(m, 0, arrow_outline(), -H / 2, H / 2)
    if red:
        extrude(m, 1, arrow_outline(1.22), -H / 2 - 0.0008, H / 2 - 0.0012)
    for i in range(dots):
        cx = -0.007 if dots == 1 else (-0.011 + 0.008 * i)
        seg = 16
        for j in range(seg):
            a0, a1 = 2 * math.pi * j / seg, 2 * math.pi * (j + 1) / seg
            rr = 0.0032
            y = H / 2 + 0.0005
            m.face(2, [(cx, y, 0.0), (cx + rr * math.cos(a1), y, rr * math.sin(a1)), (cx + rr * math.cos(a0), y, rr * math.sin(a0))], (0, 1, 0))
    mats = [mat("PadFace", color=colour, rough=0.45, emissive=0.55), mat("PadRed", color=RED, rough=0.45, emissive=0.6), mat("PadDot", color=WHITE, rough=0.4, emissive=0.5)]
    m.write(name, mats)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.endswith(".glb"):
            os.remove(os.path.join(OUT, f))
    build_board()
    build_marble("marble_black", INK, (0x1C, 0x3A, 0x44), 11)
    build_marble("marble_white", PEARL, (0xE6, 0xD3, 0xAE), 12)
    build_pad("pad_blue", BLUE)
    build_pad("pad_amber1", AMBER, dots=1)
    build_pad("pad_amber2", AMBER, dots=2)
    build_pad("pad_amber1red", AMBER, dots=1, red=True)
    build_pad("pad_amber2red", AMBER, dots=2, red=True)
    build_pad("pad_grey", GREY)
