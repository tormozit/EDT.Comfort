# Проверяет консистентность content.jar и artifacts.jar в папке site.
# Убеждается что оба файла описывают одну и ту же сборку (одинаковый qualifier).
# Запускать после "Build All" в PDE, перед коммитом и деплоем.

param(
    [string]$SiteDir = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'

function Get-QualifierFromJar {
    param(
        [string]$JarPath,
        [string]$XmlName,
        [string]$Pattern
    )
    if (-not (Test-Path -LiteralPath $JarPath)) {
        Write-Error "Файл не найден: $JarPath`nЗапусти 'Build All' в PDE."
    }
    $tmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ('comfort_verify_' + [System.IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($JarPath, $tmpDir)
        $xmlPath = Join-Path $tmpDir $XmlName
        if (-not (Test-Path -LiteralPath $xmlPath)) {
            Write-Error "$XmlName не найден внутри $JarPath"
        }
        $xml = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
        $m = [regex]::Match($xml, $Pattern)
        if (-not $m.Success) {
            Write-Error "Не удалось найти qualifier в $XmlName (паттерн: $Pattern)"
        }
        return $m.Groups[1].Value
    }
    finally {
        Remove-Item -LiteralPath $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "=== Проверка консистентности сборки сайта ==="
Write-Host "Папка: $SiteDir"
Write-Host ""

$contentJar   = Join-Path $SiteDir 'content.jar'
$artifactsJar = Join-Path $SiteDir 'artifacts.jar'

# Из content.xml берём версию feature.group IU
$contentQualifier = Get-QualifierFromJar `
    -JarPath $contentJar `
    -XmlName 'content.xml' `
    -Pattern "id='tormozit\.comfort\.feature\.feature\.group'\s+version='([^']+)'"

# Из artifacts.xml берём версию osgi.bundle артефакта
$artifactsQualifier = Get-QualifierFromJar `
    -JarPath $artifactsJar `
    -XmlName 'artifacts.xml' `
    -Pattern "classifier='osgi\.bundle'\s+id='tormozit\.comfort'\s+version='([^']+)'"

Write-Host "content.jar   qualifier: $contentQualifier"
Write-Host "artifacts.jar qualifier: $artifactsQualifier"
Write-Host ""

if ($contentQualifier -ne $artifactsQualifier) {
    Write-Host "ОШИБКА: qualifier не совпадают!" -ForegroundColor Red
    Write-Host ""
    Write-Host "content.jar и artifacts.jar описывают разные сборки." -ForegroundColor Red
    Write-Host "Если задеплоить в таком виде - плагин не будет виден в 'Установленное ПО'." -ForegroundColor Red
    Write-Host ""
    Write-Host "Решение: запусти clean.bat, затем 'Build All' заново." -ForegroundColor Yellow
    exit 1
}

Write-Host "OK: оба файла имеют одинаковый qualifier ($contentQualifier)." -ForegroundColor Green
Write-Host "Можно коммитить и деплоить."
