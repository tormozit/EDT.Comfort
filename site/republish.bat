@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\prepare-release.ps1" -Mode Republish
if errorlevel 1 exit /b 1
endlocal
