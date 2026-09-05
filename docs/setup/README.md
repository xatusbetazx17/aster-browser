# Install or update Aster

Use the guide for your device. **Run the same desktop setup command again to update the managed Aster source.** If the selected experiment has not been installed by these scripts, setup creates a new installation.

| Device | Guide | What is available now |
| --- | --- | --- |
| Desktop Linux | [Linux install and update](linux.md) | Standalone WebKit experiment, or Firefox companion source |
| Steam Deck / immutable Linux | [Firefox on Linux](linux.md#firefox-companion-and-steam-deck) | Firefox companion source; hardware/Gaming Mode unverified |
| Windows | [Windows install and update](windows.md) | Firefox companion source and launcher; no standalone Aster EXE |
| Android | [Android install and update](android.md) | Firefox installation/update now; Aster installation requires a future signed add-on |

## What the desktop updater does

- Downloads the selected experiment from `codex/aster-webkit-desktop`, pinned to one GitHub commit.
- Downloads only experiment, setup, license and guide files, avoiding the repository's legacy installer archives.
- Checks each file against its size and Git blob hash from that revision over HTTPS. This checks download consistency; it is not a signed-release verification scheme.
- Activates the new code only after its complete download passes verification.
- Keeps earlier code directories and offers `--rollback`. Rollback changes application code, not browser profile data.
- Refuses to overwrite an unrelated directory, modified installed source, modified launcher or unreadable installation state.
- Uses your existing Firefox runtime when present. If it previously used Flatpak, it keeps using Flatpak on subsequent updates.
- Installs missing runtime packages on supported desktop distributions, uses Firefox Flatpak on SteamOS/immutable Linux, or installs missing Firefox through WinGet on Windows. OS package managers may ask for your password or confirmation.

It does **not** replace the original Qt Aster installation, convert a Qt profile, update an arbitrary Git checkout, silently install an unsigned Firefox extension, or create a native Android app. Existing test checkouts and old installers remain where they are. These scripts create their own managed code installation; they do not move your personal browser data into it.

The Linux WebKit experiment continues using its existing `aster-webkit` profile. The Firefox launcher continues using its existing isolated Aster profile. If you load the companion into another Firefox profile, its data belongs to that profile. Export an Aster JSON backup before replacing a temporary add-on.

## Code updates versus browser updates

These scripts update **Aster code when you run them**. They do not install a background service or scheduled task. Existing native Firefox installations keep their normal Firefox/distro updater. Linux WebKit/GTK security updates come from your distribution; setup does not perform a whole-system upgrade. Firefox Flatpak is updated when selected setup runs with dependencies enabled.

For a future signed companion release, Firefox can manage add-on updates through AMO or a configured, maintained update feed. Neither distribution channel has been published for this experiment yet. The [Android guide](android.md) explains both installation states without claiming the unsigned test build can be installed normally.

## Advanced setup flags

| Flag | Effect |
| --- | --- |
| `--edition webkit` | Linux standalone WebKit experiment |
| `--edition firefox` | Firefox companion source |
| `--install-dir PATH` | A separate managed directory, including paths containing spaces |
| `--skip-dependencies` | Check for the runtime without invoking package managers |
| `--check` | Report local installation status; no application download or install |
| `--rollback` | Activate the previous managed code revision without downloading |

Python 3.10+ is required. A standalone bootstrap may first download `setup.py` to read these options; a repository checkout includes the helper already. Network errors, missing runtimes, integrity failures or local edits produce a nonzero exit status and an explanation.

## Validation and remaining limits

The setup workflow runs on Windows and Ubuntu. Tests cover managed install/update, no-op repeat, failed downloads and file verification, refused local edits, rollback, pointer-write failures, concurrent setup, path boundaries and preserved runtime choice. A live test downloads the real branch into a temporary directory and checks the generated launcher without installing OS packages.

Package-manager installation/UAC/password prompts, all Linux distributions, Steam Deck hardware and Android installation have **not** been automatically validated. No signed Aster release, Android APK or guaranteed Prime Video playback is supplied by these scripts.
