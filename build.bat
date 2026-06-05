@echo off
cd /d "%~dp0"

call "%~dp0sync-version.bat"
if errorlevel 1 exit /b 1

echo Building EDT Comfort plugin...
call mvn clean package -f pom.xml

if errorlevel 1 (
    echo.
    echo BUILD FAILED
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESS
echo ZIP: tormozit.edt.site\target\tormozit.comfort-*.zip
for %%f in (tormozit.edt.site\target\*.zip) do echo       %%f
