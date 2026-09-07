# Aster original engine preview

This is a **new, limited engine implementation**, with its own HTML token handling,
typography, line layout, link hit testing and display list. It uses no Chromium,
Blink, Gecko, WebKit, JavaFX WebView, Android WebView or external browser. It is
not a fork or a renamed distribution of any of those engines.

The same Java core runs in a Windows/Linux desktop application (Swing window,
Java2D drawing, a bundled OpenJDK runtime) and a native Android application
(Android widgets, Canvas and ART). Java is the implementation language/runtime;
there is **no JavaScript interpreter** in this preview.

**This is not the full browser Marcelo requested.** It opens basic HTML/text
websites. It cannot run Prime Video, Boosteroid or other JavaScript/video sites.
It does not replace the more capable [Linux WebKit prototype](../webkit/README.md)
or silently remove that prototype's reader and local companion.

## Implemented and absent

| Area | Preview behavior |
| --- | --- |
| Navigation | HTTP/HTTPS address entry, relative links, bounded redirects, Back, Forward, Home |
| Layout | Text, headings, paragraphs, lists, basic table text, preformatted lines, Unicode, word wrapping |
| Typography | Bold, italic, inherited size/color; inline `font-size` in px, six-digit hex `color`, bold/italic declarations |
| Images | Alternative text only; image bytes are not fetched or decoded |
| Desktop UI | Rectangular tabs with individual close buttons, hover/close animations, adjacent new-tab button, rounded controls, flat internal pages, up to 20 tabs and 30 persisted bookmarks |
| Android UI | Single page, navigation menu, persisted bookmarks, restored address after rotation |
| Network | Platform TLS validation; no certificate bypass, cookies, embedded URL credentials or subresource fetches |
| Desktop downloads | User-selected Save As, direct HTTP/HTTPS transfers, progress, cancel/retry, two active transfers, 2 GiB/file |
| Android DRM | Device Widevine query through Android `MediaDrm`; no provisioning/license request or playback |
| Not implemented | Modern HTML recovery, full DOM/CSS, selectors/stylesheets, images, forms, JavaScript, storage/cookies, process sandbox, Android downloads, video, MSE/EME, WebRTC, the companion/reader feature set |

Source limit: 1 MB, nesting: 64, text runs: 20,000, painted fragments: 100,000.
Redirects cannot switch HTTPS to HTTP or invoke local/executable URL schemes.
Explicit HTTP navigation remains supported and unencrypted. Unsupported CSS is
ignored. There is no standards-compliance, daily-driver security or all-sites claim.

## Desktop interface update

The Windows/Linux original-engine application now includes:

- Rectangular tabs with an individual vector close button. Selected and hovered tabs
  reveal the close button; keyboard focus reveals it too. Hover fades take 120 ms;
  closed tabs shrink away over 160 ms. Closing a background tab preserves the active
  tab; closing the final tab opens a fresh home page.
- A compact new-tab button immediately after the tabs, a horizontally scrollable
  tab strip for many tabs, rounded toolbar controls and a 2-by-2 tools menu.
- A native, flat home dashboard with six shortcuts, readable typography and a
  110 px open-tab capacity ring. Counts reflect this application session; they do
  not claim CPU/memory measurements or streaming readiness.
- `aster:bookmarks` with saved links and explicit clearing; `aster:history` with
  the last 200 successful visits from this session; `aster:settings` with persisted
  reading size and reduced motion. Clearing bookmarks preserves settings.
- `aster:downloads` now manages real desktop file transfers: paste a direct URL,
  choose a destination, see progress, cancel or retry. Clear finished entries
  without deleting saved files.
- No permanent bottom status bar. Pending navigation, confirmation and errors
  appear below the address bar and clear on successful navigation.
- Wheel scrolling over the closed reading-size dropdown scrolls the page instead
  of changing the setting. Choose a size deliberately from the dropdown.
- The native home page is prepared synchronously before the window is first
  shown. This avoids an initially empty page; it does not certify every platform's
  compositor or graphics driver as flicker-free.

Use Ctrl+T / Ctrl+W for new/close tab, Ctrl+Tab / Ctrl+Shift+Tab to switch,
Ctrl+L for the address, Ctrl+D to bookmark, and Alt+Left/Right to navigate.
Reading sizes 100%, 125%, 150% and 200% preserve link hit testing.

Internal-page navigation is handled only by the desktop shell's address bar and
native controls. Webpage links and redirects cannot invoke internal settings.
These changes target the original-engine **desktop** app; the Android app and
separate Linux WebKit prototype retain their existing interfaces.

## Download files on Windows/Linux

Open **Menu → Downloads** or press **Ctrl+J**. Paste a direct HTTP/HTTPS file URL,
choose **Save link…**, and select a new destination filename. Aster never
silently overwrites an existing file or automatically opens/executes a download.

Other entry points:

- Opening a file link or an attachment shows a native **Save as…** page with its
  suggested filename, host and advertised size. Saving begins after choosing a path.
- Right-click a rendered website link and choose **Save link as…**.
- **Ctrl+S** saves the current website response (HTML/text source, not a complete
  offline archive with images or other page resources).

The Downloads page reports progress and errors, supports cancellation/retry, and
keeps up to 30 entries for the current session. Files remain at the paths you
selected after the list is cleared or the browser closes. Closing Aster with active
transfers asks whether to cancel them; normal shutdown lets workers clean up their
partial files. Cancellation during a silent network read waits for the read timeout.
A forced process kill can leave a `.aster-*.part` file beside the destination.

Limits: two concurrent transfers, 2 GiB per file, five redirects, a 30 minute
transfer deadline, normal platform TLS validation, no HTTPS-to-HTTP redirects and
no automatic HTTP content decoding. Incomplete responses and unsafe redirect
schemes fail; existing destination files remain protected, including collisions
that appear while a transfer is running. Server filenames are sanitized before
being offered in the save dialog. Transferred bytes are staged beside the chosen
destination; completion uses an exclusive new filename. Filesystems without hard
links use an exclusive-copy fallback during finalization.

This is direct GET downloading: there are no login cookies, authentication headers,
POST-generated files, JavaScript/blob URLs, pause/resume or cross-restart transfer
recovery. The Save As offer and the transfer use separate requests, so a one-use
URL may fail when fetched again. The Android preview currently reports file links
as unsupported for saving; its download UI has not been implemented.

## Try the actual packages

Open [Aster original engine preview builds](https://github.com/xatusbetazx17/aster-browser/actions/workflows/aster-engine-preview.yml),
choose a **successful run on `codex/aster-webkit-desktop`**, and download its artifacts.
GitHub may require sign-in. These are temporary development artifacts, not a stable
release channel. Each archive has SHA-256 checksums and test screenshots.

### Windows x64

1. Download `aster-engine-Windows` and extract it.
2. Extract its inner `aster-engine-windows-x64.zip` completely.
3. Open `AsterEnginePreview/AsterEnginePreview.exe`. Its Java runtime is included.
4. Try `https://example.com`, a link, Back, a new tab and a bookmark.

The app has no publisher code-signing certificate yet. Do not disable Windows
security protections to run it. It is a portable native launcher with a bundled
Java application, not an MSI installer or a full native port of the WebKit app.

To update, close the preview and extract a newer archive into a **new directory**;
launch that version. Bookmarks stay in the per-user Java Preferences node
`io/aster/engine-preview` (Windows stores Java Preferences in the user registry).
Keep the earlier directory to roll back. No existing Aster installation is converted.

### Linux x64 and Steam Deck desktop mode

Download `aster-engine-Linux`, extract it, then run:

```sh
tar -xzf aster-engine-linux-x64.tar.gz
./AsterEnginePreview/bin/AsterEnginePreview
```

The bundle contains Java. It still needs the operating system's desktop graphics
libraries/X11 or XWayland and compatible glibc. CI builds on Ubuntu 24.04. This is
not an all-distribution binary, Flatpak, ARM package or a validated Steam Deck release.
The source/JAR can run with a suitable system Java runtime on other distributions;
test that platform before claiming support. SteamOS requires no read-only filesystem
unlock for extracting the archive into your home directory.

Close the preview and extract a newer archive into a new directory to update;
bookmarks remain in Java's per-user preferences outside that directory. Keeping
the previous directory permits code rollback. WebKit profile data is separate.

### Android 8.0/API 26 or later

Download `aster-engine-Android`, extract it and open `aster-engine-preview.apk`
on a test device, using Android's per-source installation permission when requested.
The package name is `io.aster.browser.enginepreview`; it cannot replace the old
WebView-based Android Lite app. Do not use development builds for sensitive browsing.

This APK is development-signed. **CI generates an ephemeral signing key for each
run. APKs from different runs cannot be installed over each other.** Installing an
APK built with the same key preserves bookmarks; the emulator test verifies that
replacement. For ongoing personal builds, keep the generated keystore at
`~/.android/aster-engine-preview.keystore` or set `ASTER_PREVIEW_KEYSTORE` to your
development keystore path. It uses the documented development password `android`.
Do not use this development identity/password for production distribution.

A stable release signing identity, signed update channel and migration/export
workflow are still required. Do not uninstall an existing preview with bookmarks
you need to retain just to work around a signature mismatch.

## Build from source

Use JDK 17 and Python 3 on the target desktop platform:

```sh
python experiments/aster-engine/build.py desktop --test --package
```

For a portable JAR without bundling Java, omit `--package`, then run:

```sh
java -jar experiments/aster-engine/build/jar/aster-engine-preview.jar
```

For Android, install the official SDK's `platforms;android-35` and
`build-tools;35.0.0`, set `ANDROID_SDK_ROOT`, then run:

```sh
python experiments/aster-engine/build.py android
```

No Gradle or browser-engine dependency is downloaded by the builder. It uses
the SDK's AAPT2, D8, zipalign and apksigner directly. The output is
`experiments/aster-engine/build/android/aster-engine-preview.apk`.

## Validation scope

`build.py desktop --test` runs 13 tests, including actual localhost HTTP exchanges,
a deterministic malformed-markup corpus and Java2D pixel rendering. The new CI
workflow packages and launches the Linux and Windows applications. The desktop
integration tests exercise actual controls, a real local HTTP page, scaled link
clicking, Back, background/final-tab closing, settings wheel protection and
bookmark/settings persistence with isolated test profiles. They render home,
settings, history, bookmarks and downloads pages, including a 720 px wide home.
Separate real-HTTP download tests verify byte-for-byte binary saving above 1 MB,
attachment detection, filename safety, redirects, cancellation, declared/streamed
size limits, HTTP failures/truncation, concurrent-transfer limits, destination
collisions and normal-shutdown cleanup. A desktop control test opens a file link,
activates Save As with an isolated test destination, verifies the saved bytes and
renders the completed Downloads page. The native launch checks additionally
exercise hover fade and animated close cleanup. Tests do not overwrite a user's normal preview profile. Its Android
job builds/verifies the APK and tests native Canvas rendering, a real HTTP page,
a tapped link, Back, a bookmark-preserving same-key APK replacement and the actual
device DRM query in an API 26 emulator. A green job is required before citing its
platform result. Artifact creation alone is not a passed device test.

The API 35 attempts reached boot/installation timeouts on the hosted runner,
which denies this job access to KVM. That runtime validation remains unfinished.
CI now uses the smaller API 26/x86 image in software mode; API 35 remains selectable
for manual workflow runs on a suitably configured runner. The APK still compiles
against SDK 35, targets SDK 35 and declares API 26 as its minimum.

These tests do not establish Android API 35 or physical-device graphics,
screen-reader accessibility, all Linux distributions, Steam Deck controls,
production signing, modern-web compatibility or streaming service support.

See [the remaining engine and streaming work](../../docs/setup/streaming.md).
Aster code is MIT-licensed; bundled OpenJDK keeps its own licenses under the
runtime's `legal` directory. Platform drawing/TLS libraries are not Aster-authored.
