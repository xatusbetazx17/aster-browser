# Aster Browser

## Download the latest preview

**[Windows setup — download and run](https://github.com/xatusbetazx17/aster-browser/releases/download/codex-preview/aster-windows-x64-setup.exe)** · **[Linux Flatpak — download and install](https://github.com/xatusbetazx17/aster-browser/releases/download/codex-preview/aster-linux-x64.flatpak)** · **[Android 8+ — choose the APK](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)**

[Install and update instructions](DOWNLOADS.md) · [Latest Codex preview and release notes](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview) · [All versions](https://github.com/xatusbetazx17/aster-browser/releases)

Windows setup and Linux Flatpak replace an older original-engine installation while keeping its profile. Android needs the persistent signing identity described on the release page; disposable-key testing APKs cannot update one another. These release downloads have no 14-day artifact expiry.

Aster is being developed as a **standalone browser with its own interface and page engine**. The original-engine Windows/Linux/Android application is the destination for new development. It opens directly as Aster and embeds no Chrome, Firefox, WebKit or WebView. The older Linux WebKit prototype remains available separately.

**Status: experimental.** The independent engine is a limited implementation, not a complete modern browser. The 0.4 engine milestone adds shared website cookies, desktop web storage and reproducible renderer measurements. The included 0.3 workspace provides the dark Aster interface, integrated reading/notes, tab parking, lazy restoration and better Android navigation on top of images, basic CSS/forms and document tools, with Windows/Linux bundles, an Android 8+ APK and a Flatpak build. Full web compatibility, protected streaming, production signing and validated Steam Deck support remain unfinished.

Read [Aster 0.4 features, streaming blockers and the remaining engine roadmap](experiments/aster-engine/RELEASE_0.4.md).

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

**The desktop now has opt-in JavaScript/DOM interaction, same-origin fetch and
WebSocket connections, native controller input, page video controls, progressive
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

On supported desktop Linux, run the same setup command again to update Aster's managed code installation. The updater verifies downloads and preserves earlier code revisions for rollback. Browser profile data is separate from those code directories.

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
