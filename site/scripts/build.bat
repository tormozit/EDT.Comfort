@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if defined JAVA_HOME (
    call :TryJavaHome "%JAVA_HOME%"
    if !JAVA_OK! equ 1 goto :java_ready
)

call :TryJavaHome "C:\Program Files\Zulu\zulu-17"
if !JAVA_OK! equ 1 goto :java_ready

for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

for /d %%D in ("C:\Program Files\Microsoft\jdk-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

for /d %%D in ("C:\Program Files\BellSoft\LibericaJDK-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

for /d %%D in ("C:\Program Files\Java\jdk-17*") do (
    call :TryJavaHome "%%D"
    if !JAVA_OK! equ 1 goto :java_ready
)

echo.
echo ОШИБКА: не найден рабочий JDK 17.
pause
exit /b 1

:TryJavaHome
set "JAVA_OK=0"
set "_JH=%~1"
if not exist "%_JH%\bin\java.exe" exit /b 0
"%_JH%\bin\java.exe" -version >nul 2>&1
if errorlevel 1 exit /b 0
set "JAVA_HOME=%_JH%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "JAVA_OK=1"
echo Using JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
exit /b 0

:java_ready
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-version.ps1"
if errorlevel 1 exit /b 1

if not exist "%~dp0..\..\target\tp.target" (
    echo Restoring target/tp.target from git...
    git -C "%~dp0..\.." checkout HEAD -- target/tp.target target/pom.xml
)

echo.
echo Building EDT Comfort plugin...
pushd "%~dp0..\.."
call mvn clean package -f pom.xml
set MVN_ERR=%ERRORLEVEL%
popd
if not %MVN_ERR%==0 (
    echo.
    echo BUILD FAILED
    pause
    exit /b 1
)

echo.
echo BUILD SUCCESS
echo ZIP: site\target\EDT.Comfort-*.zip
for %%f in ("%~dp0..\target\*.zip") do echo       %%f
endlocal
exit /b 0
