# Install Eclipse Application No Comfort (EDT without Comfort plugin).
# Does not touch main Eclipse Application. Close Eclipse PDE first!
$ErrorActionPreference = "Stop"

$runtimeData = "C:/VC/runtime-EclipseApplication"

function Test-BundlesInfoNoBom([string]$Path) {
    $head = [System.IO.File]::ReadAllBytes($Path)
    if ($head.Length -ge 3 -and $head[0] -eq 0xEF -and $head[1] -eq 0xBB -and $head[2] -eq 0xBF) {
        throw "bundles.info has UTF-8 BOM at $Path - run build-osgi-no-comfort.ps1 first"
    }
}

function Test-NoComfortLaunch([string]$Path) {
    $text = [System.IO.File]::ReadAllText($Path)
    if ($text -match 'NoComfort') {
        throw "launch still contains NoComfort: $Path"
    }
    if ($text -notmatch 'key="location" value="\$\{workspace_loc\}/\.\./runtime-EclipseApplication"') {
        throw "launch location is not runtime-EclipseApplication: $Path"
    }
    if ($text -notmatch "-data $([regex]::Escape($runtimeData))") {
        throw "launch PROGRAM_ARGUMENTS missing -data $runtimeData : $Path"
    }
}

$eclipse = Get-Process -Name "eclipse*" -ErrorAction SilentlyContinue
if ($eclipse) {
    Write-Host "Stopping eclipse (PID $($eclipse.Id -join ','))..."
    $eclipse | Stop-Process -Force
    Start-Sleep -Seconds 2
}

$here = $PSScriptRoot
$wsOsgi = "C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\Eclipse Application No Comfort"
$wsLaunch = "C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.debug.core\.launches\Eclipse Application No Comfort.launch"
$bakOsgi = Join-Path $here "backup\osgi-no-comfort"
$bakLaunch = Join-Path $here "backup\Eclipse Application No Comfort.launch.bak"
$bundlesSrc = "$bakOsgi\org.eclipse.equinox.simpleconfigurator\bundles.info"
$bundlesDst = "$wsOsgi\org.eclipse.equinox.simpleconfigurator\bundles.info"

if (-not (Test-Path $bundlesSrc)) {
    throw "Missing backup: $bundlesSrc - run build-osgi-no-comfort.ps1 first"
}
if (-not (Test-Path $bakLaunch)) {
    throw "Missing backup: $bakLaunch"
}

Test-BundlesInfoNoBom $bundlesSrc
Test-NoComfortLaunch $bakLaunch

cmd /c "attrib -R `"$bakOsgi\*`" /S /D" | Out-Null
cmd /c "attrib -R `"$wsOsgi\*`" /S /D" 2>$null | Out-Null

New-Item -ItemType Directory -Force -Path "$wsOsgi\org.eclipse.equinox.simpleconfigurator" | Out-Null
Copy-Item "$bakOsgi\config.ini" "$wsOsgi\config.ini" -Force
Copy-Item "$bakOsgi\dev.properties" "$wsOsgi\dev.properties" -Force
Copy-Item $bundlesSrc $bundlesDst -Force

Test-BundlesInfoNoBom $bundlesDst

$expected = (Get-Item $bundlesSrc).Length
$actual = (Get-Item $bundlesDst).Length
if ($actual -ne $expected) {
    throw "bundles.info: $actual bytes, expected $expected"
}

Copy-Item $bakLaunch $wsLaunch -Force
$launch = Get-Content $wsLaunch -Raw
$launch = $launch -replace 'runtime-EclipseApplication-NoComfort', 'runtime-EclipseApplication'
$launch = $launch -replace 'key="clearConfig" value="true"', 'key="clearConfig" value="false"'
$launch = $launch -replace 'key="askclear" value="true"', 'key="askclear" value="false"'
$launch = $launch -replace 'key="generateProfile" value="true"', 'key="generateProfile" value="false"'
$launch = $launch -replace 'key="pde.generated.config" value="false"', 'key="pde.generated.config" value="true"'
$launch = $launch -replace 'key="automaticAdd" value="true"', 'key="automaticAdd" value="false"'
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($wsLaunch, $launch, $utf8NoBom)

Test-NoComfortLaunch $wsLaunch

attrib +R "$bundlesDst"
attrib +R "$wsOsgi\config.ini"

Write-Host "OK: Eclipse Application No Comfort - bundles.info $actual bytes, BOM: no"
Write-Host "OK: runtime -data $runtimeData"
Write-Host "Restart PDE, then Run -> Eclipse Application No Comfort"
