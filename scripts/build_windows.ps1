# Lokalna budowa Nixi.exe (Windows + Python 3.11/3.12)
# To samo robi CI: .github/workflows/build-windows.yml
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

$exe = Join-Path $root "dist\Nixi.exe"
if (-not (Test-Path $exe)) { throw "Brak $exe" }
$size = (Get-Item $exe).Length
if ($size -lt 10MB) { throw "Nixi.exe podejrzanie maly ($size B)" }

Write-Host "Smoke test (--version)..." -ForegroundColor Cyan
$p = Start-Process -FilePath $exe -ArgumentList "--version" -PassThru -NoNewWindow
if (-not $p.WaitForExit(120000)) { $p.Kill(); throw "Nixi.exe --version nie zakonczyl sie w 120 s" }
if ($p.ExitCode -ne 0) { throw "Nixi.exe --version zwrocil kod $($p.ExitCode)" }

Write-Host ("GOTOWE: {0} ({1:N1} MB)" -f $exe, ($size / 1MB)) -ForegroundColor Green
