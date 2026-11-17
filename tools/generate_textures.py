#!/usr/bin/env python3
"""
Erzeugt alle Grafiken der Schießbuden-Animation (Comic-Stil mit dicken Konturen).

Aufruf (aus dem Projektordner):  python3 tools/generate_textures.py
Benötigt: Pillow  (pip install pillow)

Das Szenen-Layout ist in "virtuellen Einheiten" (640 x 360) definiert. Dieselben
Zahlen stehen in SceneLayout.java - wer hier Podeste verschiebt, muss sie dort
ebenfalls anpassen.
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
    img = Image.new("RGBA", (w, h))
    px = img.load()
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
        for x in range(w):
            px[x, y] = c
    return img


# ---------------------------------------------------------------------------
# Hintergrund: Jahrmarkt-Schießbude
# ---------------------------------------------------------------------------
# Podeste (x0, x1, top_y) - identisch zu SceneLayout.java
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

    # Seitensäulen
    for x0 in (0, 612):
        c.rect(x0, 36, x0 + 28, 296, (92, 46, 150, 255), OUTLINE, 2.4)
        c.rect(x0 + 10, 36, x0 + 18, 296, (120, 66, 186, 255))
        for y in range(56, 290, 26):
            c.ellipse(x0 + 14, y, 4, 4, (255, 230, 150, 255), OUTLINE, 1.4)

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
    # Fassungen der Glühbirnen (Positionen auch in SceneLayout.java)
    for i in range(16):
        x = 20 + i * 40
        c.rect(x - 3, 47, x + 3, 51, (60, 40, 70, 255), radius=1)

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
