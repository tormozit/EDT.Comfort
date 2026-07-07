# Headless p2 director: install Comfort from one repo and inspect profile.
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('zip', 'github-composite', 'github-direct')]
    [string]$Source,

    [string]$EdtHome = 'C:\Program Files\1C\1CE\components\1c-edt-2025.2.3+30-x86_64',
    [string]$ZipPath = 'C:\VC\EDT.Comfort\site\Comfort.zip',
    [string]$RepoRoot = 'https://tormozit.github.io/EDT.Comfort',
    [string]$Version = '1.0.0.20',
    [switch]$UninstallFirst
)

$ErrorActionPreference = 'Stop'
$launcher = Join-Path $EdtHome '1cedt.exe'
$configDir = Join-Path $EdtHome 'configuration'
$diag = Join-Path $PSScriptRoot 'diagnose-p2-profile.ps1'
$logDir = 'C:\VC\EDT.Comfort\.tmp\p2-diag'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$repo = switch ($Source) {
    'zip' { 'file:/' + $ZipPath.Replace('\', '/') + '!/' }
    'github-composite' { $RepoRoot + '/' }
    'github-direct' { $RepoRoot + '/' + $Version + '/' }
}
$label = $Source
$logPath = Join-Path $logDir ('director-' + $label + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')

if (-not (Test-Path -LiteralPath $launcher)) {
    Write-Error ('EDT launcher not found: ' + $launcher)
}

function Invoke-Director {
    param([string[]]$ExtraArgs, [string]$LogFile)
    $dirArgs = @(
        '-nosplash',
        '-consoleLog',
        '-configuration', $configDir,
        '-application', 'org.eclipse.equinox.p2.director',
        '-destination', $EdtHome,
        '-profile', 'DefaultProfile'
    ) + $ExtraArgs
    Write-Host ('>> ' + $launcher + ' ' + ($dirArgs -join ' '))
    $out = & $launcher @dirArgs 2>&1
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines($LogFile, @($out | ForEach-Object { "$_" }), $utf8)
    $out | ForEach-Object { Write-Host $_ }
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        Write-Warning ('director exit code: ' + $LASTEXITCODE + ' log=' + $LogFile)
    }
    return $out
}

Write-Host ('=== p2 install test: ' + $label + ' ===')
Write-Host ('repository: ' + $repo)
Write-Host ''

if ($UninstallFirst) {
    Write-Host '--- uninstall (if present) ---'
    Invoke-Director -ExtraArgs @(
        '-repository', $repo,
        '-uninstallIU', 'tormozit.comfort.feature.feature.group'
    ) -LogFile ($logPath + '.uninstall.log') | Out-Null
}

Write-Host '--- before ---'
& $diag -EdtHome $EdtHome -Label ('before-' + $label)

Write-Host ''
Write-Host '--- install ---'
$installOut = Invoke-Director -ExtraArgs @(
    '-repository', $repo,
    '-installIU', 'tormozit.comfort.feature.feature.group'
) -LogFile $logPath

$installText = $installOut | Out-String
$failed = $installText -match 'ERROR|Unable to|Cannot install|No solution found'
if ($failed) {
    Write-Host ''
    Write-Host ('INSTALL FAILED see ' + $logPath) -ForegroundColor Red
}

Write-Host ''
Write-Host '--- after ---'
& $diag -EdtHome $EdtHome -Label ('after-' + $label)

Write-Host ''
Write-Host ('Log: ' + $logPath)
