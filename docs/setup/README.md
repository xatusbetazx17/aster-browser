# Install or update standalone Aster

Aster must run as its own browser. The active setup path installs the standalone Linux application and does not require Firefox, Chrome or Chromium.

| Device | Guide | Current deliverable |
| --- | --- | --- |
| Supported desktop Linux | [Install, update and roll back](linux.md) | Standalone Aster using WebKitGTK |
| Windows | [Build status](windows.md) | Native Aster app not built yet |
| Android | [Build status](android.md) | Native Aster app not built yet |
| Steam Deck / immutable Linux | [Linux platform limits](linux.md#steam-deck-and-immutable-linux) | Standalone package not ready yet |

## Reusing the Linux installer

Run the same setup command to install Aster if it is missing, or update an installation created by this tool. It downloads the selected branch's application code into a managed per-user directory. It does not overwrite arbitrary source checkouts or the old Qt installation.

The updater pins downloads to one GitHub commit, checks their sizes and Git blob hashes over HTTPS, and selects the new code only after verification succeeds. This validates download consistency, not a signed-release identity. Older code directories remain available for `--rollback`.

Modified installed source, unreadable state and unrelated directories cause a clear error instead of being overwritten. Interrupted downloads preserve the previous selected version. Browser profile data remains in the existing separate `aster-webkit` location; rollback changes code only, not personal data.

## Options

| Option | Behavior |
| --- | --- |
| `--edition webkit` | Select standalone Linux Aster; this is the default |
| `--install-dir PATH` | Use a separate managed code directory |
| `--skip-dependencies` | Check installed runtime libraries without invoking a package manager |
| `--check` | Show local installation status without installing the application |
| `--rollback` | Select the previous downloaded code revision |

Python 3.10+ is required. A standalone downloaded bootstrap first obtains the Python setup helper; a repository checkout already includes it. Run setup as your normal desktop user. It uses sudo only for native packages on supported distributions. GTK and WebKit are libraries, not separately installed browsers.

## Updates and compatibility

Aster code updates occur when you run setup; no automatic background service is installed. Keep WebKit and GTK updated through your distribution's normal system updates. No whole-system upgrade is performed by setup. Windows/Android builds, all-distro compatibility and Steam Deck hardware support are not implied by the source updater's tests.

The Windows script at the previous download location now reports build availability and exits without installing anything. The retired Firefox companion is no longer an install/update target. Previously downloaded companion code is not deleted, and existing browsers or their profiles are not removed.

## Validation

Tests cover first installation, repeated updates, interrupted downloads, integrity failures, local changes, rollback, pointer-write failures, concurrent setup, path boundaries and platform rejection without installing a substitute browser. A Windows/Linux workflow also exercises real source downloads and the generated updater in temporary directories, without installing system packages.

See [the project direction](../../PROJECT_DIRECTION.md) for the remaining standalone browser work. Protected streaming inside Aster has not been validated.
