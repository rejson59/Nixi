#!/usr/bin/env python3
"""Generuje ikonę Nixi (icon.png + icon.ico) przy pomocy PySide6."""
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QGuiApplication, QImage, QLinearGradient, QPainter, QRadialGradient

SIZE = 256


def main() -> None:
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

    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "nixi", "assets")
    os.makedirs(out_dir, exist_ok=True)
    img.save(os.path.join(out_dir, "icon.png"))
    img.save(os.path.join(out_dir, "icon.ico"))
    print("ikona zapisana:", out_dir)


if __name__ == "__main__":
    main()
