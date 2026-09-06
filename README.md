# Aster Browser

Aster is being developed as a **standalone browser with its own interface and features**. The active prototype opens directly as Aster. Its Linux application uses GTK4, WebKitGTK and JavaScriptCore, with no Firefox, Chrome or Chromium installation required.

**Status: experimental.** The standalone application does not yet have all original v15 features, native Windows/Android ports, a validated Steam Deck package or verified premium streaming. WebKit is an existing rendering engine; a complete Aster engine written from scratch has not been implemented.

Read [the project direction and remaining work](PROJECT_DIRECTION.md).

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
