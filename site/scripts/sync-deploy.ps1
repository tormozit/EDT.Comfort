param(
    [string]$SiteDir = (Split-Path $PSScriptRoot -Parent),
    [string]$Root = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
)

$ErrorActionPreference = 'Stop'
$deployDir = Join-Path $Root 'deploy'

function Get-OriginUrl {
    param([string]$RepoRoot)
    $url = git -C $RepoRoot config --get remote.origin.url 2>$null
    if (-not $url) {
        Write-Error "remote.origin.url not found. Clone EDT.Comfort with origin configured."
    }
    return $url.Trim()
}

function Get-VersionDirs {
    param([string]$Dir)
    if (-not (Test-Path -LiteralPath $Dir)) {
        return @()
    }
    Get-ChildItem -LiteralPath $Dir -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
        ForEach-Object { $_.Name } |
        Sort-Object -Descending
}

function Show-DeploySummary {
    param([string]$Dir)
    $versions = Get-VersionDirs -Dir $Dir
    Write-Host 'Version folders on disk:'
    if ($versions.Count -eq 0) {
        Write-Host '  (none)'
    }
    else {
        foreach ($v in $versions) {
            Write-Host "  $v"
        }
    }
    $rootContent = Join-Path $Dir 'content.xml'
    if (Test-Path -LiteralPath $rootContent) {
        $content = Get-Content -LiteralPath $rootContent -Raw -Encoding UTF8
        $m = [regex]::Match($content, "id='tormozit\.comfort' version='([^']+)'")
        if ($m.Success) {
            Write-Host "Root p2 (latest): $($m.Groups[1].Value)"
        }
    }
    elseif (Test-Path -LiteralPath (Join-Path $Dir 'compositeContent.xml')) {
        Write-Host 'WARN: gh-pages still uses composite root (republish with resanitize)'
    }
}

$origin = Get-OriginUrl -RepoRoot $Root

if (Test-Path -LiteralPath (Join-Path $deployDir '.git')) {
    Write-Host 'Updating existing deploy/ from origin gh-pages...'
    git -C $deployDir fetch origin gh-pages
    git -C $deployDir reset --hard origin/gh-pages
}
else {
    if (Test-Path -LiteralPath $deployDir) {
        Write-Host 'Removing non-git deploy/ before clone...'
        Remove-Item -LiteralPath $deployDir -Recurse -Force
    }
    Write-Host "Cloning gh-pages into deploy/ from $origin ..."
    git clone --branch gh-pages --single-branch $origin $deployDir
}

Show-DeploySummary -Dir $deployDir
Write-Host 'Done. deploy/ mirrors gh-pages (do not commit deploy/ to main).'
