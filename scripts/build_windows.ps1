param(
    [switch]$Acoustics
)

# Lokalna budowa Nixi.exe (Windows + Python 3.11/3.12)
# Użycie podstawowe: powershell -ExecutionPolicy Bypass -File scripts\build_windows.ps1
# Pełny test wake word:  powershell -ExecutionPolicy Bypass -File scripts\build_windows.ps1 -Acoustics

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

python -m pip install --upgrade pip
pip install -r requirements.txt -r requirements-build.txt
if ($Acoustics) {
    pip install -r requirements-acoustics.txt
}

Write-Host "[1/3] Ikona..." -ForegroundColor Cyan
python scripts/gen_icon.py
if ($LASTEXITCODE -ne 0) { throw "gen_icon.py failed" }

if ($Acoustics) {
    Write-Host "[2/3] Samotest (z testem akustycznym wake word)..." -ForegroundColor Cyan
    python -m nixi.selftest --acoustics
} else {
    Write-Host "[2/3] Samotest (bez opcjonalnego testu akustycznego)..." -ForegroundColor Cyan
    python -m nixi.selftest
}
if ($LASTEXITCODE -ne 0) { throw "selftest failed" }

Write-Host "[3/3] PyInstaller -> dist/Nixi.exe..." -ForegroundColor Cyan
pyinstaller --noconfirm --clean Nixi.spec
if ($LASTEXITCODE -ne 0) { throw "pyinstaller failed" }

Write-Host "GOTOWE: $root\dist\Nixi.exe" -ForegroundColor Green
