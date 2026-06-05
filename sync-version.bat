@echo off
pushd "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-version.ps1"
set SYNC_ERR=%ERRORLEVEL%
popd
if not %SYNC_ERR%==0 (
    echo.
    echo SYNC VERSION FAILED
    exit /b 1
)