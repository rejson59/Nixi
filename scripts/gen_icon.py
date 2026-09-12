#!/usr/bin/env python3
"""Generuje ikonę Nixi (icon.png + icon.ico).

Preferuje Qt, ale ma fallback Pillow, dzięki czemu można przygotować artefakty
również w środowisku headless bez systemowej biblioteki libGL.
"""
from __future__ import annotations

import os
import sys

SIZE = 256

try:
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    from PySide6.QtCore import QPointF, QRectF, Qt
    from PySide6.QtGui import QColor, QGuiApplication, QImage, QPainter, QRadialGradient

    _HAS_QT = True
except ImportError:
    _HAS_QT = False


def _output_dir() -> str:
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "nixi", "assets")


def _generate_qt(out_dir: str) -> None:
    app = QGuiApplication(sys.argv)
    img = QImage(SIZE, SIZE, QImage.Format_ARGB32)
    img.fill(QColor("#0e0a24"))
    p = QPainter(img)
    p.setRenderHint(QPainter.Antialiasing)

    # poświata
    glow = QRadialGradient(QPointF(SIZE / 2, SIZE / 2), SIZE * 0.55)
    glow.setColorAt(0.0, QColor(120, 70, 255, 90))
    glow.setColorAt(1.0, QColor(120, 70, 255, 0))
    p.setBrush(glow)
    p.setPen(Qt.NoPen)
    p.drawRect(QRectF(0, 0, SIZE, SIZE))

    # kula
    g = QRadialGradient(QPointF(SIZE * 0.42, SIZE * 0.38), SIZE * 0.42)
    g.setColorAt(0.0, QColor("#d9c4ff"))
    g.setColorAt(0.45, QColor("#8b5cf6"))
    g.setColorAt(1.0, QColor("#2b1668"))
    p.setBrush(g)
    p.drawEllipse(QRectF(SIZE * 0.10, SIZE * 0.10, SIZE * 0.80, SIZE * 0.80))

    # litera N
    p.setPen(QColor("#ffffff"))
    font = p.font()
    font.setPixelSize(int(SIZE * 0.42))
    font.setBold(True)
    p.setFont(font)
    p.drawText(QRectF(0, 0, SIZE, SIZE), Qt.AlignCenter, "N")
    p.end()

    img.save(os.path.join(out_dir, "icon.png"))
    img.save(os.path.join(out_dir, "icon.ico"))
    app.quit()


def _generate_pillow(out_dir: str) -> None:
    from PIL import Image, ImageDraw, ImageFont

    img = Image.new("RGBA", (SIZE, SIZE), "#0e0a24")
    pixels = img.load()
    # Prosty gradient promienisty — wystarczający jako ikona awaryjna.
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (x - SIZE * 0.42) / (SIZE * 0.55)
            dy = (y - SIZE * 0.38) / (SIZE * 0.55)
            glow = max(0.0, 1.0 - (dx * dx + dy * dy))
            pixels[x, y] = (14 + int(65 * glow), 10 + int(40 * glow), 36 + int(115 * glow), 255)

    draw = ImageDraw.Draw(img)
    cx = cy = SIZE // 2
    radius = int(SIZE * 0.40)
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill="#7147c9", outline="#c9a8ff", width=3)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", int(SIZE * 0.42))
    except OSError:
        font = ImageFont.load_default()
    box = draw.textbbox((0, 0), "N", font=font)
    draw.text((cx - (box[2] - box[0]) / 2, cy - (box[3] - box[1]) / 2 - box[1]), "N", fill="white", font=font)

    img.save(os.path.join(out_dir, "icon.png"))
    img.save(os.path.join(out_dir, "icon.ico"), sizes=[(256, 256), (128, 128), (64, 64), (32, 32), (16, 16)])


def main() -> None:
    out_dir = _output_dir()
    os.makedirs(out_dir, exist_ok=True)
    if _HAS_QT:
        _generate_qt(out_dir)
        method = "Qt"
    else:
        _generate_pillow(out_dir)
        method = "Pillow (fallback headless)"
    print(f"ikona zapisana ({method}): {out_dir}")


if __name__ == "__main__":
    main()
