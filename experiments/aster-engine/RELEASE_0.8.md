# Aster 0.8 — Flexbox and an HLS decoder correction

Aster's own shared engine can now arrange supported pages in flex rows, columns
and wrapping cards. Desktop JavaScript can change those layouts without losing
unrelated inline styles. This release also corrects a reproducible arithmetic
error in the bundled HLS reader and expands the native restart checks.

**This remains an experimental browser. Full website compatibility, Netflix,
Prime Video and cloud gaming are not completed by this release.** No authenticated
commercial streaming or gaming service has been verified. The remaining work is
listed below, separately from the executable fixtures delivered here.

## Shared Flexbox implementation

`display:flex` creates a block flex container. Direct element children become flex
items, including links, buttons and spans; contiguous text gets an anonymous item,
and whitespace-only anonymous items are omitted. Hidden items remain absent.
Supported declarations include:

- `flex-direction`: row, column and their reversed directions.
- `flex-wrap` and `flex-flow`: single or multiple lines, including reverse wrapping.
- `flex`, `flex-basis`, `flex-grow`, `flex-shrink`: content/auto or definite bases,
  proportional growth and scaled shrink, partial factors below one, and min/max
  freezing with redistribution. Indefinite percentage bases use content sizing.
- `gap`, `row-gap`, `column-gap`: supported lengths, with indefinite percentage
  gaps treated as zero. Width/height constraints, padding and borders participate
  in sizing. Auto margins consume positive free space before alignment.
- `justify-content`, `align-items`, `align-self`, `align-content`: start/end,
  center, stretch where applicable, and space-between/around/evenly packing.
- `order`: stable visual ordering. Reversal and order preserve DOM reading order.

Normal flow also gains explicit height and maximum height, with percentages
resolved against definite containing heights. Long content retains its natural
height; a CSS dimension limit does not silently truncate the flow of long pages.

Layout uses reusable measured fragments. It bounds measurements, total work,
boxes and painted fragments. Nested flex sizing cannot recurse indefinitely or
run an unbounded freezing loop. Paint fragments are sorted for viewport culling;
phrase search follows a separate DOM-order list, so adjacent columns do not
interleave the words of a paragraph. Links and script actions use final geometry.
Android uses the same parser, layout and hit testing with its native Canvas.

This is a supported subset of [CSS Flexible Box Layout](https://www.w3.org/TR/css-flexbox-1/).
It is not full CSS conformance. Inline-flex formatting, baseline alignment,
writing modes/RTL, intrinsic sizing of every CSS/replaced-element case, scroll
containers, negative margins, positioned children, stacking/overlap semantics,
Grid, tables, floats, custom-property substitution and complete inline formatting
remain unfinished. Existing engine rules for long words and images still apply.
Unsupported declarations are ignored; media queries work within the 0.7 subset.

## Live inline styles in desktop scripts

The style object now reads and writes the element's actual style attribute.
Changing `element.style.flexDirection` preserves display, spacing, colors and
other existing declarations. `setAttribute('style', ...)` and removing that
attribute are reflected in subsequent property reads. The bridge supports camel
case properties, `cssText`, `getPropertyValue`, `getPropertyPriority`,
`setProperty`, `removeProperty`, `length` and `item`, with existing attribute and
script limits. A property value cannot inject another declaration through a
semicolon. Author priorities survive serialization.

This is a bounded declaration bridge, not complete CSSOM or computed style.
Unsupported CSS still does not become rendered merely because it can be stored
in a declaration. Android JavaScript remains absent.

The built-in Playground now uses responsive flex cards, so the feature can be
tried in the application alongside the existing script and media controls.

## Reproducible HLS arithmetic fix

The pinned OpenJFX 21.0.12 HLS reader divided segment size by wall-clock elapsed
milliseconds without handling zero. Fast loopback/cache reads can finish within
one clock tick. A regression test against the unmodified pinned JAR reproduces
`ArithmeticException: / by zero`.

Aster's correction retains the current variant for invalid timing or unknown
length and saturates very high bitrate estimates instead of overflowing a signed
integer. The identical regression test passes with the patched module, then reads
two real MPEG-TS segments through Aster's private media relay. The correction
addresses a concrete decoder bug; it is not proof that every previous stall had
this cause.

Only the affected Java class and its nested classes are rebuilt against the
hash-pinned distribution. Complete modified source, attribution, upstream
license and patch explanation ship with the desktop packages. See
[the patch provenance](desktop/native/javafx-patches/README.md). No native decoder,
DRM module or browser engine is added. Updating OpenJFX requires reviewing or
retiring this pinned correction.

HLS seeking remains disabled. Restart reopens the decoder, and the native fixture
now requires **three successive restarts**, each fetching fresh segments and
showing actual blue and red frames with advancing time. The Windows runner tests
a video-only HLS fixture; Linux additionally has a silent AAC track and virtual
audio output. Physical sound, live HLS and general adaptive playback still need
separate validation. Read the build-status files for the exact downloaded build.

Playback waits until its Swing video surface is visible and has painted the new
scene. Frame checks use asynchronous JavaFX pulse snapshots rather than blocking
the rendering thread. A decoder's end event can precede presentation of its last
frame; three post-end render samples still require both decoded colors and
advancing playback time before passing. Windows stores native output and decoder
logs alongside the screenshots for both file and HLS checks.

Media event queues now belong to the script session and use a separate token for
each native player. Retiring a source atomically drops its queued native events;
callbacks arriving later cannot refill the active timeline. Closing a session
closes its mailbox. Control replies survive source replacement, and time updates
remain coalesced and bounded. `MediaEventsTests` exercises deliberately delayed
callbacks and separate real QuickJS sessions. The native fixture also requires
each new page's media timeline to start at zero before its Play click.

Initial volume, mute and start time are validated before replacing a player and
installed before its loader starts. A new player does not receive a redundant
asynchronous seek to zero after it has begun playing. The native page fixture
checks its initial volume/mute at metadata delivery, then changes both during
playback. Nonzero initial HLS seeking is explicitly rejected.

## Verification

`python experiments/aster-engine/build.py desktop --test` runs the full existing
suite plus `FlexLayoutTests`, `HlsDecoderTests` and `MediaEventsTests`:

- Numeric geometry for growth/shrink, constraints, wrapping/reversal/order,
  alignment, auto margins, nested percentages, long pages and work bounds.
- Actual QuickJS style mutation and reflow, property priorities and attribute
  synchronization; renderer link/action hits and DOM-order phrase search.
- Actual Java2D backgrounds and text at wide/narrow viewport sizes and zoom.
- The shipped HLS reader's deterministic arithmetic cases and real segment reads.

CI builds Windows and Ubuntu 22.04/24.04 packages, runs native display/video/HLS
checks and Windows installation/update checks, builds the Flatpak, and runs the
Android APK in an emulator. The Android fixture checks two equal-width colored
flex items on the same row with a gap using actual screenshot pixels, alongside
CDN/CORS/integrity, navigation, forms, sessions and APK update tests. These are
controlled fixtures, not a Web Platform Tests conformance result or physical-device
certification. Check Actions for the final result on the exact commit.

## Work still required for the requested services

| Target | Remaining implementation and acceptance evidence |
| --- | --- |
| Broad website compatibility | HTML error recovery, complete DOM/CSS/graphics/fonts, modules/workers, forms and URL/site semantics, full security policies, OS process isolation, accessibility and substantial Web Platform Tests coverage; real authenticated workflows |
| Netflix and Prime Video | MSE buffering/demux and decoder integration, EME and an authorized CDM, provider/output/security requirements; actual account login and protected playback on each target platform |
| Cloud gaming | Real WebRTC ICE/STUN/TURN, DTLS/SRTP, low-latency audio/video and data channels, congestion handling, graphics/input; actual service frames, sound, controller input and measured latency |
| Android | JavaScript/media integration, release signing continuity and physical-device coverage |
| Ongoing releases | Security updates, compatibility maintenance, installation/update testing and hardware coverage including Steam Deck |

The repository has no authorized desktop CDM integration. A
[Widevine license agreement](https://developers.google.com/widevine/drm/overview)
and the applicable provider relationship must come from the project owner;
code changes cannot grant that authorization. Account-specific tests require
legitimate test access on the target devices. No keys or account secrets belong
in the repository. Aster's existing Android Widevine query reports device
capability only.

Install the verified artifacts for the desired build through the
[download guide](../../DOWNLOADS.md). None of these changes substitute an
external browser or make the incomplete service APIs pretend to work.
