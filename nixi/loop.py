"""Główna pętla aplikacji Nixi — okno-tapeta, tray, skróty, kontroler."""
from __future__ import annotations

import argparse
import logging
import os
import sys

from . import paths
from . import version
from .logging_setup import setup as setup_logging


def _single_instance_guard(log: logging.Logger) -> object | None:
    """Pozwala na uruchomienie tylko jednej instancji Nixi."""
    if sys.platform == "win32":
        import ctypes

        mutex = ctypes.windll.kernel32.CreateMutexW(None, False, "NixiSingleInstanceMutex")
        if ctypes.windll.kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
            log.warning("Nixi już działa — wychodzę.")
            return None
        return mutex
    try:
        import fcntl

        lock_path = paths.data_dir() / "nixi.lock"
        fh = open(lock_path, "w")
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fh
    except Exception:  # noqa: BLE001
        log.warning("Nie udało się zablokować instancji — kontynuuję.")
        return object()


def _make_icon() -> "QIcon":
    from PySide6.QtCore import QPointF, QRectF, Qt
    from PySide6.QtGui import QColor, QIcon, QPainter, QPixmap, QRadialGradient

    pm = QPixmap(64, 64)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    g = QRadialGradient(QPointF(32, 32), 30)
    g.setColorAt(0.0, QColor("#c9a8ff"))
    g.setColorAt(0.55, QColor("#8b5cf6"))
    g.setColorAt(1.0, QColor("#3b1f8e"))
    p.setBrush(g)
    p.setPen(Qt.NoPen)
    p.drawEllipse(QRectF(4, 4, 56, 56))
    p.end()
    return QIcon(pm)


def _setup_tray(app, view, controller, log: logging.Logger):
    from PySide6.QtGui import QAction
    from PySide6.QtWidgets import QMenu, QSystemTrayIcon

    if not QSystemTrayIcon.isSystemTrayAvailable():
        log.warning("Brak tray systemowego.")
        return None
    tray = QSystemTrayIcon(_make_icon(), app)
    tray.setToolTip(f"Nixi {version.__version__}")
    menu = QMenu()

    def _show_desktop():
        view.showFullScreen()

    def _open_settings():
        root = view.rootObject()
        if root is not None:
            loader = root.findChild(type(root), "settingsLoader")
            if loader is not None:
                loader.setProperty("active", True)

    a_show = QAction("Pokaż pulpit Nixi", menu)
    a_show.triggered.connect(_show_desktop)
    menu.addAction(a_show)
    a_toggle = QAction("Włącz / wyłącz nasłuch", menu)
    a_toggle.triggered.connect(lambda: _safe_invoke(controller, "toggleListen"))
    menu.addAction(a_toggle)
    a_settings = QAction("Ustawienia", menu)
    a_settings.triggered.connect(_open_settings)
    menu.addAction(a_settings)
    menu.addSeparator()
    a_quit = QAction("Zakończ Nixi", menu)
    a_quit.triggered.connect(app.quit)
    menu.addAction(a_quit)
    tray.setContextMenu(menu)
    tray.activated.connect(lambda reason: _show_desktop() if reason == QSystemTrayIcon.DoubleClick else None)
    tray.show()
    return tray


def _safe_invoke(controller, method_name: str) -> None:
    from PySide6.QtCore import QMetaObject, Qt

    QMetaObject.invokeMethod(controller, method_name, Qt.QueuedConnection)


def _to_pynput_combo(combo: str) -> str:
    """Nasz format 'ctrl+shift+alt+n' → pynput '<ctrl>+<shift>+<alt>+n'."""
    names = {
        "ctrl": "<ctrl>", "control": "<ctrl>", "alt": "<alt>", "shift": "<shift>",
        "win": "<cmd>", "windows": "<cmd>", "cmd": "<cmd>",
        "esc": "<esc>", "escape": "<esc>", "enter": "<enter>", "return": "<enter>",
        "space": "<space>", "tab": "<tab>", "backspace": "<backspace>",
        "del": "<delete>", "delete": "<delete>", "home": "<home>", "end": "<end>",
        "up": "<up>", "down": "<down>", "left": "<left>", "right": "<right>",
        "pageup": "<page_up>", "pagedown": "<page_down>",
    }
    parts = [p.strip().lower() for p in (combo or "").split("+") if p.strip()]
    out = []
    for p in parts:
        if p in names:
            out.append(names[p])
        elif p.startswith("f") and p[1:].isdigit() and 1 <= int(p[1:]) <= 24:
            out.append(f"<f{p[1:]}>")
        elif len(p) == 1:
            out.append(p)
        else:
            return ""
    return "+".join(out)


def _setup_hotkeys(controller, app, log: logging.Logger):
    """Globalne skróty klawiszowe (pynput) — Windows/Linux z X11."""
    try:
        from pynput import keyboard
    except Exception as e:  # noqa: BLE001
        log.info("pynput niedostępny — bez globalnych skrótów: %s", e)
        return None
    try:
        hotkeys = {}
        mapping = {
            "toggle": controller.settings.hotkeys.toggle_listen,
            "end": controller.settings.hotkeys.end_session,
        }
        for action, combo in mapping.items():
            py_combo = _to_pynput_combo(combo)
            if not py_combo:
                log.warning("Nieprawidłowy skrót: %r — pomijam.", combo)
                continue
            if action == "toggle":
                hotkeys[py_combo] = lambda: _safe_invoke(controller, "toggleListen")
            else:
                hotkeys[py_combo] = lambda: _safe_invoke(controller, "endSessionNow")
        quit_combo = _to_pynput_combo(controller.settings.hotkeys.quit)
        if quit_combo:
            hotkeys[quit_combo] = app.quit
        hk = keyboard.GlobalHotKeys(hotkeys)
        hk.start()

        def _on_press(key):
            # Esc podczas aktywnej sesji = zakończ nasłuch
            try:
                if key == keyboard.Key.esc and controller.isActive():
                    _safe_invoke(controller, "endSessionNow")
            except Exception:  # noqa: BLE001
                pass

        listener = keyboard.Listener(on_press=_on_press)
        listener.start()
        return hk, listener
    except Exception as e:  # noqa: BLE001
        log.warning("Nie udało się uruchomić skrótów: %s", e)
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nixi", description="Nixi — asystentka głosowa")
    parser.add_argument("--windowed", action="store_true", help="tryb okienkowy (deweloperski)")
    parser.add_argument("--verbose", action="store_true", help="szczegółowe logi")
    parser.add_argument("--no-single", action="store_true", help="wyłącz strażnika pojedynczej instancji")
    parser.add_argument("--reset-settings", action="store_true", help="usuń ustawienia przy starcie")
    parser.add_argument("--version", action="store_true")
    args = parser.parse_args(argv)

    if args.version:
        print(version.__version__)
        return 0

    log = setup_logging(args.verbose)
    log.info("Nixi %s startuje…", version.__version__)

    if args.reset_settings:
        try:
            paths.settings_path().unlink(missing_ok=True)
        except Exception:  # noqa: BLE001
            pass

    if not args.no_single:
        guard = _single_instance_guard(log)
        if guard is None:
            return 0

    from PySide6.QtCore import QCoreApplication, Qt
    from PySide6.QtGui import QGuiApplication
    from PySide6.QtQuick import QQuickView
    from PySide6.QtQuickControls2 import QQuickStyle
    from PySide6.QtCore import QUrl

    QCoreApplication.setAttribute(Qt.AA_ShareOpenGLContexts)
    QQuickStyle.setStyle("Material")
    app = QGuiApplication(sys.argv[:1] if argv else [sys.argv[0]])
    app.setApplicationName("Nixi")
    app.setApplicationDisplayName("Nixi")
    app.setQuitOnLastWindowClosed(False)

    from .ui.controller import Controller

    controller = Controller(logger=log)

    ui_dir = paths.ui_qml_path()
    view = QQuickView()
    view.setTitle("Nixi")
    view.setResizeMode(QQuickView.SizeRootObjectToView)
    engine = view.engine()
    engine.addImportPath(str(ui_dir))
    engine.rootContext().setContextProperty("bridge", controller)
    view.setSource(QUrl.fromLocalFile(str(ui_dir / "main.qml")))
    if view.status() != QQuickView.Ready:
        log.error("Nie udało się załadować interfejsu QML: %s", view.errors())
        return 1

    if args.windowed:
        view.resize(1280, 800)
        view.show()
    else:
        # tapeta: bez ramki, pod wszystkimi oknami
        view.setFlags(Qt.FramelessWindowHint | Qt.WindowStaysOnBottomHint)
        view.showFullScreen()

    _setup_tray(app, view, controller, log)
    _hotkeys = _setup_hotkeys(controller, app, log)

    controller.start()
    controller.firstRun.emit(not bool(controller.memory.get_kv("first_run_done")))

    log.info("Nasłuch włączony (wake word: „Hej Nixi”).")
    try:
        rc = app.exec()
    finally:
        log.info("Zamykanie Nixi…")
        try:
            if _hotkeys:
                for h in _hotkeys:
                    try:
                        h.stop()
                    except Exception:  # noqa: BLE001
                        pass
        except Exception:  # noqa: BLE001
            pass
        controller.shutdown()
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
