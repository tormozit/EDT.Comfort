# Rebuild launch/backup/osgi-no-comfort from launch/backup/osgi (UTF-8 no BOM).
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$mainOsgi = Join-Path $repoRoot "launch\backup\osgi"
$dstOsgi = Join-Path $repoRoot "launch\backup\osgi-no-comfort"
$mainBundles = "$mainOsgi\org.eclipse.equinox.simpleconfigurator\bundles.info"
$dstBundles = "$dstOsgi\org.eclipse.equinox.simpleconfigurator\bundles.info"
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

if (-not (Test-Path $mainBundles)) {
    throw "Missing main backup: $mainBundles"
}

New-Item -ItemType Directory -Force -Path "$dstOsgi\org.eclipse.equinox.simpleconfigurator" | Out-Null

$config = [System.IO.File]::ReadAllText("$mainOsgi\config.ini")
$config = $config -replace 'pde\.core/Eclipse Application/org\.eclipse', 'pde.core/Eclipse Application No Comfort/org.eclipse'
[System.IO.File]::WriteAllText("$dstOsgi\config.ini", $config, $utf8NoBom)

$devLines = @(
    "#",
    "#Fri Jun 12 11:24:07 MSK 2026",
    "@ignoredot@=true"
)
[System.IO.File]::WriteAllLines("$dstOsgi\dev.properties", $devLines, $utf8NoBom)

$lines = [System.IO.File]::ReadAllLines($mainBundles)
$filtered = $lines | Where-Object { $_ -notmatch '^tormozit\.comfort,' }
[System.IO.File]::WriteAllLines($dstBundles, $filtered, $utf8NoBom)

$head = [System.IO.File]::ReadAllBytes($dstBundles)[0..2]
if ($head.Count -ge 3 -and $head[0] -eq 0xEF -and $head[1] -eq 0xBB -and $head[2] -eq 0xBF) {
    throw "bundles.info still has UTF-8 BOM"
}

$size = (Get-Item $dstBundles).Length
Write-Host "OK: osgi-no-comfort bundles.info $size bytes, BOM: no"
