# Exercise the shipped setup EXE twice, verify profile preservation, then launch it.
$ErrorActionPreference = 'Stop'
$asterRoot = Split-Path $PSScriptRoot -Parent
$asterBuild = Join-Path $asterRoot 'build'
$asterSetup = Join-Path $asterBuild 'aster-windows-x64-setup.exe'
$asterTarget = Join-Path $env:LOCALAPPDATA 'Programs\Aster Custom Folder'
$asterProfile = 'HKCU:\Software\JavaSoft\Prefs\io\aster\engine-preview'
$asterUninstall = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\io.aster.browser.EnginePreview_is1'
function Install-Aster([string]$asterInstaller, [switch]$asterChooseDirectory) {
    $asterOptions = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-')
    if ($asterChooseDirectory) { $asterOptions += @("/DIR=`"$asterTarget`"", '/TASKS=startmenuicon,desktopicon') }
    $asterProcess = Start-Process -FilePath $asterInstaller -ArgumentList $asterOptions -Wait -PassThru
    if ($asterProcess.ExitCode -ne 0) { throw "Aster setup failed: $($asterProcess.ExitCode)" }
}
$asterCompiler = Join-Path $asterBuild 'inno\ISCC.exe'
& $asterCompiler '/DAsterVersion=0.2.0-preview.2' '/DAsterNativeVersion=0.2.0.2' '/Faster-upgrade-baseline' (Join-Path $asterRoot 'packaging\windows.iss')
if ($LASTEXITCODE -ne 0) { throw 'Could not build an older-version installation fixture' }
Install-Aster (Join-Path $asterBuild 'aster-upgrade-baseline.exe') -asterChooseDirectory
if (-not (Test-Path -LiteralPath $asterUninstall)) { throw 'Missing per-user installed application identity' }
New-Item -Path $asterProfile -Force | Out-Null
New-ItemProperty -LiteralPath $asterProfile -Name 'upgrade-fixture' -Value 'bookmarks-and-notes-stay' -PropertyType String -Force | Out-Null
$asterInstalled = Join-Path $asterTarget 'application\AsterEnginePreview.exe'
$asterExpectedHash = (Get-FileHash -LiteralPath $asterInstalled).Hash
# A real replacement must repair old application bytes and retain the profile.
# A Windows file scanner can briefly hold a newly installed EXE. Wait only for
# sharing/lock violations; other errors and a persistent lock still fail.
$asterLockDeadline = [DateTime]::UtcNow.AddSeconds(20)
$asterLockReported = $false
while ($true) {
    try { [System.IO.File]::WriteAllText($asterInstalled, 'old application fixture'); break }
    catch {
        $asterReason = $_.Exception.GetBaseException()
        $asterWin32Code = $asterReason.HResult -band 0xffff
        if ($asterReason -isnot [System.IO.IOException] -or $asterWin32Code -notin @(32, 33) -or [DateTime]::UtcNow -ge $asterLockDeadline) { throw }
        if (-not $asterLockReported) { Write-Output 'Waiting for the installed EXE sharing lock before creating the upgrade fixture.'; $asterLockReported = $true }
        Start-Sleep -Milliseconds 100
    }
}
Install-Aster $asterSetup
$asterMetadata = Get-Content -Raw -LiteralPath (Join-Path $asterBuild 'VERSION.json') | ConvertFrom-Json
if ((Get-ItemPropertyValue -LiteralPath $asterUninstall -Name 'DisplayVersion') -ne $asterMetadata.version) { throw 'Installed version was not upgraded' }
if ((Get-FileHash -LiteralPath $asterInstalled).Hash -ne $asterExpectedHash) { throw 'Setup did not replace old application bytes' }
if ((Get-ItemPropertyValue -LiteralPath $asterProfile -Name 'upgrade-fixture') -ne 'bookmarks-and-notes-stay') { throw 'Setup altered the user profile' }
$asterShell = New-Object -ComObject WScript.Shell
foreach ($asterFolder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $asterLink = Join-Path $asterFolder 'Aster Preview.lnk'
    if (-not (Test-Path -LiteralPath $asterLink)) { throw "Missing selected shortcut: $asterLink" }
    if ($asterShell.CreateShortcut($asterLink).TargetPath -ne $asterInstalled) { throw 'Shortcut does not target the original-engine installation' }
}
$asterFrame = Join-Path $asterBuild 'aster-installed-window.png'
$asterApp = Start-Process -FilePath $asterInstalled -ArgumentList @('--smoke', "`"$asterFrame`"") -PassThru
if (-not $asterApp.WaitForExit(40000)) { $asterApp.Kill(); throw 'Installed app did not finish its window check' }
if ($asterApp.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $asterFrame)) { throw 'Installed application could not open' }
# The generated uninstaller removes the managed app and selected shortcuts only.
$asterRemoval = Start-Process -FilePath (Join-Path $asterTarget 'unins000.exe') -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') -Wait -PassThru
if ($asterRemoval.ExitCode -ne 0 -or (Test-Path -LiteralPath $asterInstalled)) { throw 'Uninstall did not remove the managed application' }
if ((Get-ItemPropertyValue -LiteralPath $asterProfile -Name 'upgrade-fixture') -ne 'bookmarks-and-notes-stay') { throw 'Uninstall altered the user profile' }
Install-Aster $asterSetup -asterChooseDirectory
'Windows setup retained the chosen folder and shortcuts, replaced application bytes, opened the native window and preserved the profile through uninstall/reinstall.' | Set-Content -LiteralPath (Join-Path $asterBuild 'WINDOWS-SETUP-STATUS.txt')
