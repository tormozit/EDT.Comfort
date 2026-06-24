param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Republish', 'Release')]
    [string]$Mode
)

$ErrorActionPreference = 'Stop'
$SiteDir = Split-Path $PSScriptRoot -Parent
$Root = Split-Path $SiteDir -Parent
$versionFile = Join-Path $SiteDir 'version.txt'

function Set-Utf8NoBomContent {
    param(
        [string]$Path,
        [string[]]$ContentLines
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $ContentLines, $utf8NoBom)
}

function Read-ComfortRelease {
    $release = $null
    Get-Content -LiteralPath $versionFile -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(?:#.*)?$') {
            $release = $Matches[1]
        }
    }
    if (-not $release) {
        Write-Error "comfort.release not found in $versionFile"
    }
    return $release
}

function Invoke-BumpRelease {
    $release = $null
    $newRelease = $null
    $lines = Get-Content -LiteralPath $versionFile -Encoding UTF8
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(#.*)?$') {
            $release = $Matches[1]
            $suffix = ''
            if ($Matches[2]) { $suffix = " $($Matches[2].Trim())" }
            $parts = $release.Split('.')
            if ($parts.Count -ne 4) {
                Write-Error "comfort.release must have 4 segments (e.g. 1.0.0.13), got: $release"
            }
            $last = [int]$parts[3]
            $parts[3] = ($last + 1).ToString()
            $newRelease = $parts -join '.'
            $lines[$i] = "comfort.release=$newRelease$suffix"
            break
        }
    }
    if (-not $release) {
        Write-Error "comfort.release not found in version.txt"
    }
    Set-Utf8NoBomContent -Path $versionFile -ContentLines $lines
    Write-Host "Bumped comfort.release: $release -> $newRelease"
}

function Read-PdeLocalConfig {
    $localProps = Join-Path $PSScriptRoot 'pde-local.properties'
    $config = @{}
    if (-not (Test-Path -LiteralPath $localProps)) {
        return $config
    }
    Get-Content -LiteralPath $localProps -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)$') {
            $config[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $config
}

function Get-EdtLauncherExe {
    param([string]$EdtHome)
    foreach ($name in @('1cedt.exe', 'eclipse.exe')) {
        $path = Join-Path $EdtHome $name
        if (Test-Path -LiteralPath $path) {
            return $path
        }
    }
    return $null
}

function Find-EdtHomeUnderComponents {
    param([string]$ComponentsDir)
    if (-not (Test-Path -LiteralPath $ComponentsDir)) {
        return $null
    }
    $found = Get-ChildItem -Path $ComponentsDir -Filter '1c-edt-*' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '1c-edt-start-*' } |
        Where-Object { Get-EdtLauncherExe -EdtHome $_.FullName } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($found) {
        return $found.FullName
    }
    return $null
}

function Resolve-EdtHome {
    $config = Read-PdeLocalConfig
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:COMFORT_EDT_HOME) {
        [void]$candidates.Add($env:COMFORT_EDT_HOME.Trim().TrimEnd('\'))
    }
    if ($config['COMFORT_EDT_HOME']) {
        [void]$candidates.Add($config['COMFORT_EDT_HOME'].Trim().TrimEnd('\'))
    }
    foreach ($home in $candidates) {
        if (Get-EdtLauncherExe -EdtHome $home) {
            return $home
        }
    }
    foreach ($components in @(
            'C:\Program Files\1C\1CE\components',
            'C:\Program Files (x86)\1C\1CE\components'
        )) {
        $found = Find-EdtHomeUnderComponents -ComponentsDir $components
        if ($found) {
            return $found
        }
    }
    Write-Error @"
COMFORT_EDT_HOME not found (1cedt.exe or eclipse.exe).
Set environment variable, for example:
  set COMFORT_EDT_HOME=C:\Program Files\1C\1CE\components\1c-edt-2025.2.3+30-x86_64
Or copy site/scripts/pde-local.properties.example to pde-local.properties.
"@
}

function Resolve-PdeWorkspace {
    $config = Read-PdeLocalConfig
    if ($env:COMFORT_PDE_WS) {
        return $env:COMFORT_PDE_WS.Trim().TrimEnd('\')
    }
    if ($config['COMFORT_PDE_WS']) {
        return $config['COMFORT_PDE_WS'].Trim().TrimEnd('\')
    }
    return 'C:\VC\EDT-plugin-WS'
}

function Stop-EclipseProcesses {
    foreach ($procName in @('eclipse', '1cedt', '1cedtc')) {
        $procs = Get-Process -Name "$procName*" -ErrorAction SilentlyContinue
        if ($procs) {
            Write-Host "Stopping $procName (PID $($procs.Id -join ','))..."
            $procs | Stop-Process -Force
        }
    }
    Start-Sleep -Seconds 2
}

function Test-ExternalExitCode {
    param([string]$Context)
    $exitCode = $LASTEXITCODE
    if ($null -ne $exitCode -and $exitCode -ne 0) {
        Write-Error "$Context failed (exit code $exitCode)."
    }
}

function Assert-PdeSiteArtifacts {
    param(
        [string]$SiteDirectory,
        [datetime]$NotBefore
    )
    $featuresDir = Join-Path $SiteDirectory 'features'
    $pluginsDir = Join-Path $SiteDirectory 'plugins'
    foreach ($path in @(
            (Join-Path $SiteDirectory 'content.jar'),
            (Join-Path $SiteDirectory 'artifacts.jar')
        )) {
        if (-not (Test-Path -LiteralPath $path)) {
            Write-Error "PDE build output missing: $path"
        }
        if ((Get-Item -LiteralPath $path).LastWriteTime -lt $NotBefore) {
            Write-Error "PDE build output not updated: $path (run clean.bat + Build All in PDE if headless failed)"
        }
    }
    $featureJar = Get-ChildItem -LiteralPath $featuresDir -Filter 'tormozit.comfort.feature_*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime |
        Select-Object -Last 1
    if (-not $featureJar) {
        Write-Error "No feature jar in $featuresDir"
    }
    if ($featureJar.Name -match '-qualifier\.jar$') {
        Write-Error "Feature jar still has literal qualifier: $($featureJar.Name)"
    }
    if ($featureJar.LastWriteTime -lt $NotBefore) {
        Write-Error "Feature jar not updated: $($featureJar.Name)"
    }
    $pluginJar = Get-ChildItem -LiteralPath $pluginsDir -Filter 'tormozit.comfort_*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime |
        Select-Object -Last 1
    if (-not $pluginJar) {
        Write-Error "No plugin jar in $pluginsDir"
    }
    if ($pluginJar.LastWriteTime -lt $NotBefore) {
        Write-Error "Plugin jar not updated: $($pluginJar.Name)"
    }
    Write-Host "PDE site artifacts OK: $($featureJar.Name), $($pluginJar.Name)"
}

function Invoke-PdeBuild {
    param(
        [string]$RepoRoot,
        [string]$SiteDirectory
    )
    $edtHome = Resolve-EdtHome
    $ws = Resolve-PdeWorkspace
    $launcherExe = Get-EdtLauncherExe -EdtHome $edtHome
    if (-not $launcherExe) {
        Write-Error "EDT launcher not found in $edtHome (expected 1cedt.exe or eclipse.exe)"
    }
    if (-not (Test-Path -LiteralPath $ws)) {
        Write-Error "PDE workspace not found: $ws"
    }
    $buildfile = Join-Path $RepoRoot 'sync-version-ant.xml'
    if (-not (Test-Path -LiteralPath $buildfile)) {
        Write-Error "Ant buildfile not found: $buildfile"
    }
    $logDir = Join-Path $RepoRoot '.tmp'
    if (-not (Test-Path -LiteralPath $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    }
    $logPath = Join-Path $logDir 'pde-headless.log'
    $buildStarted = Get-Date
    Write-Host ''
    Write-Host 'PDE headless build (equivalent to Build All on site)...'
    Write-Host "  EDT: $edtHome"
    Write-Host "  Launcher: $launcherExe"
    Write-Host "  Workspace: $ws"
    Write-Host "  Log: $logPath"
    $buildOutput = & $launcherExe -nosplash -consoleLog `
        -application org.eclipse.ant.core.antRunner `
        -data $ws `
        -buildfile $buildfile `
        "-Dbasedir=$RepoRoot" `
        buildSite 2>&1
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($logPath, @($buildOutput | ForEach-Object { "$_" }), $utf8NoBom)
    $buildOutput | ForEach-Object { Write-Host $_ }
    Test-ExternalExitCode -Context 'PDE headless build'
    $logText = ($buildOutput | ForEach-Object { "$_" }) -join "`n"
    if ($logText -match 'BUILD FAILED') {
        Write-Error "PDE headless build failed. See $logPath"
    }
    if ($logText -notmatch 'BUILD SUCCESSFUL') {
        Write-Error "PDE headless build did not report BUILD SUCCESSFUL. See $logPath"
    }
    if ($logText -match 'Total time:\s*0 seconds') {
        Write-Host 'Warning: Ant reported Total time: 0 seconds — checking site artifacts...'
    }
    Assert-PdeSiteArtifacts -SiteDirectory $SiteDirectory -NotBefore $buildStarted.AddSeconds(-5)
}

function Get-BuiltFeatureVersion {
    param([string]$SiteDirectory)
    $featuresDir = Join-Path $SiteDirectory 'features'
    $jar = Get-ChildItem -LiteralPath $featuresDir -Filter 'tormozit.comfort.feature_*.jar' -File |
        Sort-Object Name |
        Select-Object -Last 1
    if (-not $jar) {
        Write-Error "No feature jar in $featuresDir"
    }
    $name = $jar.BaseName
    if ($name -match '^tormozit\.comfort\.feature_(.+)$') {
        return $Matches[1]
    }
    Write-Error "Unexpected feature jar name: $($jar.Name)"
}

Write-Host "=== $($Mode) ==="

if ($Mode -eq 'Release') {
    Invoke-BumpRelease
}

Stop-EclipseProcesses
Invoke-PdeBuild -RepoRoot $Root -SiteDirectory $SiteDir

$restoreScript = Join-Path $Root 'launch\restore-main.ps1'
if (-not (Test-Path -LiteralPath $restoreScript)) {
    Write-Error "restore-main.ps1 not found: $restoreScript"
}
Write-Host ''
Write-Host 'Sync PDE OSGi profile (launch\restore-main.ps1)...'
& powershell -NoProfile -ExecutionPolicy Bypass -File $restoreScript
Test-ExternalExitCode -Context 'restore-main.ps1'

. (Join-Path $Root 'launch\comfort-osgi-version.ps1')
$expectedQualifier = Get-ComfortExpectedQualifier
$wsOsgi = $ComfortWsOsgi
$wsBundles = Join-Path $wsOsgi $ComfortBundlesInfoRel
$wsDev = Join-Path $wsOsgi 'dev.properties'
Assert-ComfortOsgiVersionSync -Context 'prepare-release post-restore' `
    -ExpectedQualifier $expectedQualifier `
    -BundlesInfoPath $wsBundles `
    -DevPropertiesPath $wsDev

$release = Read-ComfortRelease
$built = Get-BuiltFeatureVersion -SiteDirectory $SiteDir
$actionsMode = if ($Mode -eq 'Release') { 'new' } else { 'republish' }

Write-Host ''
Write-Host "Готово: comfort.release=$release, сборка $built"
Write-Host "Закоммить:"
Write-Host "  site/features/ site/plugins/ site/content.jar site/artifacts.jar"
if ($Mode -eq 'Release') {
    Write-Host "  plugin/META-INF/MANIFEST.MF feature/feature.xml pom.xml launch/backup/osgi (если изменились)"
}
Write-Host "GitHub Actions -> Publish p2 site -> mode: $actionsMode"
Write-Host "Опционально после Actions: site/scripts/sync-deploy.bat"
Write-Host "PDE OSGi profile уже синхронизирован (restore-main.ps1)"
