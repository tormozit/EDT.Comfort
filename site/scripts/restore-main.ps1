# Восстановление Eclipse Application. Закройте Eclipse PDE!
$ErrorActionPreference = 'Stop'

$eclipse = Get-Process -Name 'eclipse*' -ErrorAction SilentlyContinue
if ($eclipse) {
    Write-Host "Останавливаю eclipse (PID $($eclipse.Id -join ','))..."
    $eclipse | Stop-Process -Force
    Start-Sleep -Seconds 2
}

. (Join-Path $PSScriptRoot 'comfort-osgi-version.ps1')
$comfortQualifier = Get-ComfortExpectedQualifier

$wsOsgi = $ComfortWsOsgi
$wsLaunch = 'C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.debug.core\.launches\Eclipse Application.launch'
$bakOsgi = Join-Path $ComfortRepoRoot 'launch\backup\osgi'
$bakLaunch = Join-Path $ComfortRepoRoot 'launch\backup\Eclipse Application.launch.bak'
$bundlesSrc = Join-Path $bakOsgi $ComfortBundlesInfoRel
$bundlesDst = Join-Path $wsOsgi $ComfortBundlesInfoRel
$devDst = Join-Path $wsOsgi 'dev.properties'

cmd /c "attrib -R `"$bakOsgi\*`" /S /D" | Out-Null
cmd /c "attrib -R `"$wsOsgi\*`" /S /D" | Out-Null

New-Item -ItemType Directory -Force -Path (Split-Path $bundlesDst -Parent) | Out-Null
Copy-Item "$bakOsgi\config.ini" "$wsOsgi\config.ini" -Force
Copy-Item "$bakOsgi\dev.properties" $devDst -Force
Copy-Item $bundlesSrc $bundlesDst -Force

Update-PdeOsgiComfortVersion -BundlesInfoPath $bundlesSrc -DevPropertiesPath "$bakOsgi\dev.properties" -Qualifier $comfortQualifier
Update-PdeOsgiComfortVersion -BundlesInfoPath $bundlesDst -DevPropertiesPath $devDst -Qualifier $comfortQualifier
Write-Host "tormozit.comfort -> $comfortQualifier (backup + workspace)"

$expected = (Get-Item $bundlesSrc).Length
$actual = (Get-Item $bundlesDst).Length
if ($actual -ne $expected) {
    throw "bundles.info: $actual байт, нужно $expected"
}

Copy-Item $bakLaunch $wsLaunch -Force
$launch = Get-Content $wsLaunch -Raw
$launch = $launch -replace 'key="clearConfig" value="true"', 'key="clearConfig" value="false"'
$launch = $launch -replace 'key="askclear" value="true"', 'key="askclear" value="false"'
$launch = $launch -replace 'key="generateProfile" value="true"', 'key="generateProfile" value="false"'
$launch = $launch -replace 'key="pde.generated.config" value="true"', 'key="pde.generated.config" value="false"'
Set-Content -Path $wsLaunch -Value $launch -NoNewline -Encoding UTF8
cmd /c "attrib -R `"$wsLaunch`"" | Out-Null

attrib +R $bundlesDst
attrib +R "$wsOsgi\config.ini"
cmd /c "attrib -R `"$devDst`"" | Out-Null

Remove-Item 'C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.debug.core\.launches\Comfort EDT Experimental.launch' -Force -ErrorAction SilentlyContinue
Remove-Item 'C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\Comfort EDT Experimental' -Recurse -Force -ErrorAction SilentlyContinue

Assert-ComfortOsgiVersionSync -Context 'restore-main workspace' `
    -ExpectedQualifier $comfortQualifier `
    -BundlesInfoPath $bundlesDst `
    -DevPropertiesPath $devDst

$bakDev = Join-Path $bakOsgi 'dev.properties'
Assert-ComfortOsgiVersionSync -Context 'restore-main backup' `
    -ExpectedQualifier $comfortQualifier `
    -BundlesInfoPath $bundlesSrc `
    -DevPropertiesPath $bakDev

Write-Host 'OK: bundles.info' $actual 'bytes; clearConfig=false; generateProfile=false; pde.generated.config=false'
Write-Host 'bundles.info and config.ini are read-only; dev.properties must stay writable for PDE launch.'
Write-Host 'Before Run: site\scripts\assert-comfort-osgi.ps1'
