# -*- mode: python ; coding: utf-8 -*-
"""Spec PyInstallera — pojedynczy plik Nixi.exe (Windows)."""
from PyInstaller.utils.hooks import collect_all

datas, binaries, hiddenimports = [], [], []
for pkg in ("PySide6", "vosk", "sounddevice", "mss"):
    try:
        d, b, h = collect_all(pkg)
        datas += d
        binaries += b
        hiddenimports += h
    except Exception:
        pass

datas += [
    ("nixi/ui", "nixi/ui"),
    ("nixi/assets", "nixi/assets"),
]

a = Analysis(
    ["nixi_launcher.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports + [
        "nixi", "nixi.loop", "nixi.config", "nixi.paths", "nixi.state",
        "nixi.core.live", "nixi.core.session", "nixi.core.demo", "nixi.core.memory",
        "nixi.core.instructions", "nixi.core.embeddings",
        "nixi.tools.registry", "nixi.tools.browser", "nixi.tools.apps",
        "nixi.tools.screen_control", "nixi.tools.volume", "nixi.tools.system_power",
        "nixi.tools.notes_memory", "nixi.tools.screenshot", "nixi.tools.session_end",
        "nixi.audio.vad", "nixi.audio.wake", "nixi.audio.player", "nixi.audio.synth",
        "nixi.ui.controller",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "PySide6.QtPdf", "PySide6.QtPdfWidgets"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="Nixi",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon="nixi/assets/icon.ico",
    version="version_info.txt",
)
