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
    $eclipse = Get-Process -Name 'eclipse*' -ErrorAction SilentlyContinue
    if ($eclipse) {
        throw "Close Eclipse PDE before -Repair (PID: $($eclipse.Id -join ','))"
    }
    Repair-ComfortWorkspaceOsgiVersion -Qualifier $expected
}

Assert-ComfortOsgiVersionSync -Context 'assert-comfort-osgi' `
    -ExpectedQualifier $expected `
    -BundlesInfoPath $wsBundles `
    -DevPropertiesPath $wsDev

Write-Host 'OK: MANIFEST, bundles.info and dev.properties are in sync.'
