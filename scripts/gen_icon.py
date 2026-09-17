#!/usr/bin/env python3
"""Generuje ikonę Nixi (icon.png + wielorozmiarowy icon.ico).

Używa wyłącznie Pillow — bez Qt, więc działa też na maszynach CI bez OpenGL
i bez serwera X (poprzednia wersja oparta o PySide6 wymagała libGL).
"""
from __future__ import annotations

import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 256
ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

BG = (14, 10, 36, 255)
GLOW = (120, 70, 255)
ORB_INNER = (217, 196, 255)
ORB_MID = (139, 92, 246)
ORB_OUTER = (43, 22, 104)


def _lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))  # type: ignore[return-value]


def _radial_orb(size: int) -> Image.Image:
    """Kula z gradientem radialnym i miękką krawędzią (z kanałem alfa)."""
    ss = size * 2  # supersampling dla gładkich krawędzi
    img = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    px = img.load()
    cx, cy = ss * 0.42, ss * 0.38   # środek rozbłysku (lekko w lewo/górę)
    radius = ss * 0.5
    for y in range(ss):
        for x in range(ss):
            # maska koła (środek geometryczny)
            dx_c, dy_c = x - ss / 2, y - ss / 2
            if dx_c * dx_c + dy_c * dy_c > radius * radius:
                continue
            d = math.hypot(x - cx, y - cy) / (ss * 0.42)
            color = _lerp(ORB_INNER, ORB_MID, d / 0.45) if d < 0.45 else _lerp(ORB_MID, ORB_OUTER, (d - 0.45) / 0.55)
            px[x, y] = (*color, 255)
    return img.resize((size, size), Image.LANCZOS)


def _glow(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = size * 0.06
    d.ellipse([m, m, size - m, size - m], fill=(*GLOW, 110))
    return img.filter(ImageFilter.GaussianBlur(size * 0.07))


def _font(px: int) -> ImageFont.ImageFont:
    for name in ("DejaVuSans-Bold.ttf", "Arialbd.ttf", "arialbd.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, px)
        except OSError:
            continue
    return ImageFont.load_default()


def build_icon(size: int = SIZE) -> Image.Image:
    img = Image.new("RGBA", (size, size), BG)
    img.alpha_composite(_glow(size))

    orb_box = int(size * 0.80)
    orb = _radial_orb(orb_box)
    off = (size - orb_box) // 2
    img.alpha_composite(orb, (off, off))

    draw = ImageDraw.Draw(img)
    font = _font(int(size * 0.42))
    draw.text((size / 2, size / 2), "N", font=font, fill=(255, 255, 255, 255), anchor="mm")
    return img


def main() -> int:
    out_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "nixi", "assets"
    )
    os.makedirs(out_dir, exist_ok=True)

    img = build_icon(SIZE)
    png_path = os.path.join(out_dir, "icon.png")
    ico_path = os.path.join(out_dir, "icon.ico")
    img.save(png_path, "PNG")
    # Pillow zapisuje prawidłowy, wielorozmiarowy plik ICO (Qt zapisywał jeden rozmiar)
    img.save(ico_path, "ICO", sizes=ICO_SIZES)

    for path in (png_path, ico_path):
        if not os.path.getsize(path):
            print(f"BŁĄD: pusty plik {path}", file=sys.stderr)
            return 1
    print(f"ikona zapisana: {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
