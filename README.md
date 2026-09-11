# Aster Browser

## Download the latest preview

**[Windows setup — download and run](https://github.com/xatusbetazx17/aster-browser/releases/download/codex-preview/aster-windows-x64-setup.exe)** · **[Linux Flatpak — download and install](https://github.com/xatusbetazx17/aster-browser/releases/download/codex-preview/aster-linux-x64.flatpak)** · **[Android 8+ — choose the APK](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)**

[Install and update instructions](DOWNLOADS.md) · [Latest Codex preview and release notes](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview) · [All versions](https://github.com/xatusbetazx17/aster-browser/releases)

**Linux one-command setup:** from this checkout, run `bash install-linux.sh --run`.
It downloads and verifies the current original-engine Flatpak, then installs or
updates it for your account. Flatpak must already be installed. `--check` shows
installation status; `--uninstall` removes the app while keeping saved data.
Windows setup offers the Aster icon, system light/dark appearance, a destination
folder and separate Start menu/desktop shortcut choices.

The [improvements adapted from new-development](docs/development/new-development-port.md)
also include the cyan/dark desktop styling and compact File / View / Tools menu.

Windows setup and Linux Flatpak replace an older original-engine installation while keeping its profile. Android needs the persistent signing identity described on the release page; disposable-key testing APKs cannot update one another. These release downloads have no 14-day artifact expiry.

Aster is being developed as a **standalone browser with its own interface and page engine**. The original-engine Windows/Linux/Android application is the destination for new development. It opens directly as Aster and embeds no Chrome, Firefox, WebKit or WebView. The older Linux WebKit prototype remains available separately.

**Status: experimental.** Aster 0.8 adds a shared Flexbox subset for rows, columns, wrapping and alignment, live inline style updates, and a reproducible HLS bitrate correction. CDN resources, responsive CSS, stylesheet ordering and priority remain included. Native HLS checks require three successive restarts. Site protection, HTTP APIs, sessions/storage and the native reader, notes, tab parking and downloads remain included. Full web compatibility, Netflix/Prime Video, cloud gaming, permanent Android signing and validated Steam Deck support remain unfinished. No performance superiority over other browsers has been established.

Read [Aster 0.8 layout, HLS fixes and current limitations](experiments/aster-engine/RELEASE_0.8.md).

Read [the project direction and remaining work](PROJECT_DIRECTION.md).

## Original engine development preview

[`experiments/aster-engine`](experiments/aster-engine/README.md) now contains Aster's
own basic HTML/text parser, layout and link renderer, shared by Windows/Linux
desktop and native Android preview applications. It embeds no other browser engine.
Its build workflow produces a Windows setup EXE and portable bundle, Linux Flatpak
and portable bundle, and Android APK. The [release page](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)
provides regular-user downloads and the actual verification status of each platform.
The original-engine Windows/Linux desktop preview includes animated tabs, flat
internal pages and [working direct file downloads](experiments/aster-engine/README.md#download-files-on-windowslinux) with Save As, progress and cancellation.

**The desktop now has opt-in JavaScript/DOM interaction, CORS-governed fetch/XHR and
same-origin WebSocket connections, native controller input, page video controls, progressive
media and unencrypted HLS playback.** Try `aster:playground`. It uses standalone
QuickJS and JavaFX media, with no embedded browser engine. This is still an early
foundation: full web layout, WebRTC, MSE/DASH, EME and Prime Video/Boosteroid
playback remain unfinished. Android shares browsing/reading and the new restricted website sessions,
while desktop scripting/media remain separate. PDF and the AI companion have not
been brought into this original engine.
See [preview installation and update limitations](experiments/aster-engine/README.md#try-the-actual-packages).

## Install or update standalone Aster

- [Linux installation, updates and rollback](docs/setup/linux.md)
- [Windows build status](docs/setup/windows.md)
- [Android build status](docs/setup/android.md)
- [Setup behavior and data preservation](docs/setup/README.md)

On Linux with Flatpak, run the root `install-linux.sh` again to install the latest
published original-engine preview. Its profile remains separate from application
files. The older WebKit source updater lives under `installers/`; its own
installation and rollback behavior is documented separately.

## Implemented in the standalone Linux prototype

- Aster's native horizontal tab bar and address/search field.
- Navigation, close/reopen tabs, page-load progress and per-tab zoom.
- Bookmarks with local atomic storage.
- Find in page and developer tools.
- Native download save/cancel dialogs.
- A separate persistent profile and shared cookies between Aster tabs.
- A native Word/PDF/text reader with document search and English/Spanish offline read-aloud.
- An in-browser companion with offline commands/excerpts, optional local GGUF inference and Vosk voice input.
- Fullscreen controls, camera/microphone and mouse-capture prompts, and a media capability check.
- A refreshed Aster new-tab page and a docked Read / Ask / Play panel.

Read the [document and local assistant setup](docs/setup/assistant.md) and the [streaming compatibility details](docs/setup/streaming.md). Optional AI/voice models are separate downloads. The streaming controls do not supply Widevine or prove Prime Video/Boosteroid playback.

See the [standalone application's instructions and test details](experiments/webkit/README.md).

## Work needed to reach the original goal

The original parking, adblock customization, containers, Lite renderer and plugin features still need integration into the standalone application. Native Windows and Android applications, maintained SteamOS packaging and legitimate protected-video support require additional work. These are requirements, not completed features.

## Historical experiments

The v15 ZIPs and legacy installers in this repository run the older Qt/Chromium edition. The Firefox companion was a separate experiment and is **retired from the active product and installation path**. Neither is presented as the new standalone Aster browser. Historical files are retained for reference; new setup commands use the standalone Linux application.

Aster source is covered by the repository's MIT license. Its engine and other dependencies retain their own licenses.
