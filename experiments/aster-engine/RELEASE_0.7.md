# Aster 0.7 — responsive pages and CDN resources

This update removes two common blockers in Aster's own engine: rejecting images
and stylesheets hosted on another origin, and ignoring responsive CSS. It also
corrects stylesheet order, author `!important` priorities and stylesheet retention
when desktop JavaScript starts, changes the DOM or stops.

**Full website compatibility, Netflix, Prime Video and cloud gaming remain
unfinished.** The changes below are implemented and covered by executable
fixtures. They do not supply a full browser platform, a DRM license or evidence
of successful commercial-service playback.

## Responsive CSS

The shared Java engine evaluates nested `@media` rules and `media` attributes on
`style` and stylesheet `link` elements. Supported queries include `screen`/`all`,
media-type negation, `only`, comma-separated alternatives, `and`, viewport width,
height and orientation, `min-`/`max-` lengths, and single comparisons such as
`(width <= 600px)`. Lengths support px, em/rem relative to the initial 16px size,
and unitless zero. Unknown features or unsupported syntax do not become a match
through negation. Print-only rules do not style the screen.

Desktop resizing, viewport-height changes and page zoom rebuild the style and
layout trees from retained markup and fetched stylesheets. Android uses the
visible scroll viewport and recalculates after its size changes. A long document
does not become portrait solely because its scrollable content is tall. Content
hidden by one breakpoint can reappear at another; link targets follow the new
geometry. Image discovery includes hidden markup within the existing limit.

External and inline stylesheet rules now retain their document order. Author
important declarations outrank normal inline declarations; inline important
declarations retain precedence over author stylesheet rules. This operates within
the existing type/class/ID, descendant and direct-child selector subset.

The parser skips unsupported at-rule blocks as a whole and understands quoted
braces, escapes and comments when finding rule boundaries. It bounds stylesheet
size, nesting (16), rule count (512), query length/features and per-element
declaration output (64 KiB). Oversized rules are skipped; an oversized cascade
uses the existing page-too-complex failure path.

This is a subset of [CSS Media Queries](https://www.w3.org/TR/mediaqueries-4/),
not conformance to the complete CSS specifications. Flexbox, grid, positioning,
floats, table layout, custom properties, full inline formatting, font loading,
general range expressions, preference queries and `matchMedia()` remain absent.

## CDN stylesheets and images

The shared loader accepts HTTP/HTTPS stylesheet and PNG/JPEG/GIF image URLs on
other origins. It preserves TLS validation, mixed-content and unsafe-port
restrictions, redirect checks, MIME validation, cancellation, download limits,
decode limits and site-protection checks before every contacted target.

- Same-origin resources can use applicable profile cookies. Cross-origin assets
  send no profile cookies and accept no third-party cookies. Crossing an origin
  during redirects prevents cookies from resuming on a return to the first origin.
- Anonymous `crossorigin` requests send `Origin` and require an appropriate
  `Access-Control-Allow-Origin` response. Cross-origin `use-credentials` remains
  unsupported and is refused before contact. Redirects retain CORS taint.
- Non-CORS responses declaring `Cross-Origin-Resource-Policy: same-origin` are
  refused across origins. `same-site` is conservatively treated the same way until
  Aster has a public-suffix implementation. These are restricted semantics, not
  complete Fetch/CORS/CORP support.
- Stylesheet `integrity` verifies SHA-256, SHA-384 or SHA-512 over decoded resource
  bytes, selecting the strongest listed supported algorithm. A bad hash never
  falls back to a weaker matching hash. Cross-origin integrity requires CORS;
  malformed or wholly unsupported integrity metadata is refused conservatively.
- The resource key includes the element's CORS, credentials and integrity policy.
  A response fetched for one policy cannot satisfy a different policy after a
  script update. Desktop snapshots retain link and style metadata in source order.
- Desktop assets use the Java HTTP client without a cookie handler, authenticator
  or automatic redirects. A bounded body subscriber and timeout cover the whole
  transfer. This sends `Origin` without globally enabling restricted headers in
  the older JDK URLConnection. Android uses its native URLConnection.

Existing budgets remain four stylesheet fetches of at most 64 KiB each, eight
image fetches of at most 2 MiB each, at most two million decoded pixels per image
and an 8 MiB decoded-image budget per page. CSP-bearing pages retain the existing
conservative refusal of external resources and scripts. Dynamic resource loading,
CSS imports/background images, SVG/WebP/AVIF, `srcset`, responsive image selection,
cross-origin script loading and third-party cookie flows are not added here.

The relevant standards are [Fetch](https://fetch.spec.whatwg.org/) and
[Subresource Integrity](https://www.w3.org/TR/sri/).

## Verification

Run `python experiments/aster-engine/build.py desktop --test`. In addition to the
existing engine, network, security, session, media transport, reading and workspace
suites, it runs:

- `AssetTests`: two actual HTTP origins, allowed and refused CORS, strongest-hash
  integrity, multiple cookies, redirect isolation, blocked network contact,
  gzip/MIME/size bounds, CSP refusal, stylesheet order, and actual QuickJS startup
  and DOM mutation with retained stylesheet policies.
- `ResponsiveTests`: breakpoint boundaries, units, orientation, height changes,
  nested/unsupported rules, author/inline priorities, work bounds, live resizing
  and zoom, visibility restoration, painted pixel colors and link hit targets.
- `ReadingTests`: an image fetched from a second HTTP origin is decoded and painted
  by the real desktop page canvas.

The Android emulator gate now requires a CORS CDN image and integrity-checked CDN
stylesheet to paint the expected pixels. The stylesheet's media rule must match
the actual mobile viewport and its important declaration must override the inline
background. The fixture verifies the Origin header, absence of leaked cookies,
blocked/restored CDN requests, and retained sessions/settings across APK replacement.

Check Actions and published status reports for the result on the exact commit.
Desktop headless fixtures do not establish native display/audio, Android device,
Steam Deck, commercial-service or complete web-platform compatibility.

## Remaining service blockers

| Target | Work and acceptance evidence still needed |
| --- | --- |
| General websites | HTML error recovery, complete DOM/CSS and graphics, fonts, modules and workers, URL/form/site semantics, complete security policies, OS isolation, accessibility and Web Platform Tests; then real authenticated site flows |
| Netflix / Prime Video | MSE buffering/demux and decoder integration, EME with an authorized CDM, output/session security and provider integration; successful login and protected playback on each target platform |
| Cloud gaming | ICE/STUN/TURN, DTLS/SRTP, real-time audio/video and data channels, graphics/input, congestion handling; actual service frames, sound, controller input and latency tests |
| Android | Script/media integration, persistent release signing and physical-device coverage |

[Widevine requires a license agreement](https://developers.google.com/widevine/drm/overview).
The repository has no authorized desktop CDM integration. The owner must arrange
that provider relationship; engine work is also required before it can be used.
Android's existing Widevine report remains a device capability query. No streaming
or gaming service is marked working on the strength of these local fixtures.

Install and update through the [download guide](../../DOWNLOADS.md). The existing
Windows setup and Linux Flatpak profile-preserving update paths remain in use.
