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
        total, _used, free = shutil.disk_usage(os.path.expanduser("~"))
        gb = 1024 ** 3
        ram = _ram_gb()
        lines = [
            f"System: {platform.system()} {platform.release()} ({platform.machine()})",
            f"Komputer: {platform.node()}",
            f"CPU: {os.cpu_count() or '?'} rdzeni",
            f"RAM: {ram:.1f} GB" if ram else "RAM: ?",
            f"Dysk: {free / gb:.0f} GB wolnych z {total / gb:.0f} GB",
        ]
        if sys.platform == "win32":
            batt = _battery_windows()
            if batt:
                lines.append(f"Bateria: {batt}")
        return _ok("\n".join(line for line in lines if line))
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
            subprocess.run(["shutdown", "/a"], capture_output=True, text=True, timeout=10, check=False)
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
    """Ustaw jasność monitora przez DDC/CI (dxva2.dll)."""
    from ctypes import Structure, byref, windll
    from ctypes.wintypes import DWORD, HANDLE, POINT

    try:
        class PHYSICAL_MONITOR(Structure):
            _fields_ = [("hPhysicalMonitor", HANDLE),
                        ("szPhysicalMonitorDescription", ctypes.c_wchar * 128)]

        dxva2 = windll.dxva2
        # MONITOR_DEFAULTTOPRIMARY = 1
        mon = windll.user32.MonitorFromPoint(POINT(0, 0), 1)
        n = DWORD(0)
        if not dxva2.GetNumberOfPhysicalMonitorsFromHMONITOR(mon, byref(n)) or n.value < 1:
            return False
        monitors = (PHYSICAL_MONITOR * n.value)()
        if not dxva2.GetPhysicalMonitorsFromHMONITOR(mon, n.value, monitors):
            return False
        changed = False
        for m in monitors:
            try:
                cur, lo, hi = DWORD(0), DWORD(0), DWORD(0)
                if dxva2.GetMonitorBrightness(m.hPhysicalMonitor, byref(lo), byref(cur), byref(hi)):
                    target = lo.value + (level / 100.0) * (hi.value - lo.value)
                    if dxva2.SetMonitorBrightness(m.hPhysicalMonitor, DWORD(round(target))):
                        changed = True
            finally:
                dxva2.DestroyPhysicalMonitor(m.hPhysicalMonitor)
        return changed
    except Exception:  # noqa: BLE001
        return False
