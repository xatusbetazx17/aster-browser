# Aster 0.6 — site protection and real page boxes

Aster's original engine now has shared ad/tracker request blocking, a normal-flow
CSS box tree, and a more cohesive native desktop/Android workspace. This is real
implementation work, **not completion of the modern web platform**. Netflix,
Prime Video and cloud gaming remain unsupported. No competing browser engine,
external-browser fallback or browser identity impersonation is included.

## Site protection

On Windows/Linux, choose **Protect** beside the address bar or **Settings → Site
protection**. On Android, use **Menu → Site protection** or the Home card.

- Blocking is on by default. Aster ships an independently curated offline starter
  set of 43 ad/tracker hostnames and also matches their subdomains. It uses indexed
  host lookups, not a regular expression over every URL. Case and a trailing DNS
  dot are normalized. Matching never treats URL paths, queries, or lookalike
  domains as the blocked hostname.
- Add up to 10,000 custom hostnames, one per line. Blank lines and `#` comments
  are accepted. Internationalized hostnames are canonicalized. Save replaces the
  custom set atomically; invalid input keeps the previous set. These are plain
  hostname rules, **not EasyList/ABP, hosts-file, wildcard or URL patterns**.
- Blocking happens before network contact and is rechecked on redirects. It
  covers the shared CSS/image loader, desktop classic scripts, fetch/XHR,
  preflights, WebSockets and the native progressive/HLS media relay. The normal
  TLS, origin, mixed-content and byte limits remain in force. Existing requests
  are not retroactively cancelled when a rule changes.
- Explicit top-level navigation and user-requested downloads are exempt. A page
  remains readable when an optional resource is blocked. A failed fetch/XHR or
  socket follows its existing error path; blocking does not fabricate a response.
- **Allow requests on this site** saves an exception for the exact scheme, host
  and port. It does not grant access to other origins or weaken cookie/CORS rules.
  Turn global blocking off, or reload after changing an exception, when diagnosing
  a broken site. Settings and rules survive application replacement in the same
  profile; desktop and Flatpak profiles remain separate.
- Activity shows actual blocked attempts for an origin in this browser session,
  across its tabs and reloads. Up to 64 origins and the latest 64 distinct blocked
  hosts per origin are retained in memory. It stores no request paths, query
  strings, cookies or bodies and writes no activity history to disk. Clearing
  website data also clears activity; protection preferences remain.

This small starter set is not Brave's filter coverage. There are no automatically
downloaded lists, cosmetic element filters, CNAME uncloaking, fingerprinting
defenses or guaranteed YouTube/video-ad removal. First-party/server-inserted ads
and unlisted hosts can still appear. Custom hostname choices can break services;
the visible exception control is part of the implementation, not a claim of
universal compatibility. No time-saved, bandwidth-saved or cross-browser speed
numbers are invented.

## Layout and presentation

The parser retains a bounded block tree alongside the text used by reading and
accessibility. Desktop Java2D and Android Canvas now consume the same geometry:

- Nested normal block flow, natural content heights and empty styled boxes.
- Content-box/border-box sizing; width, min/max-width and non-percentage min-height.
- Positive px, %, em and rem lengths; one-to-four-value margin/padding shorthands
  and side properties; horizontal auto margins for centered columns.
- Solid uniform borders, flat background colors and a single px corner radius.
- Left/center/right line alignment, inherited alignment, explicit line breaks,
  bounded long-word wrapping, and retained text/link hit testing and viewport culling.
- Type, class and ID compounds plus descendant and direct-child selectors. Mixed
  selector matching is bounded dynamic programming, not exponential backtracking.
  Specificity distinguishes IDs/classes/types; universal selectors have zero weight.

The CSS subset still has no margin collapsing, negative lengths, full inline box
layout, percentage heights, general `height`, floats, positioning, clipping,
background images, gradients, flexbox, grid, table layout, media queries, custom
properties, `!important`, pseudo-classes or sibling/attribute selectors. The HTML
tree is still Aster's restricted parser, not full HTML error recovery. Oversized
fixed widths can overflow; unsupported declarations do not create functionality.
The root retains Aster's 24px page inset. The changes are informed by the W3C
[CSS box model](https://www.w3.org/TR/css-box-3/) and
[selectors](https://www.w3.org/TR/selectors-4/), not a full conformance claim.

Home now uses consistent native cards for reading, bookmarks, site protection and
desktop testing tools. Desktop navigation icons are vectors rather than relying
on installed symbol fonts. Focusable controls, accessible names, saved reading
sizes, reduced motion, notes, downloads and tab parking remain available. The
interface continues to identify this as a preview and exposes service limits.

The Android protection dialog keeps focus on its panel until the rule editor is
selected. Opening it to review activity or allow a site does not automatically
open the keyboard and move the controls.

## Verification and efficiency

`ProtectionTests` counts requests reaching real local servers, including a
redirect and a denied CORS preflight, and exercises the packaged QuickJS process
and native controls. It verifies allowed downloads/navigation, host boundaries,
invalid-rule atomicity, saved exceptions, global disable and non-persistent
activity. `BoxLayoutTests` checks numerical geometry, alignment, selectors, empty
boxes, hidden subtrees, long words, link hits and actual Java2D pixel colors.

The Android API 26 fixture checks CSS background pixels, actual blocked/restored
image requests through the native protection dialog, and retention of rules and
exceptions across APK replacement. It also retains native navigation/forms,
cookie, images, reader and bookmark checks. Read the published platform status
files and Actions logs for the result on the exact release commit.

Existing CORS/body/XHR, session, media transport, workspace and installer checks
remain required. The renderer benchmark now verifies box geometry as well as
draws between cached and uncached measurements. Its six offline workloads still
**do not establish superiority over Chrome, Firefox or other browsers**. Useful
next measurements are matched real page flows, startup, memory, input latency,
video frames and power on specified hardware; blocking known requests alone
does not prove that Aster is the faster overall browser.

Dependency downloads retry one transient transport failure while retaining
mandatory pinned hashes. A checksum mismatch remains a hard failure. Package
status headings now read the actual version metadata.

A Windows check also exposed the previous UTF-8 decoder exceeding the script
command budget on a valid 1 MiB response. It now collects UTF-16 units and builds
strings in bounded chunks instead of making a string call for every byte.
Large responses, supplementary Unicode across chunk boundaries, partial UTF-8,
invalid sequences, BOM handling and fatal errors are exercised. The 500 ms script
budget, 1 MiB body limit and 32 MiB script heap remain unchanged. This is an
efficiency correction to Aster's decoder, not a competing-browser benchmark.

## Work still required

| Area | Remaining implementation or external dependency |
| --- | --- |
| General websites | Full HTML/DOM, CSS/Flex/Grid, graphics, fonts, accessibility tree, modules, URL/form/site semantics, CSP/CORS completion, OS isolation, Web Platform Tests and authenticated site flows |
| Netflix / Prime Video | MSE demux/buffering and decoder integration, EME with an authorized CDM, output/session security, provider approval and successful service playback on each platform |
| Cloud gaming | Interoperable ICE/STUN/TURN, DTLS/SRTP, real-time audio/video/data channels, graphics/input, congestion adaptation and actual service latency/frame tests |
| Android | JavaScript and media integration, physical device coverage, permanent private APK signer and migration for old differently signed installs |
| Windows media | Diagnose intermittent native video/HLS freezes; a single passing fixture does not prove reliable playback on users' computers |
| Ad/tracker coverage | Maintained filter distribution, richer rule semantics and site breakage testing; the current starter set is intentionally limited |

[Widevine requires a license agreement](https://developers.google.com/widevine/drm/overview).
There is no licensed desktop CDM or partner integration kit in this repository.
The owner must arrange that integration with the provider; source code cannot
issue a license or service approval. Android's DRM probe remains a capability
query, not a protected-video player. This release does not add fake MSE/EME/WebRTC
APIs, bypass DRM, or mark unsupported streaming services as working.

## Install and update

Use [Latest Codex preview](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview)
and the [download/update guide](../../DOWNLOADS.md). Windows x64 setup and Linux
x64 Flatpak replace their previous application files while keeping the same
profile. Linux support means compatible Flatpak desktops, not every Linux/CPU.
Android minimum remains 8 / API 26. Until a permanent signer is configured, the
test APK cannot reliably update installations signed in other workflow runs.
Published release assets have no 14-day Actions-artifact expiry.

```sh
python experiments/aster-engine/build.py desktop --test
```

The [0.5 HTTP APIs](RELEASE_0.5.md), [0.4 website sessions](RELEASE_0.4.md),
[0.3 workspace](RELEASE_0.3.md) and [0.2 reading tools](RELEASE_0.2.md) remain included.
