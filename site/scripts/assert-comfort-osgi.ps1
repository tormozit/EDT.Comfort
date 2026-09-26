# Проверка согласованности версии tormozit.comfort перед Run Eclipse Application.
param(
    [switch]$Repair
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'comfort-osgi-version.ps1')

$expected = Get-ComfortExpectedQualifier
$wsBundles = Join-Path $ComfortWsOsgi $ComfortBundlesInfoRel
$wsDev = Join-Path $ComfortWsOsgi 'dev.properties'

if ($Repair) {
    # Мешает только запущенная из PDE EDT (java с -data ...runtime-EclipseApplication): она держит
    # профиль. Сам PDE bundles.info не переписывает (generateProfile/clearConfig выключены), его
    # закрывать не нужно.
    $runtime = Get-CimInstance Win32_Process -Filter "Name like 'java%'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'runtime-EclipseApplication' }
    if ($runtime) {
        throw "Close runtime EDT (Eclipse Application) before -Repair (PID: $($runtime.ProcessId -join ','))"
    }
    Repair-ComfortWorkspaceOsgiVersion -Qualifier $expected
}

Assert-ComfortOsgiVersionSync -Context 'assert-comfort-osgi' `
    -ExpectedQualifier $expected `
    -BundlesInfoPath $wsBundles `
    -DevPropertiesPath $wsDev

Write-Host 'OK: MANIFEST, bundles.info and dev.properties are in sync.'
