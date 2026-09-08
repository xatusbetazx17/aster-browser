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

Use artifacts from a successful run of
[Aster original engine preview](https://github.com/xatusbetazx17/aster-browser/actions/workflows/aster-engine-preview.yml)
on `codex/aster-webkit-desktop`. All packages are development previews, not signed
stable releases. Checksums are included.

| Artifact | Contents and intended use |
| --- | --- |
| `aster-engine-Windows` | Windows x64 portable EXE bundle with Java runtime. Extract the entire inner ZIP; run `AsterEnginePreview.exe`. |
| `aster-engine-Linux` | Linux x64 bundle built on Ubuntu 22.04 for an older glibc baseline. Extract the tarball; run `AsterEnginePreview/bin/AsterEnginePreview`. |
| `aster-engine-Linux-ubuntu24` | Additional native Ubuntu 24.04 build and tests. |
| `aster-engine-Linux-Flatpak` | Linux x64 package using Freedesktop 25.08. Requires Flatpak and network access to install its runtime initially. |
| `aster-engine-Android` | Android 8+ APK and emulator evidence. Development signing limitations below apply. |

On Linux systems with Flatpak installed:

```sh
flatpak install --user ./aster-linux-x64.flatpak
flatpak run io.aster.browser.EnginePreview
```

The Flatpak uses X11 or XWayland, graphics/audio access, network, the Downloads
directory and read-only Documents access. Other folders require explicit user
permission. It uses a separate application profile. A consistent runtime reduces
host distribution dependencies, but does not prove every distribution, CPU,
graphics driver or Steam Deck control configuration works. ARM desktop builds,
musl-native binaries and headless Linux are not supplied.

Close a native desktop preview before extracting a newer bundle into a new folder.
Keep the earlier directory for rollback; the profile is stored separately. Flatpak
updates use a newer bundle of the same application ID. There is no automatic update
feed yet.

Android CI still uses an ephemeral development signing key per run. Different runs
cannot upgrade each other in place. A maintained private signing identity and an
export/migration workflow are required before distributing durable Android updates.
Do not uninstall a preview containing bookmarks or notes you need to keep. Local
builders can preserve their existing development keystore to install updates with
the same identity. Windows publisher signing also remains unconfigured.

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
