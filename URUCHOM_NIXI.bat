@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Nixi - uruchamianie

set "PYTHON_CMD="

rem Najpierw szukamy wspieranego Pythona przez Python Launcher.
py -3.12 -c "import sys" >nul 2>&1
if not errorlevel 1 set "PYTHON_CMD=py -3.12"
if not defined PYTHON_CMD (
    py -3.11 -c "import sys" >nul 2>&1
    if not errorlevel 1 set "PYTHON_CMD=py -3.11"
)

rem Fallback, gdy Python Launcher nie jest zainstalowany.
if not defined PYTHON_CMD (
    python -c "import sys; raise SystemExit(0 if (3, 11) <= sys.version_info[:2] < (3, 13) else 1)" >nul 2>&1
    if not errorlevel 1 set "PYTHON_CMD=python"
)

if not defined PYTHON_CMD (
    echo.
    echo Nie znaleziono Pythona 3.11 lub 3.12.
    echo Zainstaluj Python z https://www.python.org/downloads/windows/
    echo Podczas instalacji zaznacz opcje ^"Add Python to PATH^".
    echo.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo [1/2] Przygotowuje srodowisko Nixi. To wykonuje sie tylko raz...
    %PYTHON_CMD% -m venv .venv
    if errorlevel 1 goto :error_venv
)

if not exist ".venv\.nixi_runtime_ready" (
    echo [2/2] Instaluje biblioteki Nixi. Przy pierwszym uruchomieniu moze to potrwac kilka minut...
    ".venv\Scripts\python.exe" -m pip install --upgrade pip
    if errorlevel 1 goto :error_pip
    ".venv\Scripts\python.exe" -m pip install -r requirements.txt
    if errorlevel 1 goto :error_pip
    echo runtime dependencies installed>".venv\.nixi_runtime_ready"
)

if /I "%~1"=="--prepare-only" (
    echo Srodowisko Nixi jest gotowe.
    exit /b 0
)

echo Uruchamiam Nixi. Zamknij aplikacje z jej menu albo Ctrl+C w tym oknie.
".venv\Scripts\python.exe" -m nixi --windowed --verbose
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
    echo.
    echo Nixi zakonczyla sie z bledem. Szczegoly sa w logu aplikacji.
    pause
)
endlocal & exit /b %RC%

:error_venv
echo.
echo Nie udalo sie utworzyc srodowiska Python.
pause
exit /b 1

:error_pip
echo.
echo Nie udalo sie zainstalowac bibliotek. Sprawdz internet i uruchom plik ponownie.
pause
exit /b 1
