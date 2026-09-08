# Exercise the shipped setup EXE twice, verify profile preservation, then launch it.
$ErrorActionPreference = 'Stop'
$asterRoot = Split-Path $PSScriptRoot -Parent
$asterBuild = Join-Path $asterRoot 'build'
$asterSetup = Join-Path $asterBuild 'aster-windows-x64-setup.exe'
$asterTarget = Join-Path $env:LOCALAPPDATA 'Programs\Aster Preview'
$asterProfile = 'HKCU:\Software\JavaSoft\Prefs\io\aster\engine-preview'
$asterUninstall = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\io.aster.browser.EnginePreview_is1'
function Install-Aster([string]$asterInstaller) {
    $asterProcess = Start-Process -FilePath $asterInstaller -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-') -Wait -PassThru
    if ($asterProcess.ExitCode -ne 0) { throw "Aster setup failed: $($asterProcess.ExitCode)" }
}
$asterCompiler = Join-Path $asterBuild 'inno\ISCC.exe'
& $asterCompiler '/DAsterVersion=0.2.0-preview.2' '/DAsterNativeVersion=0.2.0.2' '/Faster-upgrade-baseline' (Join-Path $asterRoot 'packaging\windows.iss')
if ($LASTEXITCODE -ne 0) { throw 'Could not build an older-version installation fixture' }
Install-Aster (Join-Path $asterBuild 'aster-upgrade-baseline.exe')
if (-not (Test-Path -LiteralPath $asterUninstall)) { throw 'Missing per-user installed application identity' }
New-Item -Path $asterProfile -Force | Out-Null
New-ItemProperty -LiteralPath $asterProfile -Name 'upgrade-fixture' -Value 'bookmarks-and-notes-stay' -PropertyType String -Force | Out-Null
$asterInstalled = Join-Path $asterTarget 'application\AsterEnginePreview.exe'
$asterExpectedHash = (Get-FileHash -LiteralPath $asterInstalled).Hash
# A real replacement must repair old application bytes and retain the profile.
[System.IO.File]::WriteAllText($asterInstalled, 'old application fixture')
Install-Aster $asterSetup
$asterMetadata = Get-Content -Raw -LiteralPath (Join-Path $asterBuild 'VERSION.json') | ConvertFrom-Json
if ((Get-ItemPropertyValue -LiteralPath $asterUninstall -Name 'DisplayVersion') -ne $asterMetadata.version) { throw 'Installed version was not upgraded' }
if ((Get-FileHash -LiteralPath $asterInstalled).Hash -ne $asterExpectedHash) { throw 'Setup did not replace old application bytes' }
if ((Get-ItemPropertyValue -LiteralPath $asterProfile -Name 'upgrade-fixture') -ne 'bookmarks-and-notes-stay') { throw 'Setup altered the user profile' }
$asterFrame = Join-Path $asterBuild 'aster-installed-window.png'
$asterApp = Start-Process -FilePath $asterInstalled -ArgumentList @('--smoke', "`"$asterFrame`"") -PassThru
if (-not $asterApp.WaitForExit(40000)) { $asterApp.Kill(); throw 'Installed app did not finish its window check' }
if ($asterApp.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $asterFrame)) { throw 'Installed application could not open' }
'Windows setup installed, replaced application bytes, preserved profile and opened the native window.' | Set-Content -LiteralPath (Join-Path $asterBuild 'WINDOWS-SETUP-STATUS.txt')
