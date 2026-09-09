# Android: original-engine preview and full-browser status

**A native Android original-engine preview is now implemented.** It uses Android
widgets and Canvas with Aster's own basic HTML/text renderer, without WebView or
another browser engine. [Build, install and update instructions](../../experiments/aster-engine/README.md#android-80api-26-or-later)
describe the release APK and its signing requirements.
The [0.2 release](../../experiments/aster-engine/RELEASE_0.2.md) adds images, basic CSS/forms,
search, tabs, saved sessions, native documents/notes/speech and Save As downloads.
Android 8/API 26 remains the minimum. The full browser, companion, JavaScript and
streaming platform remain unfinished.

The existing Android Lite source in the legacy v15 package uses Android WebView. It is not a full standalone port of the new application and does not establish the requested engine independence.

## Installation and updates

Download from [Latest Codex preview](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview).
The release page distinguishes the persistent-key update APK from a testing APK.
Read [Android installation/update help](../../DOWNLOADS.md#android-80-or-later).

The package stays `io.aster.browser.enginepreview`. Persistent release signing
and increasing versionCode allow Android to replace it while retaining app data.
The owner must configure the private signing secret once; the publisher rejects
later key changes. The API 26 emulator checks a lower-version install followed
by a higher-version replacement and retention of a bookmark.

Earlier Actions APKs used disposable keys. Those cannot be updated with an
unrelated key, and Android Lite is a separate application. Do not uninstall an
older app containing data you need; an export/migration path is still unfinished.

## Required Android implementation

- Implement Aster's native mobile application and a suitable independent engine integration.
- Port its browser features, storage, permissions and mobile navigation into that app.
- Build and sign Android packages, then test real clean installs and upgrades.
- Test representative devices and architectures, screen sizes, downloads, audio and lifecycle behavior.
- Establish codec/DRM integration and service support for protected video inside Aster. A mobile interface or a Prime Video shortcut is not playback verification.

These requirements are not completed by the limited browsing/reading preview. Its actual
`MediaDrm` device query does not provide EME, a license exchange or Prime Video
playback. See [the project direction](../../PROJECT_DIRECTION.md) and the available
[standalone Linux build](linux.md).
