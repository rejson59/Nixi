"""Logowanie do pliku w katalogu danych + konsola."""
from __future__ import annotations

import logging
import sys
from logging.handlers import RotatingFileHandler

from . import paths

_FORMAT = "%(asctime)s %(levelname)-7s %(name)s: %(message)s"


def setup(verbose: bool = False) -> logging.Logger:
    root = logging.getLogger("nixi")
    root.setLevel(logging.DEBUG if verbose else logging.INFO)
    if root.handlers:
        return root
    fmt = logging.Formatter(_FORMAT)
    try:
        fh = RotatingFileHandler(paths.logs_dir() / "nixi.log", maxBytes=2_000_000, backupCount=2, encoding="utf-8")
        fh.setFormatter(fmt)
        root.addHandler(fh)
    except OSError as e:
        print(f"Nixi: nie udało się otworzyć pliku logu ({e}) — loguję tylko na konsolę.", file=sys.stderr)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    root.addHandler(sh)
    logging.getLogger("websockets").setLevel(logging.WARNING)
    return root
