param(
    [string]$SiteDir = (Split-Path $PSScriptRoot -Parent),
    [string]$Root = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
)

$ErrorActionPreference = 'Stop'
$versionFile = Join-Path $SiteDir 'version.txt'

if (-not (Test-Path -LiteralPath $versionFile)) {
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

$qualifier = "$release-qualifier"
$releaseSnapshot = "$release-SNAPSHOT"
Write-Host "Sync version: $qualifier (Maven: $releaseSnapshot)"

. (Join-Path $PSScriptRoot 'comfort-osgi-version.ps1')

function Set-Utf8NoBomContent {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $Lines, $utf8NoBom)
}

function Set-BundleVersion {
    param([string]$ManifestPath)
    $manifestLines = Get-Content -LiteralPath $ManifestPath -Encoding UTF8
    $manifestUpdated = $false
    for ($i = 0; $i -lt $manifestLines.Count; $i++) {
        if ($manifestLines[$i] -match '^Bundle-Version:\s') {
            $manifestLines[$i] = "Bundle-Version: $qualifier"
            $manifestUpdated = $true
            break
        }
    }
    if (-not $manifestUpdated) {
        Write-Error "Bundle-Version not found in $ManifestPath"
    }
    Set-Utf8NoBomContent -Path $ManifestPath -Lines $manifestLines
}

Set-BundleVersion -ManifestPath (Join-Path $Root 'plugin\META-INF\MANIFEST.MF')

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

$sitePath = Join-Path $SiteDir 'site.xml'
$siteText = [System.IO.File]::ReadAllText($sitePath, [System.Text.Encoding]::UTF8)
# 0.0.0 = любая версия фичи в workspace. Нельзя писать 1.0.1.N-qualifier:
# PDE вытаскивает версию из URL и ищет диапазон [1.0.1.N-,1.0.1.N0),
# а после бампа feature.xml уже другая — Build Site падает.
$siteJar = "features/tormozit.comfort.feature_0.0.0.jar"
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

$parentVersionPattern = '(<artifactId>comfort\.parent</artifactId>\s*<version>)[^<]+(</version>)'
@(
    Get-ChildItem -Path $Root -Filter 'pom.xml' -File -ErrorAction SilentlyContinue
    Get-ChildItem -Path $Root -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notin @('.tmp', 'build', '.git') } |
        ForEach-Object { Get-ChildItem -Path $_.FullName -Recurse -Filter 'pom.xml' -ErrorAction SilentlyContinue }
) |
    Where-Object { $_.FullName -notmatch '\\\.tmp\\|\\build\\' } |
    ForEach-Object {
        $pomText = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
        if ([regex]::IsMatch($pomText, $parentVersionPattern)) {
            $pomNew = [regex]::Replace($pomText, $parentVersionPattern, "`${1}$releaseSnapshot`${2}")
            [System.IO.File]::WriteAllText($_.FullName, $pomNew, (New-Object System.Text.UTF8Encoding($false)))
            Write-Host "Updated: $($_.FullName) parent version -> $releaseSnapshot"
        }
    }

$sitePomPath = Join-Path $SiteDir 'pom.xml'
$sitePomText = [System.IO.File]::ReadAllText($sitePomPath, [System.Text.Encoding]::UTF8)
$sitePomPattern = '(<artifactId>comfort\.site</artifactId>\s*<version>)[^<]+(</version>)'
if ([regex]::IsMatch($sitePomText, $sitePomPattern)) {
    $sitePomNew = [regex]::Replace($sitePomText, $sitePomPattern, "`${1}$release`${2}")
    [System.IO.File]::WriteAllText($sitePomPath, $sitePomNew, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: site/pom.xml -> $release"
}

$targetPomPath = Join-Path $Root 'target\pom.xml'
$targetPomText = [System.IO.File]::ReadAllText($targetPomPath, [System.Text.Encoding]::UTF8)
$targetPomPattern = '(<artifactId>tp</artifactId>\s*<version>)[^<]+(</version>)'
if ([regex]::IsMatch($targetPomText, $targetPomPattern)) {
    $targetPomNew = [regex]::Replace($targetPomText, $targetPomPattern, "`${1}$release`${2}")
    [System.IO.File]::WriteAllText($targetPomPath, $targetPomNew, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: target/pom.xml -> $release"
}

$launchOsgi = Join-Path $Root 'launch\backup\osgi'
$bundlesInfoPath = Join-Path $launchOsgi 'org.eclipse.equinox.simpleconfigurator\bundles.info'
$devPropertiesPath = Join-Path $launchOsgi 'dev.properties'
Update-PdeOsgiComfortVersion -BundlesInfoPath $bundlesInfoPath -DevPropertiesPath $devPropertiesPath -Qualifier $qualifier
Write-Host "Updated: launch/backup/osgi -> $qualifier"

Repair-ComfortWorkspaceOsgiVersion -Qualifier $qualifier

Write-Host "Done. OSGi version template: $qualifier (timestamp подставится при сборке)"
