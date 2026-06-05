param(
    [string]$Root = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$versionFile = Join-Path $Root 'version.properties'

if (-not (Test-Path $versionFile)) {
    Write-Error "version.properties not found: $versionFile"
}

$release = $null
Get-Content -LiteralPath $versionFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(?:#.*)?$') {
        $release = $Matches[1]
    }
}

if (-not $release) {
    Write-Error "comfort.release not found in version.properties (expected format: 1.0.0.10)"
}

$qualifier = "$release-qualifier"
Write-Host "Sync version: $qualifier"

function Set-Utf8NoBomContent {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $Lines, $utf8NoBom)
}

$manifestPath = Join-Path $Root 'tormozit.edt\META-INF\MANIFEST.MF'
$manifestLines = Get-Content -LiteralPath $manifestPath -Encoding UTF8
$manifestUpdated = $false
for ($i = 0; $i -lt $manifestLines.Count; $i++) {
    if ($manifestLines[$i] -match '^Bundle-Version:\s') {
        $manifestLines[$i] = "Bundle-Version: $qualifier"
        $manifestUpdated = $true
        break
    }
}
if (-not $manifestUpdated) {
    Write-Error "Bundle-Version not found in MANIFEST.MF"
}
Set-Utf8NoBomContent -Path $manifestPath -Lines $manifestLines

$featurePath = Join-Path $Root 'tormozit.edt.feature\feature.xml'
$featureText = [System.IO.File]::ReadAllText($featurePath, [System.Text.Encoding]::UTF8)
$featurePattern = '(<feature\b[\s\S]*?\sversion=")[^"]+(")'
if (-not [regex]::IsMatch($featureText, $featurePattern)) {
    Write-Error "feature version attribute not found in feature.xml"
}
$featureTextNew = [regex]::Replace(
    $featureText,
    $featurePattern,
    "`${1}$qualifier`${2}",
    1
)
[System.IO.File]::WriteAllText($featurePath, $featureTextNew, (New-Object System.Text.UTF8Encoding($false)))

$sitePath = Join-Path $Root 'tormozit.edt.site\site.xml'
$siteText = [System.IO.File]::ReadAllText($sitePath, [System.Text.Encoding]::UTF8)
$siteJar = "features/tormozit.comfort.feature_${release}-qualifier.jar"
$sitePattern = 'url="features/tormozit\.comfort\.feature_[^"]+\.jar"'
if (-not [regex]::IsMatch($siteText, $sitePattern)) {
    Write-Error "feature url not found in site.xml"
}
$siteTextNew = [regex]::Replace(
    $siteText,
    $sitePattern,
    "url=`"$siteJar`""
)
[System.IO.File]::WriteAllText($sitePath, $siteTextNew, (New-Object System.Text.UTF8Encoding($false)))

$pomPath = Join-Path $Root 'pom.xml'
$pomText = [System.IO.File]::ReadAllText($pomPath, [System.Text.Encoding]::UTF8)
$pomPattern = '(<artifactId>tormozit\.edt\.parent</artifactId>\s*\r?\n\s*<version>)[^<]+(</version>)'
if (-not [regex]::IsMatch($pomText, $pomPattern)) {
    Write-Error "parent version not found in pom.xml"
}
$pomTextNew = [regex]::Replace(
    $pomText,
    $pomPattern,
    "`${1}$release-SNAPSHOT`${2}",
    1
)
[System.IO.File]::WriteAllText($pomPath, $pomTextNew, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Updated: MANIFEST.MF, feature.xml, site.xml, pom.xml"
