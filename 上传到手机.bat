@echo off
chcp 65001 >nul
title HiFiProbe upload - debug
setlocal

rem ============================================================
rem  DEBUG launcher for the upload GUI.
rem
rem  Normal use: double-click the .pyw next to this file instead.
rem  That one runs with pythonw.exe and shows no console at all.
rem
rem  Use THIS one when the .pyw does nothing: .pyw has no console,
rem  so a startup error just vanishes. Here you get the traceback.
rem
rem  ASCII ONLY on purpose:
rem    CMD tracks its read position in a .bat by BYTES, and the
rem    console codepage decides how many bytes make one character.
rem    Chinese text in the file + "chcp 65001" desyncs that, and the
rem    rest of the file gets parsed as garbage. Pure ASCII is byte-
rem    identical under cp936 and cp65001, so it can never desync.
rem    (Chinese printed by Python is fine -- that is why chcp is here.)
rem
rem  Drag a music folder onto this file to open the GUI with that
rem  folder already filled in.
rem ============================================================

set "PROJ=%~dp0"
if not exist "%PROJ%tools\hifiprobe-upload-gui.pyw" set "PROJ=C:\Users\123\Desktop\hifiprobe\"
set "GUI=%PROJ%tools\hifiprobe-upload-gui.pyw"

if not exist "%GUI%" goto no_gui

where python >nul 2>&1
if errorlevel 1 goto no_python

echo.
echo   Starting the GUI with a console attached...
echo   (close the window when you are done; errors show up here)
echo.
python "%GUI%" %*
echo.
echo   The GUI window has closed.
echo   If something went wrong, the reason is printed above.
echo.
pause
exit /b 0

:no_gui
echo.
echo   Not found: %GUI%
echo   Fix the PROJ path near the top of this .bat.
echo.
pause
exit /b 1

:no_python
echo.
echo   python is not on PATH -- it is required.
echo.
pause
exit /b 1
