# Восстановление Eclipse Application. Закройте Eclipse PDE!
$ErrorActionPreference = "Stop"

$eclipse = Get-Process -Name "eclipse*" -ErrorAction SilentlyContinue
if ($eclipse) {
    Write-Host "Останавливаю eclipse (PID $($eclipse.Id -join ','))..."
    $eclipse | Stop-Process -Force
    Start-Sleep -Seconds 2
}

$here = $PSScriptRoot
$repoRoot = Split-Path $here -Parent
$versionFile = Join-Path $repoRoot 'site\version.txt'
$release = $null
Get-Content -LiteralPath $versionFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*comfort\.release\s*=\s*(\d+\.\d+\.\d+\.\d+)\s*(?:#.*)?$') {
        $release = $Matches[1]
    }
}
if (-not $release) {
    throw "comfort.release not found in $versionFile"
}
$comfortQualifier = "$release-qualifier"

function Update-PdeOsgiComfortVersion {
    param(
        [string]$BundlesInfoPath,
        [string]$DevPropertiesPath,
        [string]$Qualifier
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    if (-not (Test-Path -LiteralPath $BundlesInfoPath)) {
        throw "bundles.info not found: $BundlesInfoPath"
    }
    $bundleLines = [System.IO.File]::ReadAllLines($BundlesInfoPath)
    $bundleUpdated = $false
    for ($i = 0; $i -lt $bundleLines.Count; $i++) {
        if ($bundleLines[$i] -match '^tormozit\.comfort,') {
            $bundleLines[$i] = "tormozit.comfort,$Qualifier,file:/C:/VC/EDT.Comfort/plugin/,4,false"
            $bundleUpdated = $true
            break
        }
    }
    if (-not $bundleUpdated) {
        throw "tormozit.comfort entry not found in $BundlesInfoPath"
    }
    [System.IO.File]::WriteAllLines($BundlesInfoPath, $bundleLines, $utf8NoBom)
    $devLines = @(
        '#',
        "#$(Get-Date -Format 'ddd MMM dd HH:mm:ss ''MSK'' yyyy')",
        "tormozit.comfort;$Qualifier=bin,lib/jacob.jar",
        '@ignoredot@=true',
        'tormozit.comfort=bin,lib/jacob.jar'
    )
    [System.IO.File]::WriteAllLines($DevPropertiesPath, $devLines, $utf8NoBom)
}

$wsOsgi = "C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\Eclipse Application"
$wsLaunch = "C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.debug.core\.launches\Eclipse Application.launch"
$bakOsgi = Join-Path $here "backup\osgi"
$bakLaunch = Join-Path $here "backup\Eclipse Application.launch.bak"
$bundlesSrc = "$bakOsgi\org.eclipse.equinox.simpleconfigurator\bundles.info"
$bundlesDst = "$wsOsgi\org.eclipse.equinox.simpleconfigurator\bundles.info"

cmd /c "attrib -R `"$bakOsgi\*`" /S /D" | Out-Null
cmd /c "attrib -R `"$wsOsgi\*`" /S /D" | Out-Null

New-Item -ItemType Directory -Force -Path "$wsOsgi\org.eclipse.equinox.simpleconfigurator" | Out-Null
Copy-Item "$bakOsgi\config.ini" "$wsOsgi\config.ini" -Force
Copy-Item "$bakOsgi\dev.properties" "$wsOsgi\dev.properties" -Force
Copy-Item $bundlesSrc $bundlesDst -Force

Update-PdeOsgiComfortVersion -BundlesInfoPath $bundlesSrc -DevPropertiesPath "$bakOsgi\dev.properties" -Qualifier $comfortQualifier
Update-PdeOsgiComfortVersion -BundlesInfoPath $bundlesDst -DevPropertiesPath "$wsOsgi\dev.properties" -Qualifier $comfortQualifier
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
$launch = $launch -replace 'key="pde.generated.config" value="false"', 'key="pde.generated.config" value="true"'
Set-Content -Path $wsLaunch -Value $launch -NoNewline -Encoding UTF8
cmd /c "attrib -R `"$wsLaunch`"" | Out-Null

attrib +R "$bundlesDst"
attrib +R "$wsOsgi\config.ini"

Remove-Item "C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.debug.core\.launches\Comfort EDT Experimental.launch" -Force -ErrorAction SilentlyContinue
Remove-Item "C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\Comfort EDT Experimental" -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "OK: bundles.info $actual bytes; clearConfig=false; generateProfile=false"
Write-Host "bundles.info i config.ini - read-only. Zapustite PDE, Eclipse Application."
