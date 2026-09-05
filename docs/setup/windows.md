# Windows: install or update Aster Companion

This installs or updates **Aster Companion source for Firefox**. It is not a standalone Aster EXE and does not replace the old Qt browser. The current add-on is unsigned and must still be loaded temporarily in Firefox.

## First installation or repeat update

Use a normal PowerShell window on a Windows version supported by current Firefox. Download the script and run it:

```powershell
Invoke-WebRequest -UseBasicParsing `
  'https://raw.githubusercontent.com/xatusbetazx17/aster-browser/codex/aster-webkit-desktop/installers/install-windows.ps1' `
  -OutFile '.\install-aster-windows.ps1'
.\install-aster-windows.ps1
```

The script locates Python 3.10+ or installs Python 3.13 through WinGet when missing. It locates official Firefox or asks WinGet to install it when missing. Vendor/UAC prompts may appear. It then downloads a complete verified Aster revision and installs or updates its managed code directory.

If WinGet is unavailable, install [Python](https://www.python.org/downloads/windows/) and [official Firefox](https://www.mozilla.org/firefox/new/) first, reopen PowerShell, and rerun the script. If your PowerShell policy prevents running the downloaded script, use the Python entry point from a downloaded repository checkout when your device policy permits it:

```powershell
py -3 .\installers\setup.py --edition firefox
```

The scripts do not change execution policy or Firefox's signature/security settings. On a managed PC, follow your administrator's software-installation rules.

## Launch and load Aster

Launch Firefox using the existing isolated Aster profile:

```powershell
py -3 "$env:LOCALAPPDATA\aster-testing-firefox\start-aster.py"
```

If the Python launcher `py` is unavailable, use your installed Python executable in its place.

1. In the opened Firefox window, enter `about:debugging#/runtime/this-firefox`.
2. Click **Load Temporary Add-on**.
3. Select the **full manifest path printed by setup**: it ends in `experiments\firefox\extension\manifest.json` under a versioned release folder.
4. Click the Aster toolbar button, then **Open workspace**.

Test parking/resuming a page and saving/restoring a workspace. Optional blocking starts off; enable website access and save a blocking mode in Aster Settings.

## Update without removing your installation

Export a JSON backup from Aster Settings, then close Firefox. Run the same setup script again:

```powershell
.\install-aster-windows.ps1
```

Or use the installed launcher:

```powershell
py -3 "$env:LOCALAPPDATA\aster-testing-firefox\start-aster.py" --update
```

Download the bootstrap script again when you want its latest setup/runtime logic as well. The source updater prints the new revision and manifest path.

**Load the newly printed manifest path in Firefox after updating.** An existing temporary add-on entry still points to its previous release folder; Reload alone would reload that old version. Keep the same Firefox profile and extension ID. Avoid uninstalling the old add-on first; Firefox handles replacing the same extension, and a backup protects test data.

Temporary add-ons disappear from the active installation when Firefox restarts; load them again for the next test session. Normal permanent install/update requires Mozilla signing. [Mozilla temporary installation guide](https://extensionworkshop.com/documentation/develop/temporary-installation-in-firefox/)

## Check or roll back

```powershell
.\install-aster-windows.ps1 -Check
.\install-aster-windows.ps1 -Rollback
```

`-Check` reports local Aster application status without installing it. `-Rollback` selects the previous downloaded code revision. It does not roll back Firefox, browser cookies, settings or bookmarks. To use rolled-back companion code, load the printed older manifest path in Firefox.

Use a custom code directory with:

```powershell
.\install-aster-windows.ps1 -InstallDir "$env:LOCALAPPDATA\My Aster Test"
```

Supply the same directory on later check/update/rollback calls. The installation refuses unrelated nonempty directories and preserves modified source files instead of overwriting them.

## Update Firefox separately

Existing Firefox installations keep their normal Firefox updater. Use **Help → About Firefox** to check for a browser update. If Firefox is managed through WinGet, you can update that specific package with:

```powershell
winget upgrade --id Mozilla.Firefox --exact --source winget
```

No available upgrade means that source has no newer applicable package. This command updates Firefox, not the Aster add-on. [Microsoft WinGet upgrade guidance](https://learn.microsoft.com/en-us/windows/package-manager/winget/upgrade)

For Prime Video, enable **Play DRM-controlled content** in Firefox Settings and test an included title with your own subscription. Aster setup does not install/copy Widevine itself or guarantee premium playback. [Firefox DRM help](https://support.mozilla.org/en-US/kb/enable-drm)
