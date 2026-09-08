# Aster original engine preview

**Version 0.2:** see [the browsing/reading release notes and package guide](RELEASE_0.2.md)
for shared images, the CSS/form subset, search, reading/notes/speech, saved sessions,
Android Save As and the new Linux Flatpak build. Full web compatibility remains unfinished.

This is a **new, limited engine implementation**, with its own HTML token handling,
typography, line layout, link hit testing and display list. It uses no Chromium,
Blink, Gecko, WebKit, JavaFX WebView, Android WebView or external browser. It is
not a fork or a renamed distribution of any of those engines.

The same Java core runs in a Windows/Linux desktop application (Swing window,
Java2D drawing, a bundled OpenJDK runtime) and a native Android application
(Android widgets, Canvas and ART). Java is the implementation language/runtime;
the desktop adds standalone **QuickJS-NG 0.16.2** for JavaScript and **JavaFX
21.0.12 media/graphics/Swing** for unencrypted playback. These are components,
not browser engines; the interpreter and decoders are third-party code.
Aster owns its small page renderer, DOM bridge and browser controls. Android shares the browsing/reading features but does not include desktop scripting/media.

**This is not the full browser Marcelo requested.** It opens basic HTML/text
websites. It can run limited scripts and direct media files, but cannot run Prime Video,
Boosteroid or full modern web applications.
It does not replace the more capable [Linux WebKit prototype](../webkit/README.md)
or silently remove that prototype's reader and local companion.

## Implemented and absent

| Area | Preview behavior |
| --- | --- |
| Navigation | HTTP/HTTPS address entry, relative links, bounded redirects, Back, Forward, Home |
| Layout | Text, headings, paragraphs, lists, basic table text, preformatted lines, Unicode, word wrapping |
| Typography | Bold, italic, inherited size/color; inline `font-size` in px, six-digit hex `color`, bold/italic declarations |
| Images | Bounded same-origin PNG/JPEG/GIF decoding; alternative text on unsupported or failed resources |
| Desktop UI | Rectangular tabs with individual close buttons, hover/close animations, adjacent new-tab button, rounded controls, flat internal pages, up to 20 tabs and 30 persisted bookmarks |
| Android UI | Up to 12 tabs, search, native forms, document reader, notes/speech, Save As, bookmarks and saved sessions |
| Network | Platform TLS validation; no certificate bypass, cookies or embedded URL credentials; desktop adds bounded same-origin scripts/fetch/WebSocket and explicitly requested media |
| Desktop downloads | User-selected Save As, direct HTTP/HTTPS transfers, progress, cancel/retry, two active transfers, 2 GiB/file |
| Android DRM | Device Widevine query through Android `MediaDrm`; no provisioning/license request or playback |
| Desktop scripting | Opt-in classic JavaScript, a small DOM/event bridge, timers/Promises and native input through QuickJS |
| Desktop media | Progressive MP4/M4A/MP3/WAV and unencrypted HLS; page play/pause/volume/mute/events and file seeking. HLS seeking is disabled. JavaFX Media without JavaFX WebView |
| Not implemented | Modern HTML recovery, full DOM/CSS/box layout, complex selectors, advanced forms, storage/cookies, per-site OS process sandbox, Android scripts/media, MSE/EME, WebRTC, PDF and the AI companion |

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
These changes target the original-engine **desktop** app; the separate Linux WebKit prototype retains its existing interface; Android has its own native controls.

## Try scripting, media and controller input

Open **Menu → Playground**, or enter `aster:playground`.

1. Select **Run JavaScript**. The heading changes to “JavaScript is running”.
2. Click **Add one** on the rendered page; the counter increases.
3. Click the page and press an arrow key; the page reports the key.
4. Optionally select **Enable controller**. A readable gamepad reports its axes and
   buttons. Windows uses XInput standard mapping; Linux uses `/dev/input/js0`–`js3`
   with raw device mapping. No permissions are changed and inaccessible devices
   remain unavailable. Physical controllers and Steam Deck controls still need testing.
5. Select **Play video** in the page to play the bundled four-second blue/red video.
   The player appears above the page; scroll down to **Pause video** or **Restart
   video**. The page's time display comes from the actual player. **Close player**
   removes it. **Play sample** in the toolbar also works with scripts disabled.
   This is unencrypted H.264 video, not a DRM test.

Website pages have the same **Run JavaScript** control. Scripts are off initially
and permission lasts only for that visit. Stop, navigation, tab close and browser
close terminate that page's script process. Background tabs pause timer dispatch;
switching away from a page with controller access **stops its scripts** to revoke
input access. Run scripts again when returning. Up to four script pages may exist.

Implemented script APIs: classic inline scripts and same-origin external JavaScript
responses; basic DOM text/attribute changes, `getElementById`, tag/ID/class selectors,
node creation/appending/removal, click listeners, keydown/keyup, title changes,
DOMContentLoaded/load, Promises, timers, requestAnimationFrame and gamepad snapshots.
Aster reparses text snapshots for its own renderer. This is not a complete DOM,
HTML parser, CSS engine or event model. Scripts execute after initial parsing;
modules, inline HTML event attributes, XHR, cookies/storage, JavaScript form handling and canvas/WebGL
are not implemented. Native forms are available separately. The network and media bindings below are subsets. Keyboard
code/repeat and default-action behavior are preliminary; there is no pointer lock.
Pages with a CSP header or meta policy refuse scripts until policy support exists.

QuickJS runs in a separate process with a 32 MiB allocation limit, 1 MiB C stack,
500 ms per command, a two-second parent watchdog, bounded Promise jobs and 2 MiB
protocol frames. A page has at most 10,000 DOM nodes, depth 64, 64 timers and 32
classic scripts totalling 1 MB. The process exposes no filesystem, shell, Java
objects, raw sockets, standard-library modules or module loader. Network requests
go through a separate validated Java broker. **This is not an OS
sandbox**: protection against a native runtime exploit still requires platform
process isolation. This remains an opt-in development preview for controlled pages.

### Page networking

Opt-in desktop scripts can use `fetch(url, options)` with a URL string, GET/HEAD/
POST/PUT/PATCH/DELETE/OPTIONS, string or ArrayBuffer/view bodies, `Headers`, and
`Response.text()`, `.json()`, `.arrayBuffer()` and `.clone()`. HTTP 4xx/5xx responses
resolve normally; transport and policy errors reject. `AbortController` cancels
pending work. UTF-8 `TextEncoder`/`TextDecoder` and `atob`/`btoa` are included.

Connections and redirects must stay on the page's origin. Cross-origin CORS,
credentials/cookies, service workers, streaming request/response bodies, compressed
responses, `Request`/`Blob`/`FormData`, manual redirects and a complete URL API are
not implemented. Browser-controlled headers cannot be supplied by scripts and
Set-Cookie response headers are withheld. Four HTTP requests may run per page;
request bodies are limited to 256 KiB, responses to 1 MiB, redirects to five and
the total fetch lifetime to 15 seconds. Abort, navigation and Stop JavaScript
cancel network work.

`WebSocket` uses actual WS/WSS handshakes, protocols, open/message/error/close
events, text and ArrayBuffer messages, send buffering and closing handshakes.
Its endpoint must match the page's host, port and security (HTTP→WS, HTTPS→WSS).
There are no login cookies or cross-origin sockets. Four connections, 256 KiB per
message/send buffer and bounded event queues prevent unbounded buffering in a
background page. Only `binaryType='arraybuffer'` is supported, including as the
preview default; Blob delivery is absent. Closing the page aborts every socket.
This persistent message transport does **not** implement WebRTC video transport.

### Play and stream media

Open a direct `.mp4`, `.m4a`, `.mp3`, `.wav` or `.m3u8` HTTP/HTTPS URL and choose **Play in
Aster** on the file offer. A basic page containing `<video src>`, `<audio src>` or
`<source src>` has **Play media** for its first detected source. The native player
has play/pause, restart, volume and close controls. A page's `<video>` or `<audio>`
can call `play()`/`pause()`/`load()` and set `currentTime`, `volume` and `muted`.
It receives metadata, play/playing/pause/timeupdate/volumechange/ended/error events,
dimensions and playback state. The play Promise settles from actual native playback.
A new player/source requires a real page click; startup autoplay rejects with
`NotAllowedError`. One page-controlled player is shown above the rendered page.
HLS seeking is disabled because native MPEG-TS seeking stalled playback in Linux
and Windows tests. HLS has an empty `seekable` range; assigning `currentTime`
throws `NotSupportedError` without stopping playback. **Restart** reopens its
decoder from the beginning. Ordinary file media supports seeking, including from
pause. This is a subset of HTMLMediaElement, without tracks, source objects, encrypted
media, playback-rate controls or the complete media event/ready-state algorithms.

Aster's private, loopback-only media transport forwards progressive bytes and
byte ranges to the decoder. HLS master/variant playlists are rewritten so every
segment remains under Aster's URL validation. Remote media no longer has to finish
downloading first. TLS is validated; HTTPS downgrade, mixed media, cross-origin
playlist resources/redirects, encrypted HLS and unsupported playlist tags are
refused. No keys are fetched and no login cookies are sent. The unguessable decoder
URLs are not exposed to scripts, and requests from webpage origins are refused.

Limits: 128 KiB/playlist, 2,048 distinct HLS resources, 64 MiB per resource/range
response, 1 GiB total transfer per player, five redirects and 60 seconds per
resource. Only an on-demand H.264/AAC MPEG-TS HLS fixture is tested. Live playlist
refresh and automatic bitrate switching rely on JavaFX and remain unverified;
cross-origin CDNs and other HLS profiles may fail. Closing playback stops the
transport; bundled samples use temporary files removed after decoder disposal.
Native codec failures are reported in the player.

This does not implement **WebRTC, MSE/DASH, EME or Widevine**. Prime Video,
Boosteroid, GeForce NOW and Xbox Cloud Gaming remain
unverified and unsupported. The [streaming development status](../../docs/setup/streaming.md)
explains the remaining engine work and authorized DRM requirements.

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
URL may fail when fetched again. Android 0.2 supplies a separate native Save As path; see the release notes for its limits.

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
libraries/X11 or XWayland and compatible glibc. CI builds on Ubuntu 24.04. The additional Flatpak package provides a common runtime. Neither package proves every distribution or Steam Deck; desktop ARM is not built.
The desktop source currently builds for Linux/Windows x64 with Java 17+, a C
compiler, the pinned native script host and matching JavaFX libraries. Keep the
whole `build/jar` directory together when launching its JAR. Linux media also needs
compatible GTK3, ALSA and libavcodec/libavformat system libraries; CI tests Ubuntu
24.04, not every distribution. SteamOS requires no read-only filesystem
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

Use JDK 17, Python 3 and a C compiler on the target Linux/Windows x64 platform.
Windows additionally uses CMake and the Visual Studio C build tools. The builder
downloads SHA-256-pinned QuickJS source and JavaFX jars/notices over HTTPS; it builds
the narrow script host from source. No browser engine or JavaFX WebView is included.

```sh
python experiments/aster-engine/build.py desktop --test --package
```

To use your system Java 17 runtime, omit `--package`, keep the **whole `build/jar`
directory** together (including `native` and `lib`), then run:

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

`build.py desktop --test` runs 13 core tests plus download, script and desktop
integration suites, including actual localhost HTTP exchanges,
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

The scripting suite launches real QuickJS processes and verifies same-origin
script loading, DOM/title/click/key updates, timer/Promise execution, controller
permission/revocation, CSP refusal and CPU/memory/stack/Promise-job bounds. Desktop
tests activate Run JavaScript and click the actual rendered playground button,
then check tab-switch input revocation and process termination on navigation.
The separate native media CI check requires advancing playback time, 160 × 90
H.264 decode and two actual blue/red video frames. Local display startup was
unavailable in the coding workspace; require successful native CI checks for
platform playback evidence. Neither speaker output nor a physical controller
has been tested, and none of these fixtures exercises WebRTC or DRM.

The network suite uses real HTTP and RFC 6455 peers, including Unicode/binary
responses, POST, redirects/errors, aborted requests, WebSocket fragments, ping,
close and tab-lifetime cancellation. Transport tests require progressive bytes
before EOF, byte ranges and master/variant/segment HLS requests, and refuse
encrypted or unsafe manifests. The native `--stream-smoke` gate additionally
requires a rendered page click, refused autoplay, page-controlled HLS playback,
actual blue/red frames, play Promises, pause/resume/volume/mute, HLS seek refusal
and restart, a separate progressive MP4 seek with an observed clock jump, and navigation
cleanup. Native CI must pass before treating these checks as platform evidence.
Linux HLS checks decode a silent AAC track into a PulseAudio virtual sink. The
Windows hosted runner reports no sound device, so its HLS fixture has video only;
Windows audio decoding/output is not verified. Both platforms retain actual
decoded-frame, page-control and transport assertions.
The HLS fixtures are authored blue/red video with optional silent AAC, encoded from the
bundled sample using ffmpeg/libx264, 12 fps, baseline profile, 24-frame keyframes,
two-second MPEG-TS segments and low/high CRF 32/18. No commercial media is included.

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
runtime's `legal` directory. QuickJS's MIT notice is beside the script executable;
JavaFX and its third-party notices/source links are in the app's `legal/javafx`
directory. Dependency pins are in `desktop/native/*.lock.json`.
Platform drawing/TLS/media libraries are not Aster-authored.
