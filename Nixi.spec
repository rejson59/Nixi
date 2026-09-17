# -*- mode: python ; coding: utf-8 -*-
"""Spec PyInstallera — pojedynczy plik Nixi.exe (Windows)."""
import os

from PyInstaller.utils.hooks import collect_all

datas, binaries, hiddenimports = [], [], []
for pkg in ("PySide6", "vosk", "sounddevice", "mss"):
    try:
        d, b, h = collect_all(pkg)
        datas += d
        binaries += b
        hiddenimports += h
    except Exception as e:  # noqa: BLE001
        print(f"[Nixi.spec] UWAGA: nie udało się zebrać pakietu {pkg}: {e}")

# Zasoby aplikacji. Ikona/assety powstają w scripts/gen_icon.py — jeśli katalogu
# brak, budowa i tak przebiegnie (PyInstaller przerywa przy nieistniejącej ścieżce).
datas += [("nixi/ui", "nixi/ui")]
if os.path.isdir("nixi/assets"):
    datas += [("nixi/assets", "nixi/assets")]
else:
    print("[Nixi.spec] UWAGA: brak nixi/assets — uruchom najpierw scripts/gen_icon.py")

icon_path = "nixi/assets/icon.ico" if os.path.isfile("nixi/assets/icon.ico") else None

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
        # zależności ładowane dynamicznie (nie widać ich w importach statycznych)
        "pynput", "pynput.keyboard", "pynput.mouse",
        "pycaw", "pycaw.pycaw", "comtypes",
        "win32api", "win32con", "win32clipboard", "winreg",
        "PIL.Image",
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "tkinter",
        "PySide6.QtPdf", "PySide6.QtPdfWidgets",
        "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets", "PySide6.QtWebEngineQuick",
        "PySide6.Qt3DCore", "PySide6.Qt3DRender", "PySide6.QtCharts", "PySide6.QtDataVisualization",
        "PySide6.QtMultimedia", "PySide6.QtMultimediaWidgets", "PySide6.QtSql", "PySide6.QtTest",
        "PySide6.QtDesigner", "PySide6.QtHelp", "PySide6.QtUiTools",
        "matplotlib", "scipy", "pandas", "pytest",
    ],
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
    icon=icon_path,
    version="version_info.txt",
)
