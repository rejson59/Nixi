@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title Nixi - budowanie EXE

echo Przygotowuje srodowisko...
call "%~dp0URUCHOM_NIXI.bat" --prepare-only
if errorlevel 1 exit /b 1

echo Instaluje narzedzia do budowy...
".venv\Scripts\python.exe" -m pip install -r requirements-build.txt
if errorlevel 1 goto :error

echo Generuje ikone...
".venv\Scripts\python.exe" scripts\gen_icon.py
if errorlevel 1 goto :error

echo Uruchamia samotesty...
".venv\Scripts\python.exe" -m nixi.selftest
if errorlevel 1 goto :error

echo Buduje Nixi.exe. To moze potrwac kilka minut...
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean Nixi.spec
if errorlevel 1 goto :error

echo.
echo GOTOWE!
echo Plik znajduje sie tutaj:
echo %~dp0dist\Nixi.exe
echo.
pause
endlocal
exit /b 0

:error
echo.
echo Budowanie nie powiodlo sie. Przewin terminal wyzej, aby zobaczyc szczegoly.
pause
endlocal
exit /b 1
