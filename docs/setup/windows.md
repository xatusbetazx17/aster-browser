# Windows: standalone Aster build status

**A standalone Windows Aster build is not available yet.** The new browser must run as Aster itself. Installing Firefox and an extension does not satisfy this requirement, and that previous installation path has been retired.

The Linux GTK/WebKit prototype cannot simply be installed as a Windows program. No native Aster EXE/MSI is supplied by this branch. The old Qt installer in the repository still belongs to the legacy Chromium-based edition.

## What the setup file does now

`installers/install-windows.ps1` is retained at its former download location to prevent misleading installation. It reports the missing standalone build and exits with status 2. With `-Check`, it reports status and exits successfully. It installs no browser or runtime and changes no existing application data.

## Required Windows implementation

- Build and test the standalone Aster application and an engine integration that meets the project's requirements.
- Package it with its actual runtime dependencies, without depending on another browser application.
- Provide a versioned installer that detects an existing Aster installation and updates its application files while preserving profiles.
- Validate clean installation, upgrades, rollback, downloads, media, permissions and accessibility on supported Windows versions.
- Verify legitimate protected-video playback inside Aster separately from ordinary video rendering.

These steps remain development work. There is no automatic Windows application update until an actual native build and maintained distribution channel exist.

See [the project direction](../../PROJECT_DIRECTION.md), or test the available [standalone Linux prototype](linux.md).
