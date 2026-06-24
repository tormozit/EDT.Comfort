# Общие функции версии tormozit.comfort в OSGi-профиле PDE (MANIFEST, bundles.info, dev.properties).
$ErrorActionPreference = 'Stop'

$script:ComfortRepoRoot = if ($PSScriptRoot) { Split-Path $PSScriptRoot -Parent } else { 'C:\VC\EDT.Comfort' }
$script:ComfortVersionFile = Join-Path $script:ComfortRepoRoot 'site\version.txt'
$script:ComfortManifestPath = Join-Path $script:ComfortRepoRoot 'plugin\META-INF\MANIFEST.MF'
$script:ComfortBackupOsgi = Join-Path $script:ComfortRepoRoot 'launch\backup\osgi'
$script:ComfortWsOsgi = 'C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\Eclipse Application'
$script:ComfortBundlesInfoRel = 'org.eclipse.equinox.simpleconfigurator\bundles.info'
$script:ComfortPluginLocation = 'file:/C:/VC/EDT.Comfort/plugin/'

function Get-ComfortReleaseFromVersionFile {
    param([string]$VersionFile = $script:ComfortVersionFile)
    if (-not (Test-Path -LiteralPath $VersionFile)) {
        throw "version.txt not found: $VersionFile"
    }
    $release = $null
    Get-Content -LiteralPath $VersionFile -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(?:#.*)?$') {
            $release = $Matches[1]
        }
    }
    if (-not $release) {
        throw "comfort.release not found in $VersionFile"
    }
    return $release
}

function Get-ComfortExpectedQualifier {
    param([string]$VersionFile = $script:ComfortVersionFile)
    return "$(Get-ComfortReleaseFromVersionFile -VersionFile $VersionFile)-qualifier"
}

function Get-ComfortManifestVersion {
    param([string]$ManifestPath = $script:ComfortManifestPath)
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        throw "MANIFEST.MF not found: $ManifestPath"
    }
    foreach ($line in [System.IO.File]::ReadAllLines($ManifestPath)) {
        if ($line -match '^Bundle-Version:\s*(.+)$') {
            return $Matches[1].Trim()
        }
    }
    throw "Bundle-Version not found in $ManifestPath"
}

function Get-ComfortBundlesInfoVersion {
    param([string]$BundlesInfoPath)
    if (-not (Test-Path -LiteralPath $BundlesInfoPath)) {
        return $null
    }
    foreach ($line in [System.IO.File]::ReadAllLines($BundlesInfoPath)) {
        if ($line -match '^tormozit\.comfort,([^,]+),') {
            return $Matches[1]
        }
    }
    return $null
}

function Get-ComfortDevPropertiesVersion {
    param([string]$DevPropertiesPath)
    if (-not (Test-Path -LiteralPath $DevPropertiesPath)) {
        return $null
    }
    $found = $null
    foreach ($line in [System.IO.File]::ReadAllLines($DevPropertiesPath)) {
        if ($line -match '^tormozit\.comfort;([^=]+)=bin,lib/jacob\.jar$') {
            $found = $Matches[1]
        }
    }
    return $found
}

function Get-ComfortDevPropertiesStaleLines {
    param([string]$DevPropertiesPath)
    if (-not (Test-Path -LiteralPath $DevPropertiesPath)) {
        return @()
    }
    $stale = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($DevPropertiesPath)) {
        if ($line -match '^tormozit\.comfort;[^=]+=$') {
            [void]$stale.Add($line)
        }
    }
    return $stale.ToArray()
}

function Update-PdeOsgiComfortVersion {
    param(
        [string]$BundlesInfoPath,
        [string]$DevPropertiesPath,
        [string]$Qualifier
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    if (-not (Test-Path -LiteralPath $BundlesInfoPath)) {
        throw "bundles.info not found: $BundlesInfoPath"
    }
    $bundleLines = [System.IO.File]::ReadAllLines($BundlesInfoPath)
    $bundleUpdated = $false
    for ($i = 0; $i -lt $bundleLines.Count; $i++) {
        if ($bundleLines[$i] -match '^tormozit\.comfort,') {
            $bundleLines[$i] = "tormozit.comfort,$Qualifier,$($script:ComfortPluginLocation),4,false"
            $bundleUpdated = $true
            break
        }
    }
    if (-not $bundleUpdated) {
        throw "tormozit.comfort entry not found in $BundlesInfoPath"
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

function Test-ComfortOsgiVersionSync {
    param(
        [string]$ExpectedQualifier,
        [string]$ManifestPath = $script:ComfortManifestPath,
        [string]$BundlesInfoPath,
        [string]$DevPropertiesPath
    )
    $manifest = Get-ComfortManifestVersion -ManifestPath $ManifestPath
    $bundles = Get-ComfortBundlesInfoVersion -BundlesInfoPath $BundlesInfoPath
    $dev = Get-ComfortDevPropertiesVersion -DevPropertiesPath $DevPropertiesPath
    $stale = Get-ComfortDevPropertiesStaleLines -DevPropertiesPath $DevPropertiesPath
    $ok = ($manifest -eq $ExpectedQualifier) -and ($bundles -eq $ExpectedQualifier) -and ($dev -eq $ExpectedQualifier) -and ($stale.Count -eq 0)
    return [PSCustomObject]@{
        Ok = $ok
        Expected = $ExpectedQualifier
        Manifest = $manifest
        BundlesInfo = $bundles
        DevProperties = $dev
        StaleDevLines = $stale
    }
}

function Assert-ComfortOsgiVersionSync {
    param(
        [string]$Context,
        [string]$ExpectedQualifier,
        [string]$ManifestPath = $script:ComfortManifestPath,
        [string]$BundlesInfoPath,
        [string]$DevPropertiesPath
    )
    $state = Test-ComfortOsgiVersionSync -ExpectedQualifier $ExpectedQualifier `
        -ManifestPath $ManifestPath -BundlesInfoPath $BundlesInfoPath -DevPropertiesPath $DevPropertiesPath
    if ($state.Ok) {
        Write-Host "[$Context] tormozit.comfort OK: $($state.Expected)"
        return $state
    }
    $msg = @(
        "[$Context] Comfort OSGi version mismatch (tormozit.comfort).",
        "  Expected: $($state.Expected)",
        "  MANIFEST.MF: $($state.Manifest)",
        "  bundles.info: $($state.BundlesInfo)",
        "  dev.properties: $($state.DevProperties)"
    )
    if ($state.StaleDevLines.Count -gt 0) {
        $msg += "  Stale dev.properties lines: $($state.StaleDevLines -join '; ')"
    }
    $msg += @(
        '',
        'Symptom: image could not be loaded / cannot resolve bundle tormozit.comfort.',
        'Fix: powershell -File launch\restore-main.ps1 (close Eclipse first).',
        'Before Run: powershell -File launch\assert-comfort-osgi.ps1'
    )
    throw ($msg -join [Environment]::NewLine)
}

function Repair-ComfortWorkspaceOsgiVersion {
    param([string]$Qualifier)
    $bundlesDst = Join-Path $script:ComfortWsOsgi $script:ComfortBundlesInfoRel
    $devDst = Join-Path $script:ComfortWsOsgi 'dev.properties'
    cmd /c "attrib -R `"$bundlesDst`"" | Out-Null
    cmd /c "attrib -R `"$devDst`"" | Out-Null
    Update-PdeOsgiComfortVersion -BundlesInfoPath $bundlesDst -DevPropertiesPath $devDst -Qualifier $Qualifier
    attrib +R $bundlesDst
    cmd /c "attrib -R `"$devDst`"" | Out-Null
    Write-Host "Repaired workspace OSGi: $Qualifier"
}
