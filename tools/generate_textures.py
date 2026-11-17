#!/usr/bin/env python3
"""
Erzeugt alle Grafiken der Schießbuden-Animation (Comic-Stil mit dicken Konturen).

Aufruf (aus dem Projektordner):  python3 tools/generate_textures.py
Benötigt: Pillow  (pip install pillow)

Das Szenen-Layout ist in "virtuellen Einheiten" (640 x 360) definiert. Dieselben
Zahlen stehen in den Animations-Klassen (ShootingRangeAnimation, SlotMachineAnimation,
RouletteAnimation) - wer hier etwas verschiebt, muss es dort ebenfalls anpassen.
"""
import math
import os
import random
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "schiessbude", "textures")
GUI = os.path.join(OUT, "gui")
PRIZE = os.path.join(OUT, "prize")

PX = 3      # Ausgabe-Pixel pro virtueller Einheit
SS = 2      # Supersampling (Kantenglättung)

OUTLINE = (35, 18, 48, 255)
GOLD = (247, 190, 52, 255)
GOLD_DARK = (196, 131, 28, 255)
GOLD_LIGHT = (255, 232, 140, 255)


def save(img, folder, name, blur=True):
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name)
    img.save(path, optimize=True)
    if blur:
        with open(path + ".mcmeta", "w", encoding="utf-8") as f:
            f.write('{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n')
    print("geschrieben:", os.path.relpath(path, ROOT), img.size)


class Canvas:
    """Zeichenfläche in virtuellen Einheiten mit Supersampling."""

    def __init__(self, w, h, px=PX, bg=(0, 0, 0, 0)):
        self.w, self.h, self.px = w, h, px
        self.k = px * SS
        self.img = Image.new("RGBA", (round(w * self.k), round(h * self.k)), bg)
        self.d = ImageDraw.Draw(self.img)

    def s(self, v):
        return v * self.k

    def pts(self, pts):
        return [(x * self.k, y * self.k) for x, y in pts]

    def box(self, x0, y0, x1, y1):
        return [x0 * self.k, y0 * self.k, x1 * self.k, y1 * self.k]

    def rect(self, x0, y0, x1, y1, fill, outline=None, width=0, radius=0):
        w = round(width * self.k) if outline else 0
        if radius:
            self.d.rounded_rectangle(self.box(x0, y0, x1, y1), radius=radius * self.k, fill=fill, outline=outline, width=w)
        else:
            self.d.rectangle(self.box(x0, y0, x1, y1), fill=fill, outline=outline, width=w)

    def ellipse(self, cx, cy, rx, ry, fill, outline=None, width=0):
        w = round(width * self.k) if outline else 0
        self.d.ellipse(self.box(cx - rx, cy - ry, cx + rx, cy + ry), fill=fill, outline=outline, width=w)

    def poly(self, pts, fill, outline=None, width=0):
        self.d.polygon(self.pts(pts), fill=fill)
        if outline:
            p = self.pts(pts)
            self.d.line(p + [p[0]], fill=outline, width=round(width * self.k), joint="curve")

    def line(self, pts, fill, width):
        self.d.line(self.pts(pts), fill=fill, width=max(1, round(width * self.k)), joint="curve")

    def done(self):
        return self.img.resize((round(self.w * self.px), round(self.h * self.px)), Image.LANCZOS)


def vgradient(w, h, stops):
    """Vertikaler Verlauf; stops = [(t, (r,g,b,a)), ...]"""
    col = Image.new("RGBA", (1, h))
    px = col.load()
    for y in range(h):
        t = y / max(1, h - 1)
        for i in range(len(stops) - 1):
            t0, c0 = stops[i]
            t1, c1 = stops[i + 1]
            if t0 <= t <= t1:
                f = (t - t0) / max(1e-6, t1 - t0)
                c = tuple(round(c0[j] + (c1[j] - c0[j]) * f) for j in range(4))
                break
        else:
            c = stops[-1][1]
        px[0, y] = c
    return col.resize((w, h), Image.NEAREST)


def layer(c, draw, blur=0, mask=None):
    """Zeichnet halbtransparente Dinge auf eine eigene Ebene und blendet sie weich über das Bild."""
    lay = Canvas.__new__(Canvas)
    lay.w, lay.h, lay.px, lay.k = c.w, c.h, c.px, c.k
    lay.img = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    lay.d = ImageDraw.Draw(lay.img)
    draw(lay)
    if blur:
        lay.img = lay.img.filter(ImageFilter.GaussianBlur(blur * c.k))
    if mask is not None:
        from PIL import ImageChops
        lay.img.putalpha(ImageChops.multiply(lay.img.split()[3], mask))
    c.img.alpha_composite(lay.img)
    c.d = ImageDraw.Draw(c.img)


def poly_mask(c, pts):
    m = Image.new("L", c.img.size, 0)
    ImageDraw.Draw(m).polygon(c.pts(pts), fill=255)
    return m


# ---------------------------------------------------------------------------
# Hintergrund: Jahrmarkt-Schießbude
# ---------------------------------------------------------------------------
# Podeste (x0, x1, top_y) - passend zu ShootingRangeAnimation.java
TIERS = [
    # hintere (höchste) Stufe
    [(196, 444, 118)],
    # mittlere Stufe
    [(76, 236, 165), (404, 564, 165)],
    # vordere Stufe
    [(34, 186, 215), (454, 606, 215)],
]
TOP_DEPTH = 12     # Tiefe der Oberseite
FRONT_H = [46, 44, 40]


def podium(c, x0, x1, top, front_h, tier):
    top_col = [(255, 176, 170, 255), (250, 160, 158, 255), (246, 146, 148, 255)][tier]
    front_col = [(232, 104, 122, 255), (226, 94, 116, 255), (218, 84, 110, 255)][tier]
    shade = (190, 64, 96, 255)
    # Vorderseite
    c.rect(x0, top + TOP_DEPTH, x1, top + TOP_DEPTH + front_h, front_col, OUTLINE, 2.2, radius=3)
    # Schattenstreifen unten
    c.rect(x0 + 3, top + TOP_DEPTH + front_h - 9, x1 - 3, top + TOP_DEPTH + front_h - 3, shade, radius=2)
    # Paneel-Linien
    n = max(2, int((x1 - x0) / 60))
    for i in range(1, n):
        x = x0 + (x1 - x0) * i / n
        c.line([(x, top + TOP_DEPTH + 6), (x, top + TOP_DEPTH + front_h - 12)], (200, 70, 100, 255), 1.6)
    # Oberseite (leicht trapezförmig = Perspektive)
    c.poly([(x0 + 6, top), (x1 - 6, top), (x1, top + TOP_DEPTH), (x0, top + TOP_DEPTH)], top_col, OUTLINE, 2.2)
    # Glanzkante
    c.line([(x0 + 10, top + 3), (x1 - 10, top + 3)], (255, 214, 204, 255), 1.6)


def slot(c, x, top):
    # Schlitz, in dem der Stiel der Zielscheibe steckt
    y = top + TOP_DEPTH / 2
    c.rect(x - 7, y - 2.2, x + 7, y + 2.2, (70, 30, 70, 255), radius=2)


def draw_pillars(c):
    # Seitensäulen
    for x0 in (0, 612):
        c.rect(x0, 36, x0 + 28, 296, (92, 46, 150, 255), OUTLINE, 2.4)
        c.rect(x0 + 10, 36, x0 + 18, 296, (120, 66, 186, 255))
        for y in range(56, 290, 26):
            c.ellipse(x0 + 14, y, 4, 4, (255, 230, 150, 255), OUTLINE, 1.4)


def draw_bunting(c):
    # Wimpelkette
    for (xa, xb) in [(28, 320), (320, 612)]:
        pts = []
        for i in range(21):
            t = i / 20
            x = xa + (xb - xa) * t
            y = 58 + math.sin(t * math.pi) * 10
            pts.append((x, y))
        c.line(pts, (60, 30, 70, 255), 1.2)
        cols = [(255, 214, 70, 255), (120, 210, 255, 255), (255, 110, 160, 255), (160, 255, 150, 255)]
        for i in range(1, 20, 2):
            x, y = pts[i]
            col = cols[(i // 2) % 4]
            c.poly([(x - 5, y), (x + 5, y), (x, y + 10)], col, OUTLINE, 1.0)


def draw_awning(c, W=640):
    k = c.k
    # Schatten unter der Markise
    shadow = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rectangle([0, 38 * k, W * k, 54 * k], fill=(40, 10, 60, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(4 * k))
    c.img.alpha_composite(shadow)
    c.d = ImageDraw.Draw(c.img)

    # Markise oben (lila/gold gestreift, gewellter Rand)
    c.rect(-4, -4, W + 4, 34, (126, 63, 193, 255))
    sw = 40
    for i in range(0, W // sw + 1):
        x0 = i * sw
        col = (126, 63, 193, 255) if i % 2 == 0 else GOLD
        c.rect(x0, -4, x0 + sw, 34, col)
        # Bogen
        c.d.pieslice(c.box(x0, 34 - sw / 2 + 2, x0 + sw, 34 + sw / 2 - 2), 0, 180, fill=col)
    # Konturen der Bögen
    for i in range(0, W // sw + 1):
        x0 = i * sw
        c.d.arc(c.box(x0, 34 - sw / 2 + 2, x0 + sw, 34 + sw / 2 - 2), 0, 180, fill=OUTLINE, width=round(2.2 * k))
    c.line([(0, 6), (W, 6)], (255, 255, 255, 60), 2)
    # Fassungen der Glühbirnen (Positionen auch in CasinoAnimation.java)
    for i in range(16):
        x = 20 + i * 40
        c.rect(x - 3, 47, x + 3, 51, (60, 40, 70, 255), radius=1)


def make_background():
    W, H = 640, 360
    c = Canvas(W, H)
    k = c.k
    # Himmel
    sky = vgradient(int(W * k), int(H * k), [
        (0.0, (46, 20, 92, 255)),
        (0.30, (110, 46, 150, 255)),
        (0.48, (212, 98, 170, 255)),
        (0.62, (238, 140, 180, 255)),
        (1.0, (238, 140, 180, 255)),
    ])
    c.img.paste(sky, (0, 0))
    c.d = ImageDraw.Draw(c.img)

    rnd = random.Random(7)
    # Sterne
    for _ in range(70):
        x, y = rnd.uniform(0, W), rnd.uniform(40, 120)
        r = rnd.uniform(0.4, 1.2)
        c.ellipse(x, y, r, r, (255, 240, 255, rnd.randint(90, 200)))

    # Wolken (weich)
    clouds = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    cd = ImageDraw.Draw(clouds)
    for (cx, cy, s) in [(90, 112, 1.0), (250, 100, 0.8), (430, 108, 1.1), (600, 96, 0.9)]:
        for dx, dy, r in [(-22, 4, 14), (0, -4, 18), (22, 3, 15), (40, 7, 10), (-38, 8, 9)]:
            x, y, rr = cx + dx * s, cy + dy * s, r * s
            cd.ellipse([(x - rr) * k, (y - rr) * k, (x + rr) * k, (y + rr * 0.8) * k], fill=(255, 190, 230, 70))
    clouds = clouds.filter(ImageFilter.GaussianBlur(3 * k))
    c.img.alpha_composite(clouds)
    c.d = ImageDraw.Draw(c.img)

    # Jahrmarkt-Silhouetten
    sil = (126, 52, 140, 255)
    sil2 = (150, 66, 150, 255)
    # Riesenrad rechts
    cx, cy, r = 560, 118, 62
    c.ellipse(cx, cy, r, r, None, sil, 4)
    c.ellipse(cx, cy, r - 10, r - 10, None, sil, 1.5)
    for i in range(12):
        a = i * math.pi / 6
        c.line([(cx, cy), (cx + math.cos(a) * r, cy + math.sin(a) * r)], sil, 1.6)
        gx, gy = cx + math.cos(a) * r, cy + math.sin(a) * r
        c.rect(gx - 5, gy, gx + 5, gy + 8, sil, radius=2)
    c.poly([(cx - 30, 190), (cx, cy), (cx + 30, 190)], None, sil, 4)
    c.ellipse(cx, cy, 6, 6, sil)
    # Zirkuszelt links
    c.poly([(30, 175), (70, 120), (110, 175)], sil2)
    c.rect(36, 160, 104, 190, sil2)
    c.line([(70, 120), (70, 106)], sil2, 2)
    c.poly([(70, 106), (84, 110), (70, 114)], sil2)
    # Achterbahn-Bogen Mitte
    pts = []
    for i in range(41):
        x = 150 + i * 6.5
        y = 150 - 26 * math.sin(i / 40 * math.pi) ** 2 - 10 * math.sin(i / 40 * math.pi * 3)
        pts.append((x, y))
    c.line(pts, sil2, 3)
    for x, y in pts[::4]:
        c.line([(x, y), (x, 175)], sil2, 1.4)

    # Boden
    c.rect(0, 150, W, 300, (214, 96, 128, 255))
    for i in range(9):
        y = 158 + i * 17
        c.line([(0, y), (W, y)], (196, 80, 116, 255), 1.4)
    rnd = random.Random(3)
    for i in range(9):
        y0 = 158 + i * 17
        off = rnd.uniform(0, 60)
        x = -off
        while x < W:
            c.line([(x, y0), (x, y0 + 17)], (196, 80, 116, 255), 1.2)
            x += rnd.uniform(48, 80)

    # Podeste (hinten -> vorne)
    for tier, blocks in enumerate(TIERS):
        for (x0, x1, top) in blocks:
            podium(c, x0, x1, top, FRONT_H[tier], tier)
    # Schlitze an den Zielpositionen
    for (x, top) in [(250, 118), (320, 118), (390, 118), (150, 165), (490, 165), (100, 215), (540, 215)]:
        slot(c, x, top)

    # Theke unten
    c.rect(-4, 292, W + 4, 364, (48, 26, 84, 255), OUTLINE, 2.4)
    c.rect(-4, 292, W + 4, 302, GOLD, OUTLINE, 2.2)
    c.line([(0, 295), (W, 295)], GOLD_LIGHT, 1.4)
    for i in range(0, W + 40, 40):
        c.poly([(i, 302), (i + 20, 302), (i - 10, 364), (i - 30, 364)], (60, 34, 102, 255))
    for x in range(20, W, 80):
        c.ellipse(x, 316, 3, 3, GOLD, OUTLINE, 1.2)

    draw_pillars(c)
    draw_bunting(c)
    draw_awning(c)

    img = c.done().convert("RGB").convert("RGBA")
    save(img, GUI, "background.png")


# ---------------------------------------------------------------------------
# Einzelne Sprites
# ---------------------------------------------------------------------------
def make_target_board():
    S = 54
    c = Canvas(S, S, px=3)
    m = S / 2
    rings = [(26, (255, 255, 255, 255)), (22, (230, 58, 74, 255)), (18.5, (255, 255, 255, 255))]
    c.ellipse(m, m, 26, 26, OUTLINE)
    for r, col in rings:
        c.ellipse(m, m, r - 1.6, r - 1.6, col)
    # Große innere Fläche für das Gewinn-Bild
    c.ellipse(m, m, 15, 15, (255, 246, 226, 255), (230, 58, 74, 255), 2.2)
    # Glanz
    c.d.arc(c.box(m - 22, m - 22, m + 22, m + 22), 200, 250, fill=(255, 255, 255, 170), width=round(2 * c.k))
    save(c.done(), GUI, "target_board.png")

    # Seltenheits-Ring (weiß, wird im Spiel eingefärbt)
    c = Canvas(S + 8, S + 8, px=3)
    mm = (S + 8) / 2
    c.ellipse(mm, mm, 30.5, 30.5, OUTLINE)
    c.ellipse(mm, mm, 28.8, 28.8, (255, 255, 255, 255))
    c.ellipse(mm, mm, 25.6, 25.6, (0, 0, 0, 0))
    save(c.done(), GUI, "rarity_ring.png")


def make_pistol():
    W, H = 64, 150
    c = Canvas(W, H, px=3)
    cx = W / 2
    steel = (64, 70, 104, 255)
    steel_l = (112, 122, 168, 255)
    # Lauf
    c.rect(cx - 9, 10, cx + 9, 82, steel, OUTLINE, 2.4, radius=4)
    c.rect(cx - 3, 16, cx + 1, 78, steel_l, radius=2)
    # Laufrippe / Visierschiene
    c.rect(cx - 2.5, 4, cx + 2.5, 30, (46, 50, 78, 255), OUTLINE, 1.6, radius=1.5)
    # Mündungsring (gold)
    c.rect(cx - 11, 6, cx + 11, 16, GOLD, OUTLINE, 2.2, radius=3)
    c.rect(cx - 8, 8, cx + 6, 10, GOLD_LIGHT, radius=1)
    # Korn
    c.poly([(cx - 3, 7), (cx + 3, 7), (cx + 2, 1), (cx - 2, 1)], (240, 70, 80, 255), OUTLINE, 1.4)
    # Ringe am Lauf
    for y in (40, 56):
        c.rect(cx - 10.5, y, cx + 10.5, y + 5, GOLD_DARK, OUTLINE, 1.6, radius=2)
    # Rahmen / Körper
    c.rect(cx - 25, 76, cx + 25, 120, GOLD, OUTLINE, 2.6, radius=9)
    c.rect(cx - 21, 79, cx + 21, 86, GOLD_LIGHT, radius=4)
    c.rect(cx - 25, 108, cx + 25, 120, GOLD_DARK, OUTLINE, 2.2, radius=6)
    # Trommel
    c.ellipse(cx, 97, 15, 15, steel, OUTLINE, 2.2)
    for i in range(6):
        a = i * math.pi / 3 + math.pi / 6
        c.ellipse(cx + math.cos(a) * 8.5, 97 + math.sin(a) * 8.5, 3.2, 3.2, (30, 30, 46, 255))
    c.ellipse(cx, 97, 3, 3, GOLD, OUTLINE, 1.2)
    c.d.arc(c.box(cx - 12, 85, cx + 12, 109), 200, 260, fill=(170, 180, 220, 255), width=round(1.6 * c.k))
    # Hahn
    c.rect(cx - 5, 116, cx + 5, 128, (50, 52, 80, 255), OUTLINE, 2, radius=2)
    # Griff
    c.rect(cx - 16, 124, cx + 16, 150, (122, 64, 196, 255), OUTLINE, 2.4, radius=7)
    c.rect(cx - 12, 128, cx - 6, 146, (150, 96, 220, 255), radius=3)
    for y in (132, 142):
        c.ellipse(cx + 7, y, 2.2, 2.2, GOLD, OUTLINE, 1)
    save(c.done(), GUI, "pistol.png")


def make_mount():
    W, H = 132, 62
    c = Canvas(W, H, px=3)
    cx = W / 2
    # Sockel (Halbkuppel)
    c.d.pieslice(c.box(4, 6, W - 4, H * 2 - 6), 180, 360, fill=(96, 50, 160, 255), outline=OUTLINE, width=round(2.6 * c.k))
    c.d.pieslice(c.box(18, 18, W - 18, H * 2 - 18), 180, 360, fill=(126, 74, 200, 255))
    # Goldrand
    c.d.arc(c.box(4, 6, W - 4, H * 2 - 6), 180, 360, fill=GOLD, width=round(4.5 * c.k))
    c.d.arc(c.box(4, 6, W - 4, H * 2 - 6), 180, 360, fill=OUTLINE, width=round(1.4 * c.k))
    # Glaskuppel mit Edelstein
    c.ellipse(cx, 40, 22, 20, (190, 220, 255, 150), OUTLINE, 2)
    gem = [(cx, 26), (cx + 10, 36), (cx + 6, 50), (cx - 6, 50), (cx - 10, 36)]
    c.poly(gem, (236, 72, 210, 255), OUTLINE, 1.8)
    c.poly([(cx, 26), (cx + 10, 36), (cx, 40), (cx - 10, 36)], (255, 140, 240, 255))
    c.d.arc(c.box(cx - 16, 24, cx + 16, 56), 200, 250, fill=(255, 255, 255, 200), width=round(2 * c.k))
    c.rect(0, H - 6, W, H, OUTLINE)
    save(c.done(), GUI, "mount.png")


def make_disc():
    S = 64
    c = Canvas(S, S, px=2)
    c.ellipse(S / 2, S / 2, S / 2 - 1, S / 2 - 1, (255, 255, 255, 255))
    save(c.done(), GUI, "disc.png")


def make_glow():
    S = 128
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - S / 2 + 0.5, y - S / 2 + 0.5) / (S / 2)
            a = max(0.0, 1.0 - d) ** 2.2
            px[x, y] = (255, 255, 255, round(255 * a))
    save(img, GUI, "glow.png")


def make_rays():
    S = 512
    k = SS
    img = Image.new("RGBA", (S * k, S * k), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = S * k / 2
    n = 16
    for i in range(n):
        a0 = (i / n) * 2 * math.pi
        a1 = a0 + (2 * math.pi / n) * 0.45
        r = S * k
        d.polygon([(c, c), (c + math.cos(a0) * r, c + math.sin(a0) * r), (c + math.cos(a1) * r, c + math.sin(a1) * r)], fill=(255, 255, 255, 255))
    img = img.resize((S, S), Image.LANCZOS)
    # Radial ausblenden
    mask = Image.new("L", (S, S), 0)
    mp = mask.load()
    for y in range(S):
        for x in range(S):
            dd = math.hypot(x - S / 2, y - S / 2) / (S / 2)
            mp[x, y] = round(255 * max(0.0, min(1.0, (1.0 - dd) * 1.6)) * min(1.0, dd * 4))
    from PIL import ImageChops
    a = ImageChops.multiply(img.split()[3], mask)
    img.putalpha(a)
    save(img, GUI, "rays.png")


def make_flash():
    S = 64
    c = Canvas(S, S, px=2)
    m = S / 2
    pts = []
    for i in range(16):
        a = i * math.pi / 8
        r = 30 if i % 2 == 0 else 13
        if i % 4 == 2:
            r = 22
        pts.append((m + math.cos(a) * r, m + math.sin(a) * r))
    c.poly(pts, (255, 196, 60, 255))
    pts2 = [(m + (x - m) * 0.62, m + (y - m) * 0.62) for x, y in pts]
    c.poly(pts2, (255, 244, 170, 255))
    c.ellipse(m, m, 8, 8, (255, 255, 255, 255))
    save(c.done(), GUI, "muzzle_flash.png")


def make_sparkle():
    S = 32
    c = Canvas(S, S, px=2)
    m = S / 2
    c.poly([(m, 1), (m + 4, m - 4), (S - 1, m), (m + 4, m + 4), (m, S - 1), (m - 4, m + 4), (1, m), (m - 4, m - 4)], (255, 255, 255, 255))
    save(c.done(), GUI, "sparkle.png")


def make_sign():
    W, H = 168, 40
    c = Canvas(W, H, px=3)
    # Ketten
    for x in (24, W - 24):
        c.line([(x, 0), (x, 8)], (60, 40, 70, 255), 2.2)
    c.rect(2, 6, W - 2, H - 2, (110, 56, 30, 255), OUTLINE, 2.6, radius=8)
    c.rect(7, 10, W - 7, H - 6, (152, 86, 44, 255), radius=6)
    for y in (17, 25):
        c.line([(12, y), (W - 12, y)], (132, 72, 38, 255), 1.2)
    c.rect(7, 10, W - 7, H - 6, None, GOLD, 2.2, radius=6)
    for x in (13, W - 13):
        for y in (14, H - 10):
            c.ellipse(x, y, 2, 2, GOLD, OUTLINE, 0.8)
    save(c.done(), GUI, "sign.png")


def make_banner():
    W, H = 260, 46
    c = Canvas(W, H, px=3)
    # Bandenden
    for flip in (0, 1):
        if flip == 0:
            pts = [(2, 10), (40, 10), (40, 42), (2, 42), (14, 26)]
        else:
            pts = [(W - 2, 10), (W - 40, 10), (W - 40, 42), (W - 2, 42), (W - 14, 26)]
        c.poly(pts, (200, 200, 200, 255), OUTLINE, 2.2)
    c.rect(26, 2, W - 26, 38, (255, 255, 255, 255), OUTLINE, 2.6, radius=5)
    c.rect(32, 6, W - 32, 11, (255, 255, 255, 255))
    c.rect(30, 30, W - 30, 35, (225, 225, 225, 255), radius=2)
    save(c.done(), GUI, "banner.png")


def make_card():
    """Hintergrundkarte für das große Gewinn-Bild beim Enthüllen (wird eingefärbt)."""
    S = 120
    c = Canvas(S, S, px=3)
    m = S / 2
    c.ellipse(m, m, 58, 58, OUTLINE)
    c.ellipse(m, m, 55.5, 55.5, (255, 255, 255, 255))
    c.ellipse(m, m, 48, 48, (225, 225, 225, 255))
    c.ellipse(m, m - 4, 44, 42, (245, 245, 245, 255))
    c.d.arc(c.box(m - 50, m - 50, m + 50, m + 50), 200, 255, fill=(255, 255, 255, 255), width=round(3 * c.k))
    save(c.done(), GUI, "card.png")


# ---------------------------------------------------------------------------
# Platzhalter-Bilder der 5 Gewinne (können per Ressourcenpaket ersetzt werden)
# ---------------------------------------------------------------------------
def prize_canvas():
    return Canvas(64, 64, px=4)


def make_prizes():
    # Goldnugget
    c = prize_canvas()
    pts = [(14, 36), (20, 22), (34, 16), (48, 22), (52, 36), (44, 48), (28, 50), (18, 46)]
    c.poly(pts, GOLD, OUTLINE, 3)
    c.poly([(20, 30), (26, 22), (36, 20), (32, 28)], GOLD_LIGHT)
    c.poly([(30, 46), (44, 44), (48, 36), (40, 40)], GOLD_DARK)
    c.ellipse(40, 28, 2.5, 2.5, (255, 255, 255, 230))
    save(c.done(), PRIZE, "goldnugget.png")

    # Goldbarren
    c = prize_canvas()
    c.poly([(8, 44), (20, 22), (56, 22), (60, 30), (48, 52), (8, 52)], GOLD_DARK, OUTLINE, 3)
    c.poly([(8, 44), (20, 22), (56, 22), (44, 44)], GOLD, OUTLINE, 2.4)
    c.poly([(44, 44), (56, 22), (60, 30), (48, 52)], (220, 150, 34, 255), OUTLINE, 2.4)
    c.poly([(8, 44), (44, 44), (48, 52), (8, 52)], GOLD_DARK, OUTLINE, 2.4)
    c.poly([(18, 38), (25, 27), (46, 27), (40, 38)], GOLD_LIGHT)
    c.line([(26, 33), (36, 33)], GOLD_DARK, 2)
    c.ellipse(48, 25.5, 2, 2, (255, 255, 255, 230))
    save(c.done(), PRIZE, "goldbarren.png")

    # Star-Tool (Spitzhacke mit Stern)
    c = prize_canvas()
    c.line([(18, 52), (44, 22)], OUTLINE, 9)
    c.line([(18, 52), (44, 22)], (150, 96, 60, 255), 5)
    head = [(20, 14), (34, 10), (46, 14), (56, 28), (58, 40), (50, 30), (40, 22), (30, 20), (16, 22)]
    c.poly(head, (90, 230, 255, 255), OUTLINE, 3)
    c.poly([(22, 15), (34, 12), (44, 16), (36, 18), (26, 18)], (200, 250, 255, 255))
    # Stern
    sx, sy, R, r = 46, 46, 11, 4.6
    star = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = R if i % 2 == 0 else r
        star.append((sx + math.cos(a) * rr, sy + math.sin(a) * rr))
    c.poly(star, (255, 214, 60, 255), OUTLINE, 2.4)
    save(c.done(), PRIZE, "star_tool.png")

    # Spawner (Käfig mit Flamme)
    c = prize_canvas()
    c.rect(10, 12, 54, 56, (34, 40, 58, 255), OUTLINE, 3, radius=3)
    flame = [(32, 22), (40, 34), (40, 44), (32, 50), (24, 44), (24, 34)]
    c.poly(flame, (255, 120, 40, 255))
    c.poly([(32, 30), (36, 38), (36, 44), (32, 47), (28, 44), (28, 38)], (255, 220, 90, 255))
    for x in (21, 32, 43):
        c.line([(x, 14), (x, 54)], (88, 100, 130, 255), 2.6)
    for y in (23, 34, 45):
        c.line([(12, y), (52, y)], (88, 100, 130, 255), 2.6)
    c.rect(10, 12, 54, 56, None, OUTLINE, 3, radius=3)
    c.line([(13, 15), (24, 15)], (140, 150, 190, 255), 1.6)
    save(c.done(), PRIZE, "spawner.png")

    # Goldticket
    c = prize_canvas()
    pts = [(6, 18), (58, 18), (58, 28), (54, 32), (58, 36), (58, 46), (6, 46), (6, 36), (10, 32), (6, 28)]
    c.poly(pts, GOLD, OUTLINE, 3)
    c.rect(12, 22, 52, 42, None, GOLD_DARK, 1.6, radius=2)
    sx, sy, R, r = 32, 32, 8.5, 3.6
    star = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = R if i % 2 == 0 else r
        star.append((sx + math.cos(a) * rr, sy + math.sin(a) * rr))
    c.poly(star, (255, 255, 255, 255), OUTLINE, 1.8)
    c.line([(16, 25), (22, 25)], GOLD_LIGHT, 1.6)
    save(c.done(), PRIZE, "goldticket.png")


# ---------------------------------------------------------------------------
# Slotmaschine (Kohle)
# ---------------------------------------------------------------------------
# Lage der Maschine in der Szene (identisch zu SlotMachineAnimation.java):
# Sprite oben links bei (160, 48), Größe 320 x 292.
# Walzenfenster im Sprite: x 45..275, y 76..202  ->  in der Szene x 205..435, y 124..250
RED = (222, 58, 92, 255)
RED_D = (178, 36, 76, 255)
RED_L = (248, 122, 152, 255)
PURPLE = (126, 63, 193, 255)
PURPLE_D = (60, 26, 100, 255)
PURPLE_L = (156, 96, 220, 255)


def bezier(p0, p1, p2, n=16):
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t ** 2 * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t ** 2 * p2[1]
        pts.append((x, y))
    return pts


def draw_curtain(c, flip):
    """Samtvorhang mit goldener Kordel (links, oder gespiegelt rechts)."""
    def fx(x):
        return 640 - x if flip else x

    inner = bezier((118, 30), (72, 118), (64, 196)) + bezier((64, 196), (66, 240), (104, 274))[1:]
    pts = [(28, 30)] + inner + [(28, 274)]
    pts = [(fx(x), y) for x, y in pts]
    c.poly(pts, (196, 40, 86, 255), OUTLINE, 2.4)
    # Falten
    for f, col in [(0.22, (160, 26, 70, 255)), (0.45, (226, 78, 118, 255)), (0.62, (160, 26, 70, 255)), (0.82, (226, 78, 118, 255))]:
        top = (28 + (118 - 28) * f, 34)
        mid = (28 + (64 - 28) * f, 194)
        bot = (28 + (104 - 28) * f, 270)
        line = bezier(top, (top[0] - 6 * f, 120), mid) + bezier(mid, (mid[0] + 2, 238), bot)[1:]
        c.line([(fx(x), y) for x, y in line], col, 2.2)
    # Kordel und Quaste
    c.rect(min(fx(24), fx(70)), 188, max(fx(24), fx(70)), 200, GOLD, OUTLINE, 2.2, radius=5)
    c.line([(fx(30), 194), (fx(64), 194)], GOLD_DARK, 1.4)
    qx = fx(58)
    c.line([(qx, 200), (qx, 210)], GOLD_DARK, 2)
    c.poly([(qx - 5, 208), (qx + 5, 208), (qx + 7, 228), (qx - 7, 228)], GOLD, OUTLINE, 1.8)
    c.line([(qx - 4, 222), (qx + 4, 222)], GOLD_DARK, 1.2)


def make_slot_background():
    W, H = 640, 360
    c = Canvas(W, H)
    k = c.k
    wall = vgradient(int(W * k), int(H * k), [
        (0.0, (36, 14, 72, 255)),
        (0.40, (84, 34, 136, 255)),
        (0.74, (110, 46, 156, 255)),
        (1.0, (110, 46, 156, 255)),
    ])
    c.img.paste(wall, (0, 0))
    c.d = ImageDraw.Draw(c.img)

    # Tapete: Rautenmuster mit goldenen Punkten
    def wallpaper(l):
        for i in range(-12, 34):
            x = i * 32
            l.line([(x, 34), (x + 240, 274)], (255, 255, 255, 26), 1.2)
            l.line([(x, 34), (x - 240, 274)], (255, 255, 255, 26), 1.2)
        for j in range(0, 16):
            for i in range(-1, 22):
                x, y = 16 * j + 32 * i, 34 + 16 * j
                if y < 268:
                    l.ellipse(x % 672 - 16, y, 1.6, 1.6, (255, 214, 120, 110))
    layer(c, wallpaper)

    # Leuchten hinter der Maschine und Scheinwerfer von oben
    def glow(l):
        l.ellipse(320, 196, 200, 150, (255, 140, 220, 70))
    layer(c, glow, blur=30)

    def spots(l):
        for x in (150, 490):
            l.poly([(x - 10, 34), (x + 10, 34), (x + 70 * (1 if x > 320 else -1) + 50, 300), (x + 70 * (1 if x > 320 else -1) - 50, 300)],
                   (255, 230, 255, 40))
    layer(c, spots, blur=6)

    # Sockelleiste und Teppich mit Fluchtpunkt
    c.rect(-4, 256, W + 4, 270, PURPLE_D, OUTLINE, 2.2)
    c.rect(-4, 268, W + 4, 273, GOLD, OUTLINE, 1.8)
    c.rect(-4, 273, W + 4, 364, (190, 62, 108, 255))
    for i in range(6):
        y = 273 + (i + 1) ** 1.55 * 4.4
        c.line([(0, y), (W, y)], (166, 46, 94, 255), 1.4)
    for i in range(-12, 13):
        xf = 320 + i * 34
        xn = 320 + i * 34 * 2.4
        c.line([(xf, 273), (xn, 364)], (166, 46, 94, 255), 1.4)
    for i in range(-11, 12):
        for j in range(5):
            y = 273 + (j + 1.5) ** 1.55 * 4.4
            f = (y - 273) / 91
            x = 320 + (i + 0.5) * 34 * (1 + 1.4 * f)
            c.ellipse(x, y, 1.4 + f, 1.0 + f * 0.6, (255, 200, 110, 255))

    # Schatten unter der Maschine
    def shadow(l):
        l.ellipse(320, 340, 160, 14, (40, 8, 40, 150))
    layer(c, shadow, blur=4)

    draw_curtain(c, False)
    draw_curtain(c, True)
    draw_pillars(c)
    draw_awning(c)
    save(c.done().convert("RGB").convert("RGBA"), GUI, "slot_background.png")


def make_slot_machine():
    W, H = 320, 292
    c = Canvas(W, H, px=3)
    # Hebel-Gehäuse rechts
    c.rect(290, 150, 318, 206, (150, 158, 196, 255), OUTLINE, 2.4, radius=6)
    c.rect(295, 155, 301, 201, (204, 212, 240, 255), radius=2)
    c.ellipse(307, 178, 8.5, 8.5, (70, 74, 110, 255), OUTLINE, 2)
    c.ellipse(307, 178, 3.5, 3.5, (40, 40, 60, 255))
    # Sockel
    c.rect(26, 272, 294, 290, PURPLE_D, OUTLINE, 2.6, radius=5)
    c.rect(32, 276, 288, 280, PURPLE, radius=2)
    # Gehäuse
    c.rect(20, 60, 300, 280, RED, OUTLINE, 3, radius=14)
    c.rect(268, 68, 294, 272, RED_D, radius=10)
    c.rect(27, 72, 35, 266, RED_L, radius=4)
    # Krone
    c.d.pieslice(c.box(44, 10, 276, 118), 180, 360, fill=PURPLE, outline=OUTLINE, width=round(3 * c.k))
    c.d.arc(c.box(52, 18, 268, 110), 180, 360, fill=GOLD, width=round(6 * c.k))
    c.d.arc(c.box(49, 15, 271, 113), 180, 360, fill=OUTLINE, width=round(1.2 * c.k))
    c.d.pieslice(c.box(64, 30, 256, 98), 180, 360, fill=PURPLE_D, outline=OUTLINE, width=round(1.6 * c.k))
    c.rect(38, 58, 282, 69, GOLD, OUTLINE, 2.4, radius=3)
    c.line([(44, 61.5), (276, 61.5)], GOLD_LIGHT, 1.4)
    # Fassungen der Kronen-Lichter (Positionen auch in SlotMachineAnimation.java)
    for i in range(9):
        a = math.radians(196 + i * 148 / 8)
        c.ellipse(160 + 108 * math.cos(a), 64 + 46 * math.sin(a), 3.4, 3.4, (60, 40, 70, 255))
    # Walzenfenster mit Goldrahmen
    c.rect(36, 69, 284, 210, GOLD, OUTLINE, 3, radius=9)
    c.rect(40, 73, 280, 206, GOLD_DARK, radius=7)
    c.rect(45, 76, 275, 202, (255, 248, 232, 255), OUTLINE, 2.4)
    for i in range(3):
        x0 = 45 + i * 230 / 3
        c.rect(x0 + 4, 80, x0 + 230 / 3 - 4, 198, (255, 252, 242, 255), radius=4)
    # Gewinnlinien-Pfeile (Linie liegt bei y = 139 im Sprite)
    c.poly([(30, 130), (44, 139), (30, 148)], (240, 60, 70, 255), OUTLINE, 1.8)
    c.poly([(290, 130), (276, 139), (290, 148)], (240, 60, 70, 255), OUTLINE, 1.8)
    # Bedienfeld
    c.rect(40, 216, 280, 248, PURPLE_D, OUTLINE, 2.4, radius=6)
    for x, col in [(78, (240, 70, 80, 255)), (114, (255, 200, 60, 255)), (150, (90, 220, 110, 255))]:
        c.ellipse(x, 233, 11, 8, (40, 18, 52, 255))
        c.ellipse(x, 231, 10, 7, col, OUTLINE, 2)
        c.ellipse(x - 3, 229, 3, 1.8, (255, 255, 255, 255))
    c.rect(186, 222, 268, 242, (34, 16, 48, 255), GOLD, 1.8, radius=4)
    for i in range(5):
        c.rect(193 + i * 14.5, 227, 203 + i * 14.5, 237, (120, 230, 140, 255) if i < 3 else (60, 110, 80, 255), radius=1.5)
    # Münzschale
    c.rect(70, 252, 250, 276, GOLD, OUTLINE, 2.6, radius=8)
    c.rect(78, 256, 242, 272, (40, 18, 52, 255), radius=6)
    c.rect(82, 266, 238, 271, (72, 38, 84, 255), radius=3)
    # Nieten
    for x in (30, 290):
        for y in (88, 262):
            c.ellipse(x, y, 2.4, 2.4, GOLD, OUTLINE, 1)

    def shine(l):
        l.d.arc(l.box(58, 24, 262, 104), 200, 250, fill=(255, 255, 255, 120), width=round(3 * l.k))
        l.rect(24, 64, 34, 150, (255, 255, 255, 40), radius=4)
    layer(c, shine)
    save(c.done(), GUI, "slot_machine.png")


def make_slot_glass():
    """Glas über den Walzen: Rundungs-Schatten oben/unten, Trennstege, Spiegelung."""
    W, H = 230, 126
    c = Canvas(W, H, px=3)
    hk = round(H * c.k)
    for y in range(hk):
        t = y / (hk - 1)
        a = 225 * max(0.0, 1 - t / 0.34) ** 1.7 + 225 * max(0.0, (t - 0.66) / 0.34) ** 1.7
        if a > 0.5:
            c.d.line([(0, y), (W * c.k, y)], fill=(35, 18, 48, round(a)))

    def glare(l):
        l.poly([(16, 0), (58, 0), (26, 126), (-16, 126)], (255, 255, 255, 34))
        l.poly([(66, 0), (76, 0), (44, 126), (34, 126)], (255, 255, 255, 26))
    layer(c, glare)
    for i in (1, 2):
        x = i * W / 3
        c.rect(x - 2.2, 0, x + 2.2, H, OUTLINE)
        c.rect(x - 0.6, 0, x + 0.6, H, (90, 60, 100, 255))
    c.rect(0, 0, W, H, None, OUTLINE, 2.6)
    save(c.done(), GUI, "slot_glass.png")


def make_lever_knob():
    S = 30
    c = Canvas(S, S, px=3)
    m = S / 2
    c.ellipse(m, m, 14, 14, OUTLINE)
    c.ellipse(m, m, 12.2, 12.2, (200, 30, 56, 255))
    c.ellipse(m - 1.4, m - 1.6, 10, 10, (236, 56, 76, 255))
    c.ellipse(m - 4.5, m - 5, 3.6, 2.6, (255, 190, 196, 255))
    save(c.done(), GUI, "lever_knob.png")


def make_coin():
    S = 24
    c = Canvas(S, S, px=3)
    m = S / 2
    c.ellipse(m, m, 11.5, 11.5, OUTLINE)
    c.ellipse(m, m, 10, 10, GOLD)
    c.ellipse(m, m, 7, 7, GOLD_LIGHT, GOLD_DARK, 1.6)
    star = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rr = 4.6 if i % 2 == 0 else 2
        star.append((m + math.cos(a) * rr, m + math.sin(a) * rr))
    c.poly(star, GOLD_DARK)
    c.d.arc(c.box(m - 8.5, m - 8.5, m + 8.5, m + 8.5), 200, 250, fill=(255, 255, 255, 255), width=round(1.4 * c.k))
    save(c.done(), GUI, "coin.png")


def make_hand():
    """Comic-Handschuh mit Ärmel. Greifpunkt bei (10, 37), der Ärmel zeigt nach rechts."""
    W, H = 150, 72
    c = Canvas(W, H, px=3)
    glove = (250, 250, 255, 255)
    glove_s = (210, 210, 230, 255)
    c.rect(78, 14, 156, 58, PURPLE, OUTLINE, 2.6, radius=6)
    c.rect(82, 18, 156, 25, PURPLE_L, radius=3)
    c.rect(70, 9, 86, 63, GOLD, OUTLINE, 2.4, radius=4)
    c.rect(73, 13, 77, 59, GOLD_LIGHT, radius=2)
    c.poly([(52, 18), (72, 12), (72, 60), (52, 54)], glove, OUTLINE, 2.4)
    c.line([(60, 20), (60, 52)], glove_s, 1.6)
    c.line([(66, 17), (66, 56)], glove_s, 1.6)
    c.rect(20, 13, 60, 60, glove, OUTLINE, 2.6, radius=14)
    for y in (27, 36, 45):
        c.line([(36, y), (52, y)], glove_s, 1.8)
    for i in range(4):
        y0 = 15 + i * 11
        c.rect(5, y0, 30, y0 + 11.5, glove, OUTLINE, 2.2, radius=5.5)
        c.line([(9, y0 + 3.5), (16, y0 + 3.5)], (255, 255, 255, 255), 1.2)
    c.ellipse(29, 15, 14, 7, glove, OUTLINE, 2.2)
    c.line([(20, 13), (34, 13)], glove_s, 1.4)
    img = c.done()
    save(img, GUI, "hand.png")
    # Gespiegelt (Ärmel nach links) - negative Skalierung wird im Spiel nicht gezeichnet
    save(img.transpose(Image.FLIP_LEFT_RIGHT), GUI, "hand_left.png")


# ---------------------------------------------------------------------------
# Roulette (Gold, Eisen, Redstone, Diamant, Lapis)
# ---------------------------------------------------------------------------
# Rad-Mitte in der Szene (WCX, WCY) = (320, 194), Neigung SQ = 0.56, Rad-Vergrößerung K = 1.22,
# Einsatz-Feld bei (540, 302) (identisch zu RouletteAnimation.java)
SQ = 0.56
WHEEL_K = 1.22
WCY = 194
CHIP_POS = (540, 302)
FELT = (30, 136, 98, 255)


def make_roulette_background():
    W, H = 640, 360
    c = Canvas(W, H)
    k = c.k
    wall = vgradient(int(W * k), int(H * k), [
        (0.0, (34, 12, 62, 255)),
        (0.26, (80, 28, 112, 255)),
        (1.0, (80, 28, 112, 255)),
    ])
    c.img.paste(wall, (0, 0))
    c.d = ImageDraw.Draw(c.img)

    def stripes(l):
        for x in range(28, 612, 24):
            l.rect(x, 34, x + 10, 96, (255, 255, 255, 16))
    layer(c, stripes)
    c.rect(-4, 80, W + 4, 86, GOLD, OUTLINE, 1.6)

    # Licht der Lampen
    def light(l):
        for x in (150, 490):
            l.poly([(x - 20, 76), (x + 20, 76), (x + 110, 330), (x - 110, 330)], (255, 236, 190, 46))
    layer(c, light, blur=10)

    # Tisch: Polsterrand und Filz in Perspektive
    rail = [(50, 88), (590, 88), (712, 372), (-72, 372)]
    c.poly(rail, (112, 34, 60, 255), OUTLINE, 2.6)
    c.line([(58, 92), (582, 92)], (156, 64, 92, 255), 2)
    felt = [(72, 101), (568, 101), (680, 372), (-40, 372)]
    c.poly(felt, (226, 180, 80, 255), OUTLINE, 2.2)
    felt_in = [(76, 104), (564, 104), (675, 372), (-35, 372)]
    c.poly(felt_in, FELT)

    def felt_light(l):
        l.ellipse(320, 236, 320, 150, (90, 210, 150, 110))
    layer(c, felt_light, blur=40, mask=poly_mask(c, felt_in))

    def felt_edge(l):
        l.poly(felt_in, None, (10, 60, 40, 150), 14)
    layer(c, felt_edge, blur=8, mask=poly_mask(c, felt_in))
    # aufgedruckte Goldlinie
    c.poly([(96, 116), (544, 116), (648, 372), (-8, 372)], None, (232, 200, 112, 255), 1.6)
    # Schatten unter dem Rad
    def wheel_shadow(l):
        l.ellipse(322, WCY + 16, 162 * WHEEL_K, 96 * WHEEL_K, (6, 30, 20, 150))
    layer(c, wheel_shadow, blur=8)
    # Einsatz-Feld (hier landen die Chips)
    c.ellipse(CHIP_POS[0], CHIP_POS[1], 32, 18, None, (232, 200, 112, 255), 1.8)
    c.ellipse(CHIP_POS[0], CHIP_POS[1], 26, 14.5, None, (232, 200, 112, 255), 1.0)

    # Lampen
    for x in (150, 490):
        c.line([(x, 30), (x, 60)], (60, 40, 70, 255), 1.6)
        c.poly([(x - 26, 77), (x + 26, 77), (x + 14, 58), (x - 14, 58)], (34, 104, 78, 255), OUTLINE, 2.2)
        c.line([(x - 10, 62), (x - 18, 74)], (90, 170, 130, 255), 1.6)
        c.rect(x - 27, 74, x + 27, 80, GOLD, OUTLINE, 1.8, radius=2)
        c.ellipse(x, 81, 7, 3, (255, 246, 210, 255))

    draw_pillars(c)
    draw_awning(c)
    save(c.done().convert("RGB").convert("RGBA"), GUI, "roulette_background.png")


def make_roulette_bowl():
    """Feststehende Holzschüssel mit Kugelbahn, schon perspektivisch (oval) gezeichnet."""
    W, H = 304, 196
    c = Canvas(W, H, px=3)
    cx, cy = 152, 87
    wood_d = (86, 40, 28, 255)
    wood = (150, 80, 42, 255)
    wood_l = (192, 114, 62, 255)
    track = (226, 176, 112, 255)

    def ell(r, dy=0, fill=None, outline=None, width=0):
        c.ellipse(cx, cy + dy, r, r * SQ, fill, outline, width)

    ell(150, 20, wood_d, OUTLINE, 3)
    c.rect(cx - 150, cy, cx + 150, cy + 20, wood_d)
    c.line([(cx - 150, cy), (cx - 150, cy + 20)], OUTLINE, 3)
    c.line([(cx + 150, cy), (cx + 150, cy + 20)], OUTLINE, 3)
    c.d.arc(c.box(cx - 150, cy + 11 - 150 * SQ, cx + 150, cy + 11 + 150 * SQ), 0, 180, fill=GOLD, width=round(2.4 * c.k))
    ell(150, 0, wood, OUTLINE, 3)
    ell(146, 0, None, wood_l, 1.6)
    ell(141, 0, wood, GOLD, 2.2)
    ell(136, 0, track, OUTLINE, 1.8)
    ell(131, 0, None, (240, 200, 140, 255), 1.2)
    ell(117, 0, (52, 24, 30, 255), OUTLINE, 2)
    # Rauten auf der Kugelbahn
    for i in range(8):
        a = i * math.pi / 4 + math.pi / 8
        x, y = cx + math.cos(a) * 121, cy + math.sin(a) * 121 * SQ
        c.poly([(x - 4, y), (x, y - 2.2), (x + 4, y), (x, y + 2.2)], GOLD, OUTLINE, 1)

    def shine(l):
        l.d.arc(l.box(cx - 146, cy - 146 * SQ, cx + 146, cy + 146 * SQ), 200, 250, fill=(255, 255, 255, 110), width=round(2.4 * l.k))
    layer(c, shine)
    save(c.done(), GUI, "roulette_bowl.png")


def wheel_canvas():
    return Canvas(232, 232, px=3)


def make_roulette_wheel():
    """Drehender Teil: dunkler Fächerring und der Kegel in der Mitte (von oben gesehen)."""
    c = wheel_canvas()
    m = 116
    c.ellipse(m, m, 115, 115, (60, 26, 36, 255), OUTLINE, 2)
    c.ellipse(m, m, 80, 80, OUTLINE)
    for i in range(12):
        a0 = i * 30 - 15
        col = (164, 92, 48, 255) if i % 2 == 0 else (130, 70, 38, 255)
        c.d.pieslice(c.box(m - 78.5, m - 78.5, m + 78.5, m + 78.5), a0, a0 + 30, fill=col)
    c.ellipse(m, m, 58, 58, (192, 120, 64, 255), GOLD, 2.4)
    for i in range(12):
        a = math.radians(i * 30)
        c.line([(m + math.cos(a) * 30, m + math.sin(a) * 30), (m + math.cos(a) * 56, m + math.sin(a) * 56)], GOLD_DARK, 1.6)
    c.ellipse(m, m, 30, 30, (150, 82, 44, 255), GOLD, 2.2)
    save(c.done(), GUI, "roulette_wheel.png")


def make_roulette_pocket():
    """Ein Fach (Keil von -15° bis +15°). Weiß, wird im Spiel in der Seltenheitsfarbe eingefärbt."""
    c = wheel_canvas()
    m = 116
    def wedge(r0, r1, col):
        pts = []
        for i in range(13):
            a = math.radians(-15 + 30 * i / 12)
            pts.append((m + math.cos(a) * r1, m + math.sin(a) * r1))
        for i in range(12, -1, -1):
            a = math.radians(-15 + 30 * i / 12)
            pts.append((m + math.cos(a) * r0, m + math.sin(a) * r0))
        c.poly(pts, col)
    wedge(80, 114, (255, 255, 255, 255))
    wedge(80, 87, (214, 214, 214, 255))
    wedge(107, 114, (236, 236, 236, 255))
    save(c.done(), GUI, "roulette_pocket.png")


def make_roulette_frets():
    """Goldene Stege zwischen den Fächern und Ränder des Fächerrings."""
    c = wheel_canvas()
    m = 116
    for i in range(12):
        a = math.radians(15 + i * 30)
        p0 = (m + math.cos(a) * 79, m + math.sin(a) * 79)
        p1 = (m + math.cos(a) * 115, m + math.sin(a) * 115)
        c.line([p0, p1], OUTLINE, 4.2)
        c.line([p0, p1], GOLD, 2.2)
    c.ellipse(m, m, 115, 115, None, OUTLINE, 3)
    c.ellipse(m, m, 113.2, 113.2, None, GOLD, 1.4)
    c.ellipse(m, m, 81, 81, None, OUTLINE, 3)
    c.ellipse(m, m, 81.2, 81.2, None, GOLD, 1.2)
    save(c.done(), GUI, "roulette_frets.png")


def make_roulette_turret():
    S = 64
    c = Canvas(S, S, px=3)
    m = S / 2
    for i in range(4):
        a = i * math.pi / 2
        p = (m + math.cos(a) * 25, m + math.sin(a) * 25)
        c.line([(m, m), p], OUTLINE, 7.5)
        c.line([(m, m), p], GOLD, 4.2)
        c.ellipse(p[0], p[1], 5.2, 5.2, GOLD, OUTLINE, 2)
        c.ellipse(p[0] - 1.4, p[1] - 1.4, 1.8, 1.8, GOLD_LIGHT)
    c.ellipse(m, m, 12, 12, GOLD, OUTLINE, 2.4)
    c.ellipse(m, m, 7, 7, GOLD_LIGHT, GOLD_DARK, 1.2)
    save(c.done(), GUI, "roulette_turret.png")


def make_ball():
    S = 20
    c = Canvas(S, S, px=3)
    m = S / 2
    c.ellipse(m, m, 9.4, 9.4, OUTLINE)
    c.ellipse(m, m, 7.8, 7.8, (206, 210, 228, 255))
    c.ellipse(m - 1, m - 1.2, 6.4, 6.4, (250, 250, 255, 255))
    c.ellipse(m - 3, m - 3.2, 2.2, 1.7, (255, 255, 255, 255))
    save(c.done(), GUI, "ball.png")


def make_chip():
    """Pokerchip schräg von oben. Hell gezeichnet, wird im Spiel eingefärbt. Oberseite-Mitte bei (18, 11)."""
    W, H = 36, 26
    c = Canvas(W, H, px=3)
    body = (200, 200, 200, 255)
    c.ellipse(18, 15.5, 16.5, 9.2, body, OUTLINE, 2)
    c.rect(1.5, 11, 34.5, 15.5, body)
    c.line([(1.5, 11), (1.5, 15.5)], OUTLINE, 2)
    c.line([(34.5, 11), (34.5, 15.5)], OUTLINE, 2)
    for xs in (4.5, 12, 24, 31.5):
        c.rect(xs - 1.6, 13, xs + 1.6, 19.5 if 8 < xs < 28 else 17.5, (255, 255, 255, 255))
    c.ellipse(18, 11, 16.5, 9.2, (230, 230, 230, 255), OUTLINE, 2)
    for i in range(8):
        a = i * math.pi / 4 + math.pi / 8
        x, y = 18 + math.cos(a) * 13.6, 11 + math.sin(a) * 7.6
        c.ellipse(x, y, 2.3, 1.4, (255, 255, 255, 255))
    c.ellipse(18, 11, 10.5, 5.8, None, (255, 255, 255, 255), 1.4)
    save(c.done(), GUI, "chip.png")


def make_rake():
    """Croupier-Schieber: Kopf links (Mitte bei 10, 30), Stiel nach rechts."""
    W, H = 330, 60
    c = Canvas(W, H, px=3)
    c.rect(22, 26.5, 336, 33.5, (176, 112, 64, 255), OUTLINE, 2.2, radius=3)
    c.line([(26, 28.6), (330, 28.6)], (214, 156, 100, 255), 1.4)
    c.rect(12, 23, 28, 37, GOLD, OUTLINE, 2, radius=3)
    c.line([(15, 25.5), (25, 25.5)], GOLD_LIGHT, 1.2)
    c.rect(4, 3, 17, 57, (120, 64, 36, 255), OUTLINE, 2.4, radius=4)
    c.line([(8, 7), (8, 53)], (160, 96, 58, 255), 1.6)
    save(c.done(), GUI, "rake.png")


if __name__ == "__main__":
    make_background()
    make_target_board()
    make_pistol()
    make_mount()
    make_glow()
    make_disc()
    make_rays()
    make_flash()
    make_sparkle()
    make_sign()
    make_banner()
    make_card()
    make_prizes()
    make_slot_background()
    make_slot_machine()
    make_slot_glass()
    make_lever_knob()
    make_coin()
    make_hand()
    make_roulette_background()
    make_roulette_bowl()
    make_roulette_wheel()
    make_roulette_pocket()
    make_roulette_frets()
    make_roulette_turret()
    make_ball()
    make_chip()
    make_rake()
