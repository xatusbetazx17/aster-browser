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
| Desktop UI | Native window, up to 20 tabs, up to 30 persisted bookmarks, keyboard shortcuts |
| Android UI | Single page, navigation menu, persisted bookmarks, restored address after rotation |
| Network | Platform TLS validation; no certificate bypass, cookies, credentials or subresource fetches |
| Android DRM | Device Widevine query through Android `MediaDrm`; no provisioning/license request or playback |
| Not implemented | Modern HTML recovery, full DOM/CSS, selectors/stylesheets, images, forms, JavaScript, storage/cookies, process sandbox, downloads, video, MSE/EME, WebRTC, the companion/reader feature set |

Source limit: 1 MB, nesting: 64, text runs: 20,000, painted fragments: 100,000.
Redirects cannot switch HTTPS to HTTP or invoke local/executable URL schemes.
Explicit HTTP navigation remains supported and unencrypted. Unsupported CSS is
ignored. There is no standards-compliance, daily-driver security or all-sites claim.

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
workflow packages and launches the Linux and Windows applications. Its Android
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
