# Windows: install or update Aster Preview

Download the **Windows setup EXE** from [Latest Codex preview](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview),
close Aster, and run setup. Open **Aster Preview** from Start. A newer setup replaces
the same application and preserves the original-engine profile. Java is included.

See [the installation and update guide](../../DOWNLOADS.md#windows-1011-64-bit-intel-or-amd)
for portable-installation details, system requirements and known limitations.
The package targets Windows 10/11 x64, has no publisher certificate yet, and
Windows video/HLS remains unreliable. Core browsing/reading, native window and
setup/update checks must pass before the package can be released.

Aster uses its own renderer and includes limited images, CSS/forms, document
reading and opt-in desktop scripting. Full modern-web compatibility, logins,
protected streaming and the AI companion are still unfinished.

The old `installers/install-windows.ps1` remains a compatibility notice. The
published setup EXE is the normal installation path. Older Qt/Chromium and
Linux WebKit editions have separate application identities and profiles;
the Windows preview does not automatically convert them.
