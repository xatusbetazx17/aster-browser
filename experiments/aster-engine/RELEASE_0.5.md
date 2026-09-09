# Aster 0.5 — web application requests

This release implements another part of Aster's own web platform: desktop pages
can exchange data with HTTP APIs across origins when those servers permit it.
It **does not finish full website compatibility or enable Netflix, Prime Video or
cloud gaming**. The existing workspace, profiles, sessions, reading tools and
renderer remain in place. No other browser engine or external browser is used.

## Implemented and exercised

- **Cross-origin fetch:** CORS Origin checks, credential rules, OPTIONS preflights,
  method/header permissions, response-header exposure and a bounded per-page
  preflight cache. Denied preflights never send the actual write. Denied responses
  do not expose their body or private headers to page code.
- **Redirect rules:** each cross-origin response is checked before following it;
  Authorization is removed when the origin changes; cross-to-cross redirect
  chains serialize Origin as `null`. A tainted chain returning to the first
  origin does not regain its cookies. `redirect: 'error'` refuses redirects;
  `manual` returns an opaque redirect response without its address, headers or body.
- **Request bodies:** `Request` construction, cloning and consumption; independent
  `URLSearchParams` with duplicate keys, scalar UTF-8 conversion, sorting, live
  iteration and form encoding; buffered `Blob`/`File`; programmatically populated
  `FormData` with multipart field/file serialization. These send actual bytes
  through the existing Java HTTP broker; they are not capability flags.
- **Response handling:** response headers arrive before the body is finished.
  Body promises complete when the transfer ends, or reject if it fails/aborts.
  Responses support text, JSON, bytes, ArrayBuffer, Blob and cloning. Network
  headers are immutable, Set-Cookie is withheld, and null-body statuses remain
  empty. Gzip and zlib-wrapped deflate are decoded with encoded/decoded byte bounds.
- **Asynchronous XMLHttpRequest:** open/send, request/response headers,
  readyState/status, partial UTF-8 text, text/JSON/ArrayBuffer/Blob responses,
  download progress, upload publisher progress, abort, timeout and reuse.
  It uses the same origin/credential checks as fetch. Upload listeners trigger
  cross-origin preflight even for an otherwise simple request.
- **Bounded delivery:** network events are delivered in smaller IPC batches and
  Base64 decoding writes directly into typed arrays, keeping a valid 1 MiB text
  response within the existing script command budget. Limits were not removed.
- **Shared navigation correction:** query-only and empty relative references keep
  the current path on desktop and Android. A link such as `?page=2` no longer
  accidentally changes `/folder/article` into `/folder/`.

The implementation is informed by [Fetch](https://fetch.spec.whatwg.org/),
[XMLHttpRequest](https://xhr.spec.whatwg.org/),
[form URL encoding](https://url.spec.whatwg.org/#application/x-www-form-urlencoded)
and [File API](https://w3c.github.io/FileAPI/). It is a bounded subset, not a claim
that these complete specifications or the full Web Platform Tests suite pass.

## Limits that still affect real websites

The new JavaScript APIs are **desktop only**. Android still uses the native
Canvas/forms path and has no JavaScript or video pipeline. Minimum Android remains
8 / API 26. This release does not expand CPU or distribution coverage.

Cookies remain restricted by scheme, host and port under the
[0.4 session policy](RELEASE_0.4.md#boundaries-of-the-session-implementation).
Cross-origin cookies are neither sent nor stored, including with
`credentials: 'include'`; that mode still requires the server's exact origin and
`Access-Control-Allow-Credentials: true`. Third-party state, parent-domain cookies,
SSO and a complete site/public-suffix implementation are unfinished. An explicit
script Authorization header requires preflight permission across origins and is
stripped on an origin-changing redirect. WebSockets, external classic scripts and
automatic images/styles remain same-origin.

CORS authorizes reading a response. Simple cross-origin requests can still reach
a server that does not allow reading its response; servers must enforce their own
write authorization. CORS is not a replacement for CSRF protection or process
isolation. Aster's script process still lacks a complete OS sandbox and local
network permission system; scripts remain opt-in for controlled testing. Pages
with CSP still refuse scripts and automatic assets pending full policy enforcement.

Other unavailable parts include `mode: 'no-cors'`, a complete `URL` constructor
and WHATWG URL parser, IDNA/opaque URL handling, service workers, an HTTP response
cache, streaming request bodies, `ReadableStream`/`Response.body`, Blob object URLs,
FileReader, multipart response parsing, `FormData(formElement)`, synchronous XHR,
XML/document responses and non-UTF-8 XHR decoding. Fetch/Request accept HTTP(S)
strings; native URL validation remains authoritative. Referrers are omitted;
custom referrers, integrity, keepalive and unsupported cache modes are refused.
HTTP status text is empty because the Java HTTP client does not expose it.
The implementation does not claim full Web IDL conversion/event conformance.

Four HTTP transfers may run per page, with a 15-second total deadline, five
redirects, a 256 KiB request limit, a 1 MiB decoded response/Blob limit and a
2 MiB encoded response limit. The network event queue has 512 entries / 8 MiB;
IPC delivery batches stay below 400 KB. The preflight cache has at most 64
entries and caps lifetime at five minutes. Query and form entry counts are
bounded. HTTP header control, unsafe port checks, normal platform TLS validation
and HTTPS downgrade refusal remain enforced in Java, beyond the mutable page DOM.

## Evidence and remaining service work

`CompatibilityTests` uses three real local HTTP server origins and the packaged
QuickJS child. It verifies approved/denied CORS, write refusal, preflight reuse,
credential/header isolation, redirect taint, exact multipart/form bodies,
cloning/consumption, early headers, partial XHR text across split UTF-8 characters,
response types, upload progress, abort/timeout/reopen, compression bombs, a valid
1 MiB response and query navigation. The existing engine, scripting, session,
media transport, reader and workspace tests remain required. This test fixture
is not an authenticated Netflix/Prime/cloud gaming test or full standards suite.

| Area | What is still needed before calling it supported |
| --- | --- |
| General websites | Modern HTML/DOM recovery and events, CSS box layout/Flex/Grid, complete URLs and forms, modules, CORS/CSP/isolation completion, Web Platform Tests and real website flows |
| Netflix / Prime Video | MSE buffering/demux/decoder integration, EME and an authorized CDM, secure persistence/output handling, service authentication and provider-supported playback on each platform |
| Cloud gaming | Interoperable WebRTC ICE/STUN/TURN, DTLS/SRTP, audio/video/data channels, graphics, pointer/controller input, network adaptation and real service latency/frame tests |
| Android browser | JavaScript bindings, graphics/media/lifecycle integration, physical Android 8+ device tests and a permanent private APK signer |
| Reliable Windows media | Resolve intermittent native video/HLS freezes and prove decoded frames, pause/resume and restart on supported Windows machines |
| Speed comparisons | Matched supported workloads and hardware, competing browser versions, startup/memory/interaction/media measurements; current renderer benchmarks cannot establish overall superiority |

[Widevine requires a license agreement](https://developers.google.com/widevine/drm/overview).
There is no licensed desktop CDM or partner onboarding material in this repository.
The owner must complete that process with the provider; a code change cannot
issue a license or service approval. Android's MediaDrm probe is a device
capability query, not successful protected playback. No DRM bypass or browser
identity impersonation has been added.

## Install and reproduce

Use the [latest Codex preview](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)
and [download/update instructions](../../DOWNLOADS.md). The publisher attaches
per-platform status files, source version and checksums. Windows/Linux retain
stable application/profile identities. Android updates between releases still
require the owner's permanent private signing key; test APKs from different runs
may not replace each other. Published release files have no 14-day artifact expiry.

```sh
python experiments/aster-engine/build.py desktop --test
```

Native Windows/Linux/Flatpak and Android emulator results are reported by the
release workflow. Read the status files for actual results; publishing a package
does not imply that every optional native media fixture passed. The existing
renderer benchmark is still scoped to Aster's own offline renderer workloads.
