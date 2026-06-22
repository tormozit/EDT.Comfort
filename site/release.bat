@echo off
setlocal
cd /d "%~dp0"

if /i "%~1"=="republish" (
    echo Republish same release line: sync-version + clean artifacts
    call "%~dp0clean.bat"
    if errorlevel 1 exit /b 1
    goto :done
)

echo New release: bump version + sync-version + clean artifacts
call "%~dp0bump-release.bat"
if errorlevel 1 exit /b 1
call "%~dp0sync-version.bat"
if errorlevel 1 exit /b 1

echo Cleaning old build artifacts...
if exist content.jar del /f content.jar
if exist artifacts.jar del /f artifacts.jar
if exist features rmdir /s /q features
if exist plugins rmdir /s /q plugins
if exist *.zip del /f /q *.zip

:done
echo.
echo Next steps:
echo   1. Build All in Eclipse update site project "site"
echo   2. restore-main.ps1 ^(Eclipse PDE closed^) — launch\restore-main.ps1
echo   3. Commit site/features, site/plugins, site/content.jar, site/artifacts.jar
echo   4. GitHub Actions -^> Publish p2 site -^> Run workflow
echo.
endlocal
