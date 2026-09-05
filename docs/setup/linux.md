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

The script installs/checks the native GTK/WebKit dependencies, then installs the verified Aster source. Automatic dependency commands cover Debian/Ubuntu families, Arch and Fedora; the distro must provide WebKitGTK's **6.0 API**. On other distributions, install the dependencies yourself and run with `--skip-dependencies` if the WebKitGTK 6.0 runtime is available. This is not a guarantee of compatibility with every distro.

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

## Steam Deck and immutable Linux

A maintained standalone Aster package for SteamOS/immutable Linux is not ready yet. Setup reports that limit and does not unlock the OS image or install a different browser. A future Flatpak or other native package must bundle Aster's own application and be tested on Steam Deck hardware.

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

- **Missing WebKit package:** check your distro version and the [native dependency instructions](../../experiments/webkit/README.md).
- **Source files changed:** keep your edits and choose another `--install-dir`, or restore the modified files before updating.
- **Download failed / GitHub rate limit:** retry later. The previous active code revision remains selected.
- **Another setup may be running:** wait for it to finish. Only remove the exact `.setup-lock` file mentioned in the error after confirming the earlier setup process has exited.
- **Protected video:** premium DRM playback inside Aster has not been validated. Setup does not install another browser as a workaround. Ordinary video support also depends on your distro's codec packages.

Keep WebKit and GTK updated through your distribution's normal updates. See [the standalone product direction](../../PROJECT_DIRECTION.md) for the remaining Windows, Android and streaming work.
