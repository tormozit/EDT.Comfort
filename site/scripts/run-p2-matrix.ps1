# Runs A/B/C install matrix (needs elevated PowerShell if EDT is under Program Files).
# Usage: right-click PowerShell -> Run as administrator, then:
#   powershell -NoProfile -ExecutionPolicy Bypass -File site\scripts\run-p2-matrix.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$diagDir = Join-Path $root '.tmp\p2-diag'
New-Item -ItemType Directory -Force -Path $diagDir | Out-Null
$report = Join-Path $diagDir 'matrix-report.txt'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Write-Report {
    param([string]$Line)
    Write-Host $Line
    [IO.File]::AppendAllText($report, $Line + [Environment]::NewLine, $utf8)
}

if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report -Force }
Write-Report ('=== p2 matrix ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ===')
Write-Report ''

$edtHome = 'C:\Program Files\1C\1CE\components\1c-edt-2025.2.3+30-x86_64'
$cfgUri = 'file:/C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64/configuration'
$destUri = 'file:/C:/Program Files/1C/1CE/components/1c-edt-2025.2.3+30-x86_64/'
$launcher = Join-Path $edtHome '1cedt.exe'
$diag = Join-Path $PSScriptRoot 'diagnose-p2-profile.ps1'
$iu = 'tormozit.comfort.feature.feature.group'

$sources = @(
    @{ Name = 'A-zip'; Repo = 'file:/C:/VC/EDT.Comfort/site/Comfort.zip!/' },
    @{ Name = 'B-github-composite'; Repo = 'https://tormozit.github.io/EDT.Comfort/' },
    @{ Name = 'C-github-direct'; Repo = 'https://tormozit.github.io/EDT.Comfort/1.0.0.20/' }
)

# write test
$pluginsDir = Join-Path $edtHome 'plugins'
$writable = $true
try {
    $probe = Join-Path $pluginsDir '_comfort_probe.tmp'
    [IO.File]::WriteAllText($probe, 'x')
    Remove-Item -LiteralPath $probe -Force
}
catch {
    $writable = $false
}
Write-Report ('EDT plugins writable: ' + $writable)
if (-not $writable) {
    Write-Report 'WARNING: run this script as Administrator (EDT under Program Files).'
}
Write-Report ''

& $diag -EdtHome $edtHome -Label 'baseline' | ForEach-Object { Write-Report $_ }

foreach ($src in $sources) {
    Write-Report ''
    Write-Report ('--- test ' + $src.Name + ' repo=' + $src.Repo + ' ---')

    $unlog = Join-Path $diagDir ('director-' + $src.Name + '-uninstall.log')
    & $launcher -nosplash -consoleLog -configuration $cfgUri `
        -application org.eclipse.equinox.p2.director -destination $destUri -profile DefaultProfile `
        -repository $src.Repo -uninstallIU $iu 2>&1 |
        Out-File -FilePath $unlog -Encoding utf8

    $log = Join-Path $diagDir ('director-' + $src.Name + '-install.log')
    & $launcher -nosplash -consoleLog -configuration $cfgUri `
        -application org.eclipse.equinox.p2.director -destination $destUri -profile DefaultProfile `
        -repository $src.Repo -installIU $iu 2>&1 |
        Out-File -FilePath $log -Encoding utf8

    & $diag -EdtHome $edtHome -Label ('after-' + $src.Name) | ForEach-Object { Write-Report $_ }
    Write-Report ('install log: ' + $log)
}

Write-Report ''
Write-Report ('Report saved: ' + $report)
