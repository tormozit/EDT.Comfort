# Install git pre-commit hook for automatic EOL repair (CR+CRLF / double-spaced lines).
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$srcHook = Join-Path $PSScriptRoot 'git-hooks\pre-commit'
$dstHook = Join-Path $repoRoot '.git\hooks\pre-commit'
$marker = '# comfort-eol-repair'

if (-not (Test-Path -LiteralPath (Join-Path $repoRoot '.git\hooks') -PathType Container)) {
    throw "Not a git repository: $repoRoot"
}

if (Test-Path -LiteralPath $dstHook -PathType Leaf) {
    $existing = [System.IO.File]::ReadAllText($dstHook)
    if ($existing -match [regex]::Escape($marker)) {
        Write-Host "pre-commit hook already installed: $dstHook"
        exit 0
    }
    if ($existing.Trim().Length -gt 0) {
        $bak = "$dstHook.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Copy-Item -LiteralPath $dstHook -Destination $bak -Force
        Write-Host "Existing pre-commit backed up to: $bak"
        @"
$marker
# Previous hook (see .bak):
# $($existing -replace "`r?`n", "`n" | ForEach-Object { "# $_" })

"@ | Set-Content -LiteralPath $dstHook -Encoding utf8NoBOM -NoNewline
        Add-Content -LiteralPath $dstHook -Value ([System.IO.File]::ReadAllText($srcHook)) -Encoding utf8NoBOM
        Write-Host "Chained comfort EOL repair into: $dstHook"
        exit 0
    }
}

Copy-Item -LiteralPath $srcHook -Destination $dstHook -Force
Write-Host "Installed pre-commit hook: $dstHook"
Write-Host "On each commit staged .java/.xml/.properties/.mdc/.ps1/.md/.txt/.launch are auto-repaired."
