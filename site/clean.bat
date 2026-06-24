@echo off
rem Скрипт очистки сайта перед сборкой новой версии.
rem Запускать из папки site перед нажатием "Build All".
rem Версия подтягивается из version.txt в этой же папке.

cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\sync-version.ps1"
if errorlevel 1 exit /b 1

echo Cleaning old build artifacts...
if exist content.jar del /f content.jar
if exist artifacts.jar del /f artifacts.jar
if exist features rmdir /s /q features
if exist plugins rmdir /s /q plugins
if exist *.zip del /f /q *.zip
echo Done. Now run "Build All" on project site in PDE.
