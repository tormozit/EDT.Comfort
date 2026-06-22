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
$releaseSnapshot = "$release-SNAPSHOT"
Write-Host "Sync version: $qualifier (Maven: $releaseSnapshot)"

function Set-Utf8NoBomContent {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $Lines, $utf8NoBom)
}

function Update-PdeOsgiComfortVersion {
    param(
        [string]$BundlesInfoPath,
        [string]$DevPropertiesPath,
        [string]$Qualifier
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    if (-not (Test-Path -LiteralPath $BundlesInfoPath)) {
        Write-Error "bundles.info not found: $BundlesInfoPath"
    }
    $bundleLines = [System.IO.File]::ReadAllLines($BundlesInfoPath)
    $bundleUpdated = $false
    for ($i = 0; $i -lt $bundleLines.Count; $i++) {
        if ($bundleLines[$i] -match '^tormozit\.comfort,') {
            $bundleLines[$i] = "tormozit.comfort,$Qualifier,file:/C:/VC/EDT.Comfort/plugin/,4,false"
            $bundleUpdated = $true
            break
        }
    }
    if (-not $bundleUpdated) {
        Write-Error "tormozit.comfort entry not found in $BundlesInfoPath"
    }
    [System.IO.File]::WriteAllLines($BundlesInfoPath, $bundleLines, $utf8NoBom)
    $devLines = @(
        '#',
        "#$(Get-Date -Format 'ddd MMM dd HH:mm:ss ''MSK'' yyyy')",
        "tormozit.comfort;$Qualifier=bin,lib/jacob.jar",
        '@ignoredot@=true',
        'tormozit.comfort=bin,lib/jacob.jar'
    )
    [System.IO.File]::WriteAllLines($DevPropertiesPath, $devLines, $utf8NoBom)
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

# pom.xml — версия parent и ссылки на parent во всех модулях
$parentVersionPattern = '(<artifactId>comfort\.parent</artifactId>\s*<version>)[^<]+(</version>)'
Get-ChildItem -Path $Root -Recurse -Filter 'pom.xml' | ForEach-Object {
    $pomText = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    if ([regex]::IsMatch($pomText, $parentVersionPattern)) {
        $pomNew = [regex]::Replace($pomText, $parentVersionPattern, "`${1}$releaseSnapshot`${2}")
        [System.IO.File]::WriteAllText($_.FullName, $pomNew, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "Updated: $($_.FullName) parent version -> $releaseSnapshot"
    }
}

# site/pom.xml — версия модуля site
$sitePomPath = Join-Path $PSScriptRoot 'pom.xml'
$sitePomText = [System.IO.File]::ReadAllText($sitePomPath, [System.Text.Encoding]::UTF8)
$sitePomPattern = '(<artifactId>comfort\.site</artifactId>\s*<version>)[^<]+(</version>)'
if ([regex]::IsMatch($sitePomText, $sitePomPattern)) {
    $sitePomNew = [regex]::Replace($sitePomText, $sitePomPattern, "`${1}$release`${2}")
    [System.IO.File]::WriteAllText($sitePomPath, $sitePomNew, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: site/pom.xml -> $release"
}

# target/pom.xml
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

Write-Host "Done. OSGi version template: $qualifier (timestamp подставится при сборке)"
