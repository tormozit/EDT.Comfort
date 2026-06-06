param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'
$versionFile = Join-Path $PSScriptRoot 'version.txt'

if (-not (Test-Path $versionFile)) {
    Write-Error "version.txt not found: $versionFile"
}

$release = $null
Get-Content -LiteralPath $versionFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(?:#.*)?$') {
        $release = $Matches[1]
    }
}

if (-not $release) {
    Write-Error "comfort.release not found in version.txt (expected format: 1.0.0.10)"
}

# PDE и Tycho сами подставляют метку времени вместо "qualifier" при каждой сборке
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

# plugin/META-INF/MANIFEST.MF
$manifestPath = Join-Path $Root 'plugin\META-INF\MANIFEST.MF'
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

# feature/feature.xml
$featurePath = Join-Path $Root 'feature\feature.xml'
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

# site/site.xml
$sitePath = Join-Path $PSScriptRoot 'site.xml'
$siteText = [System.IO.File]::ReadAllText($sitePath, [System.Text.Encoding]::UTF8)
$siteJar = "features/tormozit.comfort.feature_${qualifier}.jar"
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

# site/pom.xml
$sitePomPath = Join-Path $PSScriptRoot 'pom.xml'
$sitePomText = [System.IO.File]::ReadAllText($sitePomPath, [System.Text.Encoding]::UTF8)
$sitePomPattern = '(<artifactId>comfort\.site</artifactId>\s*<version>)[^<]+(</version>)'
if ([regex]::IsMatch($sitePomText, $sitePomPattern)) {
    $sitePomNew = [regex]::Replace($sitePomText, $sitePomPattern, "`${1}$release`${2}")
    [System.IO.File]::WriteAllText($sitePomPath, $sitePomNew, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: site/pom.xml -> $release"
}

# tp/pom.xml
$tpPomPath = Join-Path $Root 'tp\pom.xml'
$tpPomText = [System.IO.File]::ReadAllText($tpPomPath, [System.Text.Encoding]::UTF8)
$tpPomPattern = '(<artifactId>tp</artifactId>\s*<version>)[^<]+(</version>)'
if ([regex]::IsMatch($tpPomText, $tpPomPattern)) {
    $tpPomNew = [regex]::Replace($tpPomText, $tpPomPattern, "`${1}$release`${2}")
    [System.IO.File]::WriteAllText($tpPomPath, $tpPomNew, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: tp/pom.xml -> $release"
}

# pom.xml (root) — update tp version reference in target-platform-configuration
$rootPomPath = Join-Path $Root 'pom.xml'
$rootPomText = [System.IO.File]::ReadAllText($rootPomPath, [System.Text.Encoding]::UTF8)
$rootPomPattern = '(<artifactId>tp</artifactId>\s*<version>)[^<]+(</version>)'
if ([regex]::IsMatch($rootPomText, $rootPomPattern)) {
    $rootPomNew = [regex]::Replace($rootPomText, $rootPomPattern, "`${1}$release`${2}")
    [System.IO.File]::WriteAllText($rootPomPath, $rootPomNew, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: pom.xml (root tp reference) -> $release"
}

Write-Host "Done. OSGi version template: $qualifier (timestamp подставится при сборке)"
