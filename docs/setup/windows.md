# Windows: Aster previews and full-browser status

**A Windows preview using Aster's original basic text engine is now implemented.**
Follow [its package, launch and update instructions](../../experiments/aster-engine/README.md#windows-x64).
Use the EXE bundle from a successful original-engine CI run. It includes Java and
does not use another browser engine. It has no JavaScript/video/DRM and is not the
completed port of Aster's reader/assistant/WebKit browser.

The Linux GTK/WebKit prototype cannot simply be installed as a Windows program.
The original-engine preview is a separate portable EXE application, not an MSI
installer or a conversion of an existing Aster installation. The old Qt installer
still belongs to the legacy Chromium-based edition.

## What the setup file does now

`installers/install-windows.ps1` is retained at its former download location to prevent misleading installation. It reports the missing standalone build and exits with status 2. With `-Check`, it reports status and exits successfully. It installs no browser or runtime and changes no existing application data.

The graphical `Aster-Browser-Setup.exe` — which installs the legacy v16 kit, not the
standalone port described above — is built from `Aster-Browser-Windows-Kit-v16.zip`
and committed at the repository root. It is not produced by a checkout, so it is only
as new as the last build: rebuild and commit it whenever the kit, the logo or the
interface changes, on a Windows machine with Python 3.10+:

```powershell
python -m pip install --upgrade pyinstaller pillow customtkinter
python installers\windows_installer\build_exe.py
```

That regenerates the icons from `asterlogo.png`, repacks the kit into
`app_bundle.zip` and writes the executable to the repository root.
`.github/workflows/release.yml` builds the same file on each `v*` tag and attaches it
to the matching [GitHub Release](https://github.com/xatusbetazx17/aster-browser/releases/latest)
with a `SHA256SUMS.txt`, which is the copy to verify a download against.

## Required Windows implementation

- Build and test the standalone Aster application and an engine integration that meets the project's requirements.
- Package it with its actual runtime dependencies, without depending on another browser application.
- Provide a versioned installer that detects an existing Aster installation and updates its application files while preserving profiles.
- Validate clean installation, upgrades, rollback, downloads, media, permissions and accessibility on supported Windows versions.
- Verify legitimate protected-video playback inside Aster separately from ordinary video rendering.

Most of these steps remain development work. The portable engine preview updates
by extracting a newer bundle into a new directory, preserving its per-user
bookmarks. There is no automatic Windows full-browser update or stable release channel.

See [the project direction](../../PROJECT_DIRECTION.md), or test the available [standalone Linux prototype](linux.md).
