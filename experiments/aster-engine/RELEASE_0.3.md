# Aster 0.3 — a connected browsing and reading workspace

This preview brings the recognizable dark Aster interface to the original engine.
It keeps the same Windows, Linux and Android application/profile identities used
by 0.2. The renderer is still Aster's own Java implementation. Desktop uses
standalone QuickJS and JavaFX media components; no Chromium, Gecko, WebKit or
WebView renderer is embedded. The engine is experimental, not a complete browser.

## What changes

- **Desktop workspace:** dark navy surfaces, mint star branding, readable native
  controls, a compact tab strip and sidebar access to reading, documents, saved
  pages, downloads and settings. Website colors remain controlled by the page.
- **Reading beside browsing:** Read or Ctrl+Shift+R pins selectable page text in
  a resizable panel. Open document uses the same space for DOCX/text/Markdown.
  Narrow windows stack reading below the page. Changing browser tabs does not
  replace the pinned document or its notes.
- **Notes and reading position:** notes save automatically to the existing
  per-document profile key. Switching documents and closing the panel flush the
  pending save. Notes are limited to 6 KB; additional input is refused with an
  explanation, rather than silently truncated. Cursor position is retained per
  reading document. English/Spanish speech uses an installed offline system voice;
  Flatpak still does not bundle a speech engine.
- **Actual tab parking:** Park or the tab context menu releases the parsed page,
  layout, font cache and decoded images. The URL, title, Back/Forward history and
  scroll position stay available. Selecting a parked tab requests the page again
  without adding an extra history entry. POST responses, pages with forms,
  running scripts, active media and unfinished navigation cannot be parked.
- **Optional automatic parking:** Settings can park older background website
  tabs above a chosen live-tab limit (2–8, default 4). This is off initially.
  Protected pages can exceed that limit; it is not a hard memory cap. The sidebar
  and dashboard report actual live/parked counts, not invented memory readings.
- **Lazy session restoration:** Restore tabs loads only the selected saved website.
  Other saved tabs remain parked until opened. Startup continues to offer the
  saved session without automatically contacting those websites.
- **Navigation:** Ctrl-click, Command-click, middle-click and the link context
  menu open background tabs. Ctrl+F searches phrases across rendered words;
  Enter/F3 and Shift+F3 advance through matches. Escape closes search or stops
  navigation. Reload becomes Stop during a request. F11 uses the desktop's
  full-screen window mode.
- **Android:** a matching dark native home and chrome, touch-sized Back, Forward,
  Tabs and Read controls, and a Stop loading command. Each tab retains its own
  Back/Forward history while the activity is alive. Inactive tabs hold URL/title/
  position/history metadata; switching reloads the page. Saved sessions retain
  URL/title/position, not passwords, form input or full history across restarts.

The original parser, small CSS/form subset, downloads, bookmarks, reader, desktop
scripts and unencrypted media from [0.2](RELEASE_0.2.md) remain included.

## What websites can use today

| Capability | Windows/Linux desktop | Android 8+ |
| --- | --- | --- |
| Basic HTML, small CSS subset, same-origin images | Implemented with bounded resources | Same shared engine |
| Basic same-origin native GET/POST forms | Implemented; no login sessions | Implemented; no login sessions |
| Downloads, bookmarks, text/DOCX reading and notes | Implemented | Implemented |
| JavaScript, DOM, fetch and WebSocket | Limited, opt-in desktop subset | Not implemented |
| Unencrypted MP4/HLS | Existing native fixtures; Windows HLS remains unreliable | Not implemented |
| Full CSS layout, DOM, cookies and web storage | Not implemented | Not implemented |
| Media Source Extensions and Encrypted Media Extensions | Not implemented | Not implemented |
| Netflix / Prime Video playback | Unsupported | Unsupported |
| Cloud gaming / WebRTC | Unsupported | Unsupported |
| Extensions, container profiles, full PDF, AI companion | Not implemented in this engine | Not implemented in this engine |

The same limits are visible in desktop **Website compatibility** and Android's
**Streaming support** / **About this preview** controls. A successful local video
fixture or an Android Widevine hardware query does not establish service support.

[Netflix](https://help.netflix.com/en/node/30081) and
[Prime Video](https://www.primevideo.com/help?nodeId=GUX9FYHU5D8LC9EJ) publish their
supported browser requirements; Aster is not listed.
[GeForce NOW](https://www.nvidia.com/en-us/geforce-now/system-reqs/) also specifies
supported browser/device and network requirements. Our engineering assessment is
that the missing APIs below must be implemented and tested before attempting to
qualify Aster for those services. Changing its user agent is not an implementation.

## Work still needed for a full independent browser

1. **Standards and isolation:** HTML recovery, the CSS box model, Flexbox/Grid,
   complete DOM/events, accessible form controls, cookies/storage, origin and CSP
   enforcement, secure permissions and OS-level isolation. Add scoped Web Platform
   Tests and hostile-origin fixtures as each subsystem lands. Account login needs
   these foundations, not just native password fields.
2. **Media platform:** stable decoding on the supported machines, buffered seek,
   tracks/captions, audio policies and the browser media object model. Implement
   [Media Source Extensions](https://www.w3.org/TR/media-source-2/) before claiming
   modern adaptive streaming support. The existing unencrypted HLS relay is a
   limited native playback path, not MSE.
3. **Protected playback:** implement
   [Encrypted Media Extensions](https://www.w3.org/TR/encrypted-media/), an approved
   content-decryption module, licensing/provisioning and protected output paths.
   [Widevine requires a license agreement](https://developers.google.com/widevine/drm/overview).
   No private CDM, service credentials or DRM keys are included in this repository.
   Provider approval and actual authenticated playback tests remain separate gates.
4. **Cloud gaming:** implement and test
   [WebRTC](https://www.w3.org/TR/webrtc/) transport, real-time audio/video,
   graphics, pointer lock, full input/gamepad behavior and service authentication.
   Measure latency and frame delivery on actual controllers, networks and devices.
5. **Daily use and distribution:** richer history and bookmark management,
   import/export, PDF, container profiles, content blocking, extensions, signed
   releases and recovery. Expand accessibility, physical Android/Steam Deck,
   Linux distribution/CPU, Windows and Android-version coverage. Benchmark startup,
   scrolling and memory using identical workloads before making speed comparisons.

## Install, update and verify

Use the [latest preview release](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)
and [installation guide](../../DOWNLOADS.md). Windows setup and Linux Flatpak keep
their stable identifiers and profiles. Desktop downloads are x64, not every CPU
or Linux distribution. Android remains minimum API 26; a permanent private signing
key must be configured before releases can promise updates between CI runs.

`python experiments/aster-engine/build.py desktop --test` runs the existing
engine/network/script/reading checks and the new HTTP-backed workspace checks:
background link navigation, phrase search, resource release/resume, form/POST
protection, cancellation, lazy restore, responsive reader and persistent notes.
The Android emulator check exercises the visible navigation controls and tab
history in addition to the existing image, form, reader and package-update checks.
Per-platform release status files report native CI results; physical device and
premium-service playback have not been verified. This release makes no claim of
being faster than Chrome or Firefox, or supporting every website.
