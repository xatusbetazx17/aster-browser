# Aster 0.2 — independent browsing and reading preview

This release keeps Aster's own page renderer on Windows, Linux and Android 8+
(minimum API 26). QuickJS and JavaFX media remain standalone desktop components;
no Chromium, Gecko, WebKit or WebView is embedded in this application.

## New shared features

- Search words in the address bar using DuckDuckGo's HTML search endpoint, or enter
  a website URL. Site links still use strict HTTP/HTTPS URL validation.
- Display PNG, JPEG and the first GIF frame from the page's own origin. Images
  preserve their proportions and fit the viewport. Eight images, 2 MiB encoded
  bytes per image, two million decoded pixels per image and 8 MiB decoded image
  memory per page are the preview limits. Unsupported sources retain alt text.
- A small CSS cascade for type, class, ID, compound and comma-separated selectors,
  inline styles, typography, hidden content and `display:block`. Up to four
  same-origin external stylesheets are loaded. Complex selectors, at-rules,
  Flexbox, Grid and the full CSS box model remain absent.
- Gzip decoding for HTML and stylesheet responses, with decoded-size limits.
- A native form panel for same-origin GET/POST URL-encoded forms. Text, password,
  hidden, checkbox and textarea controls are included. Required fields are checked;
  password forms require HTTPS. Select, radio, file, multipart, form overrides and
  disabled-fieldset forms are explicitly refused. Native forms use the original
  page source and do not execute JavaScript form handlers. Cookies and login sessions
  are not implemented, so ordinary account sign-in is still unsupported.
- Local `.docx`, UTF-8 `.txt` and `.md` reading. Word body/table text is extracted
  without macros or external entities. Word formatting/images and PDF are not yet
  included. Read page exposes selectable text, search and local document notes.
- English/Spanish speech using installed system voices on request. Windows needs
  an installed System.Speech voice; native Linux needs espeak-ng/espeak; Android
  selects an installed voice that does not require a network connection. Speech
  output still needs physical-device verification. The Flatpak does not yet bundle
  a speech engine.

Pages declaring Content Security Policy keep scripts and automatic image/stylesheet
fetches disabled until full policy enforcement exists. Failed assets do not block
the text page. No cross-origin asset policy bypass was added.

## Desktop

The menu includes Read page, Open document, Restore session and Reopen tab. The home
page exposes the document reader and previous session. Ctrl+O opens a document;
Ctrl+F highlights matching words in the rendered page (the reader can search whole
phrases); Ctrl+Shift+T reopens a closed tab; Ctrl+R/F5 reload the current URL.
Reloading a form response uses GET and never automatically repeats its POST body.

Saved sessions restore website tabs and scroll positions when requested. Startup
does not immediately fetch every saved website. Existing bookmarks and settings
remain in the same profile; form values and passwords are not stored in sessions.

Unchanged script ticks now return a small state response instead of serializing the
entire page. Supported DOM/style mutations invalidate the cached snapshot. Changed
pages still reparse and lay out in full: incremental layout remains future work.
Drawing and link hit testing use a vertical index, and desktop font objects are
cached. These changes do not establish a speed advantage over other browsers.

## Android

Menu → Tabs supports up to 12 tabs and restoring saved tabs. Switching tabs reloads
the page; unsaved form input is not retained. Menu → Read page / Find provides text
selection/search, notes and speech. Menu → Open document uses Android's file picker.
Menu → Page forms opens the native form controls.

Opening a direct downloadable file offers Android Save As. It stages the transfer
in application cache, then copies the completed bytes to the selected destination.
The preview supports one transfer, 512 MiB per file, five redirects and a 15-minute
deadline. Cancellation can wait for an eight-second read timeout. Menu → Cancel
download cancels the active transfer. Closing/destroying the activity cancels it;
background-service transfers, authenticated downloads and resume remain absent.
No broad external-storage permission is required. Files are not automatically opened.

The APK contains Java bytecode with no CPU-specific native engine library. API 26
is the minimum; the emulator check covers API 26. Newer APIs and physical ARM devices
still require testing. Android scripts, advanced media and the AI companion remain
separate work.

## Packages and updates

The 0.2.1 packaging update adds permanent [GitHub preview releases](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview),
a per-user Windows setup EXE, a verified Flatpak replacement path, per-build
versions and a persistent Android signing gate. The browsing/reading features
above remain included. Follow the [download and update guide](../../DOWNLOADS.md)
for the current package names and installation steps.

Windows setup keeps the same application identity and Java Preferences profile.
Linux Flatpak keeps `io.aster.browser.EnginePreview` on the `preview` branch;
`flatpak install --user --or-update ./aster-linux-x64.flatpak` installs or replaces
it without removing its saved profile. The native Linux tarball and Flatpak
have separate profiles. Desktop packages are x64; every Linux distro, CPU and
physical Steam Deck has not been verified.

Android update releases require the owner's persistent private signing secret.
Until it is configured, the clearly named testing APK has disposable signing and
cannot promise cross-run updates. The publisher refuses a subsequent change to
an established release certificate or a non-increasing Android versionCode.
Earlier differently signed APKs and the old Android Lite need a future migration
path. Do not uninstall an app containing notes/bookmarks you need.

Windows native video remains a known verification blocker: the 0.2 checks observed
an HLS clock stall and, on another runner, a native `ERROR_MEDIA_INVALID` decoding
error. Browsing, reading and native-window checks passed separately. Windows
packages are retained only if those checks and the real setup/update test pass;
failed media results remain visible in their report and the overall workflow.
Windows publisher signing is still unconfigured.

Release assets have no 14-day Actions artifact expiry. Updates are initiated by
downloading a newer package; there is no silent background updater. All downloads
are development previews rather than a completed, stable browser.

## Validation and remaining work

The new ReadingTests suite exercises actual HTTP/gzip/CSS, image decode and painted
pixels, form POST bytes, bounded document extraction and DTD refusal, shared Android
download bytes, idle script snapshot suppression and desktop session restoration.
Existing script/download/network/media/UI tests remain in the build. The Android
emulator checks actual image pixels, form submission and native reader controls in
addition to navigation, bookmarks and same-key APK replacement. Flatpak has a
separate install-and-native-window gate. Require the corresponding green job before
claiming a package passed on that platform.

Full HTML/CSS/DOM compatibility, cookie/storage/login support, per-site OS sandboxing,
PDF and the local AI companion, WebRTC, WebGL, MSE/EME and legitimate DRM/service
integration are still required. Prime Video and cloud gaming remain unsupported.
This is a concrete browsing/reading milestone toward the larger browser, not a
completed implementation of the entire modern web platform.
