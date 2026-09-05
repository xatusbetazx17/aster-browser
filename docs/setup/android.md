# Android: standalone Aster build status

**A native Android Aster app for this new browser has not been built yet.** Aster must open as its own app with its own interface and features. The former Firefox-companion instructions have been retired; installing another browser is not an Aster installation.

The existing Android Lite source in the legacy v15 package uses Android WebView. It is not a full standalone port of the new application and does not establish the requested engine independence.

## Installation and updates

There is no new standalone Aster APK/AAB to install from this branch yet. No script or renamed XPI can create one. If you have the old Android Lite app, preserve its data until a real migration plan is available; the current work does not upgrade or convert it.

For the eventual Android app, installation/update must use an actual signed Aster APK or an official Aster store listing. Upgrades must retain the same Android application identity and a compatible signing key so Android can update the installed application while preserving its data. Keep that signing identity secure as part of the release process.

## Required Android implementation

- Implement Aster's native mobile application and a suitable independent engine integration.
- Port its browser features, storage, permissions and mobile navigation into that app.
- Build and sign Android packages, then test real clean installs and upgrades.
- Test representative devices and architectures, screen sizes, downloads, audio and lifecycle behavior.
- Establish codec/DRM integration and service support for protected video inside Aster. A mobile interface or a Prime Video shortcut is not playback verification.

These are pending requirements, not completed Android support. See [the project direction](../../PROJECT_DIRECTION.md) and the available [standalone Linux build](linux.md).
