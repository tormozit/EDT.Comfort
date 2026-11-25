param(
    [string[]]$Profiles,
    [string]$DependencyProfile,
    [string]$Early,
    [string]$Main,
    [string]$JavaHome = 'C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$installations = Join-Path $env:LOCALAPPDATA '1C\1cedtstart\installations'
$profileLocation = '1cedt\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'

if (!$Profiles) {
    $Profiles = @('2026.1', '2026.2') | ForEach-Object {
        Join-Path $installations "1C_EDT $_\$profileLocation"
    }
}
if (!$DependencyProfile) {
    $DependencyProfile = Join-Path $installations "1C_EDT 2026.1\$profileLocation"
}
if (!$Early) { $Early = Join-Path $repo 'plugin.early\bin' }
if (!$Main) { $Main = Join-Path $repo 'plugin\bin' }

$java = (Resolve-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe')).Path
$dependency = (Resolve-Path -LiteralPath $DependencyProfile).Path
$earlyArtifact = (Resolve-Path -LiteralPath $Early).Path
$mainArtifact = (Resolve-Path -LiteralPath $Main).Path
$auditor = Join-Path $PSScriptRoot 'weaving\WeavingAudit.java'
$registry = Join-Path $PSScriptRoot 'weaving\targets.tsv'

function Read-ProfileBundles([string]$Profile) {
    $installation = Split-Path (Split-Path (Split-Path $Profile -Parent) -Parent) -Parent
    foreach ($line in Get-Content -LiteralPath $Profile -Encoding UTF8) {
        if ($line -match '^\s*(#|$)') { continue }
        $fields = $line -split ',', 5
        if ($fields.Count -ne 5) { throw "Неверная строка в $Profile. $line" }
        $location = $fields[2] -replace '^reference:', ''
        $path = if ($location.StartsWith('file:')) {
            ([uri]($location -replace '^file:/([A-Za-z]:)', 'file:///$1')).LocalPath
        } else {
            Join-Path $installation $location
        }
        [pscustomobject]@{ Id = $fields[0]; Version = $fields[1]; Path = $path }
    }
}

function Require-Bundle($Bundles, [string]$Id) {
    $matches = @($Bundles | Where-Object Id -eq $Id)
    if ($matches.Count -ne 1 -or !(Test-Path -LiteralPath $matches[0].Path -PathType Leaf)) {
        throw "Не найден JAR бандла $Id."
    }
    return $matches[0].Path
}

$dependencyBundles = @(Read-ProfileBundles $dependency)
$osgi = Require-Bundle $dependencyBundles 'org.eclipse.osgi'
$runId = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss-fff')
$reports = Join-Path $repo ".tmp\weaving-audit\$runId"
New-Item -ItemType Directory -Path $reports -Force | Out-Null
$hasFailures = $false
$index = 0
foreach ($requestedProfile in $Profiles) {
    $index++
    $profile = (Resolve-Path -LiteralPath $requestedProfile).Path
    $targetBundles = @(Read-ProfileBundles $profile)
    $targetAsm = $targetBundles | Where-Object Id -eq 'org.objectweb.asm'
    $dependencyAsm = $dependencyBundles | Where-Object Id -eq 'org.objectweb.asm'
    $asmBundles = if ($targetAsm -and [version]$targetAsm.Version -gt [version]$dependencyAsm.Version) {
        $targetBundles
    } else {
        $dependencyBundles
    }
    $classPath = $osgi, (Require-Bundle $asmBundles 'org.objectweb.asm'),
        (Require-Bundle $asmBundles 'org.objectweb.asm.tree'),
        (Require-Bundle $asmBundles 'org.objectweb.asm.tree.analysis'),
        (Require-Bundle $asmBundles 'org.objectweb.asm.util') -join [IO.Path]::PathSeparator

    $output = Join-Path $reports "profile-$index"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $report = Join-Path $output 'audit.log'
    Write-Host "Проверка $profile"
    Set-Content -LiteralPath $report -Encoding UTF8 -Value $profile
    $javaArgs = @('-Dfile.encoding=UTF-8', '--class-path', $classPath, $auditor, $registry,
        $profile, $dependency, $earlyArtifact, $mainArtifact, $output)
    & $java @javaArgs 2>&1 | Add-Content -LiteralPath $report -Encoding UTF8
    $status = $LASTEXITCODE
    $summary = Select-String -LiteralPath $report -Pattern '^Итого:' | Select-Object -Last 1
    if ($summary) { Write-Host $summary.Line }
    Write-Host "Отчёт $report"
    if ($status -ne 0) {
        $hasFailures = $true
        Get-Content -LiteralPath $report -Encoding UTF8 |
            Where-Object { $_ -notmatch '^(OK \||early \||main \||Итого:|\s+at\s)' } |
            Select-Object -Skip 1 -First 8 | Write-Host
    }
}
if ($hasFailures) { exit 1 }
