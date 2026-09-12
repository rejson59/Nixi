"""Informacje o systemie, zasilanie i jasność ekranu."""
from __future__ import annotations

import ctypes
import os
import platform
import shutil
import subprocess
import sys


def _ok(output: str, **extra) -> dict:
    return {"status": "ok", "output": output, **extra}


def _err(output: str) -> dict:
    return {"status": "error", "output": output}


def system_info(args: dict, ctx) -> dict:
    try:
        total, used, free = shutil.disk_usage(os.path.expanduser("~"))
        gb = 1024 ** 3
        lines = [
            f"System: {platform.system()} {platform.release()} ({platform.machine()})",
            f"Komputer: {platform.node()}",
            f"CPU: {os.cpu_count() or '?'} rdzeni",
            f"RAM: {_ram_gb():.1f} GB" if _ram_gb() else "RAM: ?",
            f"Dysk: {free / gb:.0f} GB wolnych z {total / gb:.0f} GB",
        ]
        if sys.platform == "win32":
            batt = _battery_windows()
            if batt:
                lines.append(f"Bateria: {batt}")
        return _ok("\n".join(l for l in lines if l))
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd odczytu informacji: {e}")


def _ram_gb() -> float | None:
    try:
        if sys.platform == "win32":
            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
            m = MEMORYSTATUSEX()
            m.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
            return m.ullTotalPhys / (1024 ** 3)
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) / (1024 ** 2)
    except Exception:  # noqa: BLE001
        return None
    return None


def _battery_windows() -> str | None:
    try:
        class SYSTEM_POWER_STATUS(ctypes.Structure):
            _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte),
                        ("BatteryLifePercent", ctypes.c_byte), ("SystemStatusFlag", ctypes.c_byte),
                        ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]
        s = SYSTEM_POWER_STATUS()
        if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)):
            return None
        if s.BatteryLifePercent == 255:
            return None
        on_ac = "na zasilaniu" if s.ACLineStatus == 1 else "na baterii"
        return f"{s.BatteryLifePercent}% ({on_ac})"
    except Exception:  # noqa: BLE001
        return None


def control_system(args: dict, ctx) -> dict:
    if sys.platform != "win32":
        return _err("Sterowanie systemem działa na Windows (wersja .exe).")
    action = (args.get("action") or "").strip().lower()
    try:
        if action == "lock":
            ctypes.windll.user32.LockWorkStation()
            return _ok("Komputer zablokowany.")
        if action == "sleep":
            ctypes.windll.powrprof.SetSuspendState(0, 1, 0)
            return _ok("Komputer usypiany.")
        if action == "shutdown":
            subprocess.Popen(["shutdown", "/s", "/t", "30", "/c", "Nixi zamyka komputer za 30 s (anuluj: shutdown /a)"])
            return _ok("Zamykanie komputera za 30 sekund.")
        if action == "restart":
            subprocess.Popen(["shutdown", "/r", "/t", "30", "/c", "Nixi uruchamia ponownie komputer za 30 s (anuluj: shutdown /a)"])
            return _ok("Ponowne uruchomienie za 30 sekund.")
        if action == "abort":
            subprocess.run(["shutdown", "/a"], capture_output=True, text=True, timeout=10)
            return _ok("Anulowano zamykanie/restart.")
        return _err(f"Nieznana akcja: {action}")
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd akcji systemowej: {e}")


def set_brightness(args: dict, ctx) -> dict:
    if sys.platform != "win32":
        return _err("Sterowanie jasnością działa na Windows (wersja .exe).")
    try:
        level = max(0, min(100, int(args.get("level") or 50)))
        ok = _set_brightness_dxva2(level)
        if ok:
            return _ok(f"Jasność ustawiona na {level}%.", level=level)
        return _err("Nie udało się ustawić jasności (monitor może nie obsługiwać DDC/CI).")
    except Exception as e:  # noqa: BLE001
        return _err(f"Błąd ustawiania jasności: {e}")


def _set_brightness_dxva2(level: int) -> bool:
    from ctypes import POINTER, Structure, byref, c_uint32, c_ulong, windll
    from ctypes.wintypes import DWORD, HANDLE, HDC, RECT

    try:
        class PHYSICAL_MONITOR(Structure):
            _fields_ = [("hPhysicalMonitor", HANDLE), ("szPhysicalMonitorDescription", ctypes.c_wchar * 128)]

        user32 = windll.user32
        dxva2 = windll.dxva2
        mon = ctypes.windll.user32.MonitorFromPoint(
            ctypes.wintypes.POINT(0, 0), 1  # MONITOR_DEFAULTTOPRIMARY
        )
        n = DWORD(0)
        if not dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR(mon, byref(n)) or n.value < 1:
            return False
        monitors = (PHYSICAL_MONITOR * n.value)()
        if not dxva2.GetPhysicalMonitorsFromHMONITOR(mon, n.value, monitors):
            return False
        for m in monitors:
            cur = DWORD(0)
            _min = DWORD(0)
            _max = DWORD(0)
            dxva2.GetMonitorBrightness(m.hPhysicalMonitor, byref(_min), byref(cur), byref(_max))
            target = _min.value + (level / 100.0) * (_max.value - _min.value)
            dxva2.SetMonitorBrightness(m.hPhysicalMonitor, DWORD(int(target)))
            dxva2.DestroyPhysicalMonitor(m.hPhysicalMonitor)
        return True
    except Exception:  # noqa: BLE001
        return False
