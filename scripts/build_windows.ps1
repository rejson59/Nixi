# Lokalna budowa Nixi.exe (Windows + Python 3.11/3.12)
# To samo robi CI: docs/build-windows.workflow.yml
# Użycie (PowerShell):  powershell -ExecutionPolicy Bypass -File scripts\build_windows.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

python -m pip install --upgrade pip
pip install -r requirements.txt -r requirements-build.txt

Write-Host "[1/3] Ikona..." -ForegroundColor Cyan
python scripts/gen_icon.py
if ($LASTEXITCODE -ne 0) { throw "gen_icon.py failed" }

Write-Host "[2/3] Samotest (z testem akustycznym wake word)..." -ForegroundColor Cyan
python -m nixi.selftest --acoustics
if ($LASTEXITCODE -ne 0) { throw "selftest failed" }

Write-Host "[3/3] PyInstaller -> dist/Nixi.exe..." -ForegroundColor Cyan
pyinstaller --noconfirm --clean Nixi.spec
if ($LASTEXITCODE -ne 0) { throw "pyinstaller failed" }

Write-Host "GOTOWE: $root\dist\Nixi.exe" -ForegroundColor Green
