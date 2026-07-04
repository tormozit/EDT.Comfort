# Project rules

## Язык ответа
По умолчанию отвечать на русском языке.

## Java
Java 17 is required for compilation. Use:
```
C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64
```
Set `JAVA_HOME` to this path before running Maven.

## Мaven
Build is via Tycho (eclipse-plugin packaging). Full build requires the EDT target platform.
The Maven installation is at `C:\Program Files\apache-maven`.

## Логи
Если в корне проекта есть файл `debug-*.log` — читать его сразу до любых вопросов пользователю. В логе — NDJSON-диагностика выполнения.

## Build
```powershell
$env:JAVA_HOME = "C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64"
mvn compile -pl plugin -am
```
Note: Full target platform resolution may fail outside Eclipse PDE environment.
