# Scan repository for EOL damage: CR+CRLF and LF/mixed (non-CRLF). Exit 1 if any found.
param(
    [string[]]$Path = @()
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$extPattern = '^\.(java|xml|properties|mdc|ps1|md|txt|launch)$'
$skipPattern = '\\(\.git|plugin\\bin|\.tmp|node_modules)\\'

function Get-ScanFiles {
    param([string[]]$InputPath)
    if ($InputPath.Count -gt 0) {
        foreach ($p in $InputPath) {
            $full = if ([System.IO.Path]::IsPathRooted($p)) { $p } else { Join-Path $repoRoot $p }
            if (Test-Path -LiteralPath $full -PathType Leaf) {
                Get-Item -LiteralPath $full
            }
            elseif (Test-Path -LiteralPath $full -PathType Container) {
                Get-ChildItem -LiteralPath $full -Recurse -File |
                    Where-Object { $_.Extension -match $extPattern -and $_.FullName -notmatch $skipPattern }
            }
        }
        return
    }
    foreach ($rel in @('plugin\src', 'plugin\plugin.xml', 'plugin\META-INF', '.cursor', 'launch', 'site')) {
        $full = Join-Path $repoRoot $rel
        if (-not (Test-Path -LiteralPath $full)) { continue }
        if ((Get-Item -LiteralPath $full).PSIsContainer) {
            Get-ChildItem -LiteralPath $full -Recurse -File |
                Where-Object { $_.Extension -match $extPattern -and $_.FullName -notmatch $skipPattern }
        }
        else {
            Get-Item -LiteralPath $full
        }
    }
}

function Get-EolIssues {
    param([string]$Raw)
    $issues = @()
    $crcrlf = ([regex]::Matches($Raw, "`r`r`n")).Count
    if ($crcrlf -gt 0) { $issues += "CR+CRLF=$crcrlf" }
    $loneLf = ([regex]::Matches($Raw, '(?<!\r)\n')).Count
    if ($loneLf -gt 0) { $issues += "LF=$loneLf" }
    $bareCr = ([regex]::Matches($Raw, '\r(?!\n)')).Count
    if ($bareCr -gt 0) { $issues += "bareCR=$bareCr" }
    return $issues
}

$hits = @()
foreach ($file in @(Get-ScanFiles -InputPath $Path | Sort-Object FullName -Unique)) {
    $raw = [System.IO.File]::ReadAllText($file.FullName)
    $issues = @(Get-EolIssues -Raw $raw)
    if ($issues.Count -gt 0) {
        $hits += [PSCustomObject]@{
            Path = $file.FullName.Replace($repoRoot + '\', '')
            Issues = ($issues -join ', ')
        }
    }
}

if ($hits.Count -eq 0) {
    Write-Host 'OK: no EOL damage found (CR+CRLF / LF / bare CR).'
    exit 0
}

Write-Host "FAIL: $($hits.Count) file(s) with EOL issues:" -ForegroundColor Red
$hits | Format-Table -AutoSize
Write-Host "Repair: powershell -NoProfile -File `"$PSScriptRoot\repair-double-eol.ps1`" -Path `"$repoRoot\plugin\src`""
exit 1
