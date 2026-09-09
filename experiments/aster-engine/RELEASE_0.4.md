# Aster 0.4 — website sessions and measured rendering

This is a further implementation milestone for Aster's original engine. It does
**not** enable Netflix, Prime Video or cloud gaming, or make Aster a complete web
browser. It retains the 0.3 workspace and the same application/profile identities.
The renderer remains Aster's own Java implementation; no Chromium, Gecko, WebKit
or WebView has been introduced.

## Implemented in this milestone

- **Shared website sessions on desktop and Android:** native form POST, redirects,
  navigation, same-origin CSS/images, and explicit downloads use the profile's
  cookie store. Desktop classic scripts, fetch, WebSocket handshakes and explicitly
  opened media also use it. Tests establish a session at a real local HTTP server,
  carry it through these paths, and sign out by clearing website data.
- **Cookie security and lifetime:** Secure, HttpOnly, path boundaries, default
  paths, Max-Age/Expires, deletion, restricted SameSite handling and cookie prefix
  checks. HttpOnly values are withheld from `document.cookie`; scripts cannot
  overwrite them. Cookies with an explicit expiry persist; session cookies live
  only in the process. Responses from requests that predate clearing are revoked.
- **Desktop website storage:** synchronous `localStorage`, per-tab
  `sessionStorage`, and `document.cookie`, through a bounded native IPC broker.
  The broker uses the loaded document's trusted origin, irrespective of changes
  to JavaScript's URL properties. Storage supports string conversion, key/length,
  get/set/remove/clear, and ordinary named-property access. Local storage changes
  are immediately visible to another same-origin tab; session storage survives
  navigation in its tab and disappears when the tab closes.
- **User control:** desktop Settings and Android's menu offer Clear website data.
  Desktop returns open tabs to Home and clears their session storage; Android
  returns the current tab to Home. Bookmarks, reader notes and downloaded files
  remain saved. Clearing signs out of sites using these cookies.
- **Measured text layout:** a bounded, per-layout cache reuses repeated complex
  script word measurements. ASCII words keep the inexpensive native measurement
  path. Cached and uncached geometry is compared, including Unicode, styles,
  links and cache saturation. The cache is released after each layout.
- **Reproducible measurements:** `--benchmark` records raw samples, medians and
  95th percentiles for parsing, layout and Java2D viewport painting. It compares
  Aster's cached and uncached layout on identical source documents and publishes
  the environment, source hashes, viewport, draw counts and measurement counts.

## Boundaries of the session implementation

This is a deliberately restricted implementation informed by
[HTTP cookies](https://www.rfc-editor.org/rfc/rfc6265.html),
[Fetch](https://fetch.spec.whatwg.org/) and
[Web Storage](https://html.spec.whatwg.org/multipage/webstorage.html), not a claim
of conformance to their complete algorithms.

Cookies are isolated by **scheme, host and port**, more strictly than standard
browser cookies. `Domain` is accepted only when it names the exact response host,
and it still does not extend to subdomains. Parent-domain, third-party and
partitioned cookies are refused. Aster lacks a public-suffix list and complete
schemeful-site calculation. Cross-origin redirect chains do not regain Strict
cookies when they return to the initial origin. These restrictions can break
cross-subdomain login, SSO, payment and embedded account flows.

Cookies are limited to 4 KiB each, 50 per origin and 256 per profile; the request
header is bounded to 16 KiB and persistent lifetime is capped at 400 days. Only
three common HTTP date formats are parsed. Excess cookies are refused without
evicting existing authentication cookies. Credentials are not forwarded to
another origin's assets or redirected downloads. Fetch remains same-origin;
`credentials: 'omit'` neither sends cookies nor accepts Set-Cookie, while
`same-origin` and `include` use the restricted jar. Java WebSocket's handshake
API sends applicable cookies but does not expose response Set-Cookie for storage.

Desktop storage uses a 64 KiB UTF-16 quota per origin/area, 512 KiB per area,
64 origins and 256 entries per origin. There are no storage events, IndexedDB,
service workers or multi-process storage locking yet. The IPC has a two-second
watchdog, 256 KiB site-data frames, 1,024 calls and 4 MiB combined traffic per
script command. Website storage does not run on Android because Android
JavaScript remains absent. Existing CSP pages still disable scripts and automatic
assets until policy enforcement is implemented.

Persistent site data uses an atomic replacement file in the app's private
profile directory, with owner-only permissions on POSIX systems. It is not
encrypted or a password vault. Desktop uses `~/.aster-engine-preview/site-data.bin`
inside its native/Flatpak home; Android uses its app-private files directory.
Desktop flushes changes periodically and on exit; Android flushes navigation
changes and on stop. A process crash can lose unflushed changes. Opening multiple
independent desktop processes against this new store is not supported yet.

## What still prevents the requested services

| Requirement | Current state | Completion evidence needed |
| --- | --- | --- |
| Full websites/account applications | Basic sessions now work; DOM, CSS layout, URL/navigation, advanced forms, CORS, CSP and isolation remain incomplete | Web Platform Tests, hostile-origin fixtures and real supported-site flows |
| Netflix / Prime Video | Unsupported; no MSE, EME or approved CDM integration | Implement APIs/decoder paths, obtain licensed integration, then authenticated playback and provider acceptance on each supported platform |
| Cloud gaming | Unsupported; no WebRTC, full graphics or pointer/input platform | Real ICE/STUN/TURN, DTLS/SRTP, audio/video and data-channel interoperability; actual service/controller latency and frame tests |
| Windows HLS | Known freeze remains: previous native fixture reported PLAYING at about 1.42 s with a frozen blue frame | A decoder fix that passes both decoded colors, pause/resume and restart on Windows; current run results are in WINDOWS-BUILD-STATUS.txt |
| Android media | No native video pipeline or JavaScript yet | Android 8+ decoding, lifecycle/audio focus, media controls, then the browser API work above |
| Faster than other browsers | Not established | Matched supported workloads on the same hardware, browser versions, power/network conditions; startup, memory, interaction and frame delivery measurements |

[Widevine requires a license agreement](https://developers.google.com/widevine/drm/overview).
The repository has no licensed desktop CDM, partner credentials, content keys or
service approval. An Android MediaDrm capability query does not provide those.
An owner must complete the provider's licensing/onboarding process; code cannot
issue that approval. No DRM bypass, copied CDM or user-agent impersonation is used.

[Netflix](https://help.netflix.com/en/node/30081) and
[Prime Video](https://www.primevideo.com/help?nodeId=GUX9FYHU5D8LC9EJ) publish supported
browser requirements. Our assessment is that Aster needs the missing browser APIs
as well as authorized DRM before those services can be meaningfully qualified.
For engine contracts see [MSE](https://www.w3.org/TR/media-source-2/),
[EME](https://www.w3.org/TR/encrypted-media/) and
[WebRTC](https://www.w3.org/TR/webrtc/). The wider remaining work is recorded in
[the 0.3 roadmap](RELEASE_0.3.md#work-still-needed-for-a-full-independent-browser).

## Reproduce and install

```sh
python experiments/aster-engine/build.py desktop --test
java -Djava.awt.headless=true -jar experiments/aster-engine/build/jar/aster-engine-preview.jar --benchmark benchmark.json
```

The benchmark uses three fixed offline documents at 720×800 and 1280×800,
15 warm-up and 40 measured iterations, alternating cached/uncached layout order.
It excludes network, page JavaScript, protected video, cold startup, RSS and other
browsers. Host load, fonts and JIT compilation affect results; inspect all samples
and repeat on the same hardware. These are scoped engine measurements, not a
daily-use speed ranking. CI attaches the Windows/Linux results to the release.

Use the [latest release](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)
and [installation instructions](../../DOWNLOADS.md). Windows and Linux x64 retain
the tested replacement mechanism; Flatpak covers compatible distributions rather
than every Linux/CPU. Android remains minimum API 26. A permanent private Android
signing key still needs owner configuration before separate CI releases can
update each other's APK. Read the per-platform status files for actual native
test outcomes; no premium-service or physical-device test is implied.
