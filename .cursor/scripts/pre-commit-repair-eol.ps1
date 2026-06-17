# Git pre-commit: repair CR+CRLF / double-spaced EOL on staged text files.
param(
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$repairScript = Join-Path $PSScriptRoot 'repair-double-eol.ps1'
$extPattern = '\.(java|xml|properties|mdc|ps1|md|txt|launch)$'

function Test-CrCrLf {
    param([string]$FilePath)
    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) { return $false }
    $raw = [System.IO.File]::ReadAllText($FilePath)
    return $raw -match "`r`r`n"
}

$staged = @(git -C $repoRoot diff --cached --name-only --diff-filter=ACM)
$relPaths = @($staged | Where-Object { $_ -match $extPattern })
if ($relPaths.Count -eq 0) { exit 0 }

$fullPaths = @($relPaths | ForEach-Object { Join-Path $repoRoot $_ })

if ($CheckOnly) {
    $bad = @($fullPaths | Where-Object { Test-CrCrLf $_ })
    if ($bad.Count -gt 0) {
        Write-Host "pre-commit EOL check failed ($($bad.Count) file(s) with CR+CRLF):" -ForegroundColor Red
        foreach ($f in $bad) {
            Write-Host "  $($f.Replace($repoRoot + '\', ''))"
        }
        Write-Host "Run: powershell -NoProfile -File `"$repairScript`" -Path `"$repoRoot\plugin\src`""
        exit 1
    }
    exit 0
}

& powershell -NoProfile -File $repairScript -Path $fullPaths

foreach ($rel in $relPaths) {
    $full = Join-Path $repoRoot $rel
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
    git -C $repoRoot add -- $rel | Out-Null
}

$stillBad = @($fullPaths | Where-Object { Test-CrCrLf $_ })
if ($stillBad.Count -gt 0) {
    Write-Host "pre-commit EOL repair failed for:" -ForegroundColor Red
    foreach ($f in $stillBad) {
        Write-Host "  $($f.Replace($repoRoot + '\', ''))"
    }
    exit 1
}

exit 0
