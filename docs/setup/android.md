# Android: installation and updates

**Current status: Firefox can be installed/updated now. Aster Companion has no published Mozilla-signed Android release yet.** The repository's unsigned XPI and desktop scripts cannot complete a normal Android add-on installation. A separate standalone Aster APK has not been built for this non-Chromium edition.

## Install Firefox if missing, or update it if already installed

1. Open [Mozilla Firefox on Google Play](https://play.google.com/store/apps/details?id=org.mozilla.firefox).
2. Tap **Install** if it is missing, or **Update** if an update is available.
3. If the button says **Open**, Google Play is not currently offering your device another update.
4. Keep Firefox's Play Store automatic updates enabled if you want future browser updates automatically.

Aster declares Firefox 142+ as its minimum, but use a current supported release. Google Play may stage updates or limit them by device. Updating Firefox does not install Aster or establish compatibility with every Aster feature.

## Install Aster after a signed Android-compatible release is published

Use a signed package published by this repository's maintainer, or a future official Aster listing on Mozilla Add-ons. **There is no such download linked here yet.** Do not rename the unsigned XPI to APK or try the old WebView APK as a replacement for this edition.

For a maintainer-provided signed `.xpi`, Mozilla documents this Android file-installation flow:

1. Save the signed `.xpi` on the Android device.
2. Open Firefox **Settings → About Firefox**.
3. Tap the Firefox logo five times in quick succession to reveal the file-installation menu.
4. Return to Settings and choose **Install Extension from File**.
5. Select the signed XPI and approve Firefox's add-on prompt.
6. Open Firefox's Extensions menu, then Aster Companion.

The signature and compatibility checks still apply. This menu does not make an unsigned package installable. [Mozilla Android file-installation instructions](https://extensionworkshop.com/documentation/publish/install-self-distributed/)

## Update an existing signed Aster installation

For a future AMO-installed release, use Firefox's add-on update mechanism. For a self-distributed signed release with no update feed, download the newer signed XPI from the same maintainer and install it through **Install Extension from File** in the same Firefox profile.

The new package must retain Aster's extension ID (`aster-companion@aster-browser.local`), have an appropriate newer version and still pass Mozilla's signing/compatibility checks. Export an Aster JSON backup first. Avoid uninstalling Aster or clearing Firefox's application data as an update step, since that can remove stored settings and saved pages.

Automatic Aster updates require a published AMO listing or a configured, maintained update feed. **This experiment does not have either yet.** The future maintainer must publish updates through the selected channel; uploading source changes to the Codex branch alone does not update Android installations. [Mozilla extension update guidance](https://extensionworkshop.com/documentation/manage/updating-your-extension/)

## If you already have the old Aster Android Lite app

That older starter uses Android WebView and a different app identity. The Firefox companion does not update or migrate it. Keep its data until a deliberate migration path exists. Android browser cookies, app data and APK update signatures cannot be transferred by these desktop source scripts.

## What still needs testing

Android runtime behavior, containers, tab pinning, installed local speech voices and actual media playback remain unverified. Some streaming services may require their official Android app. A Firefox installation, a successful add-on install or an available Prime Video link does not guarantee protected-video playback.

The current immediately testable companion paths are documented for [Linux](linux.md) and [Windows](windows.md).
