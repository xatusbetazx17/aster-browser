# Android: original-engine preview and full-browser status

**A native Android original-engine preview is now implemented.** It uses Android
widgets and Canvas with Aster's own basic HTML/text renderer, without WebView or
another browser engine. [Build, install and update instructions](../../experiments/aster-engine/README.md#android-80api-26-or-later)
describe the APK from successful CI runs and its development-signing limitations.
The full browser, companion, JavaScript and streaming platform remain unfinished.

The existing Android Lite source in the legacy v15 package uses Android WebView. It is not a full standalone port of the new application and does not establish the requested engine independence.

## Installation and updates

The new package is `io.aster.browser.enginepreview`, distributed as a development
APK, not an AAB/store release. It opens text sites and saves local bookmarks.
It does not upgrade or convert the old Android Lite app. Preserve that app's data
until a migration plan is implemented. CI APKs use a different development key
per run; cross-run in-place updates are not supported. Personal builds can retain
the same development keystore, and the emulator checks same-key replacement.

For the eventual Android app, installation/update must use an actual signed Aster APK or an official Aster store listing. Upgrades must retain the same Android application identity and a compatible signing key so Android can update the installed application while preserving its data. Keep that signing identity secure as part of the release process.

## Required Android implementation

- Implement Aster's native mobile application and a suitable independent engine integration.
- Port its browser features, storage, permissions and mobile navigation into that app.
- Build and sign Android packages, then test real clean installs and upgrades.
- Test representative devices and architectures, screen sizes, downloads, audio and lifecycle behavior.
- Establish codec/DRM integration and service support for protected video inside Aster. A mobile interface or a Prime Video shortcut is not playback verification.

These requirements are not completed by a working text preview. Its actual
`MediaDrm` device query does not provide EME, a license exchange or Prime Video
playback. See [the project direction](../../PROJECT_DIRECTION.md) and the available
[standalone Linux build](linux.md).
