# Linux: install or update Aster

The same command performs first installation or updates an installation previously created by this setup tool. Use your normal desktop account. Setup calls `sudo` only for supported system packages.

## Standalone Aster WebKit

On Ubuntu 24.04 or a compatible newer desktop, install the small bootstrap prerequisites if needed:

```bash
sudo apt update
sudo apt install python3 curl
```

On desktop Arch Linux, use `sudo pacman -S --needed python curl`. Keep Arch updated through your normal whole-system update process; this installer does not refresh its package databases or perform a partial system upgrade. On Fedora, use `sudo dnf install python3 curl`. All paths require Python 3.10+.

Download the script, then run it:

```bash
curl --fail --location --proto '=https' --proto-redir '=https' \
  'https://raw.githubusercontent.com/xatusbetazx17/aster-browser/codex/aster-webkit-desktop/installers/install-linux.sh' \
  --output install-aster-linux.sh
bash install-aster-linux.sh --edition webkit
```

The script installs/checks the native GTK/WebKit dependencies, then installs the verified Aster source. Automatic dependency commands cover Debian/Ubuntu families, Arch and Fedora; the distro must provide WebKitGTK's **6.0 API**. Unsupported or older distributions should use the Firefox path below, or install the dependencies themselves and run with `--skip-dependencies`.

Launch the standalone browser:

```bash
/usr/bin/python3 "$HOME/.local/share/aster-testing-webkit/start-aster.py"
```

Try two tabs, bookmarks, Ctrl+F, zoom and a download. This is the original non-Chromium Linux prototype, not full v15 parity. Camera/microphone permission controls and verified premium DRM remain absent.

## Update an existing managed installation

Close Aster, then run the same download/setup commands above. Alternatively, use the installed launcher:

```bash
/usr/bin/python3 "$HOME/.local/share/aster-testing-webkit/start-aster.py" --update
```

The latest setup script can also be downloaded again to pick up bootstrap/runtime changes. If no code update is available, setup reports that the code is current. Your original checkout and Qt installation are not overwritten. Existing WebKit bookmarks and cookies remain in the separate `aster-webkit` profile used by previous launches.

Check status without installing application files:

```bash
bash install-aster-linux.sh --edition webkit --check
```

Roll back to the previous downloaded code revision:

```bash
bash install-aster-linux.sh --edition webkit --rollback
```

Rollback does not restore or change browser-profile data. Old release directories are kept so custom files can be recovered; setup does not delete them automatically.

## Firefox companion and Steam Deck

On Steam Deck, first switch to **Desktop Mode**. Download the same Linux script, then run:

```bash
bash install-aster-linux.sh --edition firefox
```

If native Firefox is installed on a conventional desktop, setup uses it. Otherwise it installs or updates Firefox Flatpak using Flatpak's normal confirmations. SteamOS and immutable Linux default to Flatpak. If Flatpak itself is unavailable on an immutable system, install it through that system's software center first. Setup does not unlock or modify the read-only OS image.

Open Firefox in Aster's isolated profile:

```bash
python3 "$HOME/.local/share/aster-testing-firefox/start-aster.py"
```

Then load the test companion:

1. Enter `about:debugging#/runtime/this-firefox` in Firefox.
2. Select **Load Temporary Add-on**.
3. Select the **full `manifest.json` path printed by setup**. It is under the managed install's `releases/<commit>/experiments/firefox/extension/` folder.
4. Open the Aster toolbar button, then **Open workspace**.

After updating, use **Load Temporary Add-on** and select the **new manifest path printed by setup**. Clicking Reload on an entry pointing to the old release keeps the old files. Reinstalling the same extension ID is handled by Firefox; export an Aster JSON backup first, and avoid removing the add-on before loading the update. Temporary loading is required again after a Firefox restart. [Mozilla temporary installation guide](https://extensionworkshop.com/documentation/develop/temporary-installation-in-firefox/)

Update source again with:

```bash
python3 "$HOME/.local/share/aster-testing-firefox/start-aster.py" --update
```

The script cannot silently activate unsigned add-on updates inside Firefox. Normal permanent installation and automatic add-on updates need a signed release. Steam Deck controller/Gaming Mode support and hardware playback remain unverified.

## Existing Git checkouts or old Aster installations

If you previously ran `aster-linux-test/experiments/webkit/run_aster_webkit.py`, the managed WebKit launcher uses the same default browser profile while keeping a separate source directory. It will not merge or overwrite edits in `aster-linux-test`.

If you prefer maintaining your existing clean Git checkout yourself, the usual development update is:

```bash
git fetch origin
git switch codex/aster-webkit-desktop
git pull --ff-only origin codex/aster-webkit-desktop
```

Run these only inside that checkout. If Git reports local changes or divergence, preserve your work before resolving them. The automated setup does not run Git resets or force a merge. Original Qt v15 installers remain a separate browser edition.

## Troubleshooting

- **Missing WebKit package:** check your distro version, use the Firefox edition, or install the [native dependencies](../../experiments/webkit/README.md) yourself.
- **Source files changed:** keep your edits and choose another `--install-dir`, or restore the modified files before updating.
- **Download failed / GitHub rate limit:** retry later. The previous active code revision remains selected.
- **Another setup may be running:** wait for it to finish. Only remove the exact `.setup-lock` file mentioned in the error after confirming the earlier setup process has exited.
- **Firefox cannot see the manifest:** use its file chooser to select the printed location. A sandboxed Firefox package may require a location it can access; `--install-dir "$HOME/Downloads/AsterCompanion"` creates a separate accessible test installation on common setups.
- **Prime Video:** update Firefox, enable DRM-controlled content in Firefox Settings, and try with Aster blocking off. Setup itself does not verify paid streaming. [Mozilla DRM help](https://support.mozilla.org/en-US/kb/enable-drm)

For native Firefox updates, use its existing distro or Mozilla updater. Keep WebKit updated through normal system updates. [Mozilla Linux installation guidance](https://support.mozilla.org/en-US/kb/install-firefox-linux)
