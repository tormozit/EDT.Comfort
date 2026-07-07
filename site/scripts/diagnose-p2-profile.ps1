# Reads EDT p2 profile and bundles.info for tormozit.comfort / feature.group.
param(
    [string]$EdtHome = 'C:\Program Files\1C\1CE\components\1c-edt-2025.2.3+30-x86_64',
    [string]$Label = 'snapshot'
)

$ErrorActionPreference = 'Stop'

function Find-TormozitInText {
    param([string]$Text, [string]$Source)
    $patterns = @(
        'tormozit\.comfort\.feature\.feature\.group',
        'tormozit\.comfort\.feature',
        'tormozit\.comfort'
    )
    $found = @()
    foreach ($p in $patterns) {
        if ($Text -match $p) { $found += $p }
    }
    if ($found.Count -eq 0) {
        Write-Host "  [$Source] tormozit: NONE"
    }
    else {
        Write-Host "  [$Source] found: $($found -join ', ')"
        $rx = [regex]'tormozit[^\s<''"]{0,80}'
        $uniq = $rx.Matches($Text) | ForEach-Object { $_.Value } | Select-Object -Unique | Select-Object -First 8
        foreach ($m in $uniq) { Write-Host "    $m" }
    }
}

Write-Host "=== p2 diagnose [$Label] ==="
Write-Host "EDT: $EdtHome"
Write-Host ""

$bundlesInfo = Join-Path $EdtHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
if (Test-Path -LiteralPath $bundlesInfo) {
    $bi = Get-Item -LiteralPath $bundlesInfo
    Write-Host "bundles.info: $($bi.FullName)"
    Write-Host "  mtime: $($bi.LastWriteTime)"
    $biText = Get-Content -LiteralPath $bundlesInfo -Raw
    $lines = Select-String -InputObject $biText -Pattern '^tormozit' -AllMatches
    if ($lines) {
        Write-Host "  lines:"
        $lines.Line | ForEach-Object { Write-Host "    $_" }
    }
    else {
        Write-Host "  tormozit lines: 0"
    }
}
else {
    Write-Host "bundles.info: NOT FOUND"
}

Write-Host ""
$profDir = Join-Path $EdtHome 'p2\org.eclipse.equinox.p2.engine\profileRegistry\DefaultProfile.profile'
if (-not (Test-Path -LiteralPath $profDir)) {
    Write-Host "profile dir: NOT FOUND $profDir"
    exit 0
}

$latest = Get-ChildItem -LiteralPath $profDir -Filter '*.profile.gz' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $latest) {
    Write-Host "profile.gz: no files"
    exit 0
}

Write-Host "profile.gz: $($latest.Name)  mtime: $($latest.LastWriteTime)"
$outXml = Join-Path $env:TEMP ("comfort_prof_" + $Label + '.xml')
$fs = [IO.File]::OpenRead($latest.FullName)
$gz = New-Object IO.Compression.GZipStream($fs, [IO.Compression.CompressionMode]::Decompress)
$sw = New-Object IO.StreamWriter($outXml, $false, [Text.Encoding]::UTF8)
$sr = New-Object IO.StreamReader($gz, [Text.Encoding]::UTF8)
$text = $sr.ReadToEnd()
$sw.Write($text)
$sw.Close(); $sr.Close(); $gz.Close(); $fs.Close()
$sizeMb = [math]::Round((Get-Item -LiteralPath $outXml).Length / 1MB, 2)
Write-Host ('  unpacked: ' + $outXml + ' sizeMiB=' + $sizeMb)
Find-TormozitInText -Text $text -Source 'profile.gz'

$prefs = Join-Path $profDir '.data\.settings\org.eclipse.equinox.p2.metadata.repository.prefs'
if (Test-Path -LiteralPath $prefs) {
    Write-Host ""
    Write-Host "metadata repos (tormozit/github):"
    $repoHits = Select-String -Path $prefs -Pattern 'tormozit|EDT\.Comfort' -SimpleMatch
    if ($repoHits) {
        $repoHits | ForEach-Object { Write-Host "  $($_.Line)" }
    }
    else {
        Write-Host "  (no tormozit/EDT.Comfort entries)"
    }
}

Write-Host ""
