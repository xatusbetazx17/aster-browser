# Aster's OpenJFX HLS correction

`com/sun/media/jfxmedia/locator/HLSConnectionHolder.java` comes from the pinned
OpenJFX 21.0.12 source, commit `ecd5757a6970c88bcf179779d73d8fb6c7ed9d29`:
https://github.com/openjdk/jfx21u/blob/ecd5757a6970c88bcf179779d73d8fb6c7ed9d29/modules/javafx.media/src/main/java/com/sun/media/jfxmedia/locator/HLSConnectionHolder.java

Aster changes only `adjustBitrate`:

- Keep the current variant for a zero/negative elapsed time or unknown/nonpositive
  content length. Fast loopback or cached reads can complete in a single clock
  tick; upstream divided by zero in that case.
- Saturate the bitrate at `Integer.MAX_VALUE` instead of allowing a narrowing
  integer conversion to wrap a fast connection into a negative estimate.

The existing license header is retained. This file is GPL-2.0-only with the
Classpath exception, as stated in its header; it is not covered by Aster's MIT
license. The package's `legal/javafx/` contains the upstream license and assembly
exception, and `legal/javafx/aster-patches/` contains this complete modified source.

`desktop_deps.py` verifies the original JAR hashes, compiles this class and its
nested classes against those JARs, and replaces only the corresponding class
entries in the packaged media JAR. No native library or browser engine is added.
Keep this correction under review when updating OpenJFX; remove it when an
upstream version fixes both cases.

`HlsDecoderTests` calls the shipped reader with deterministic timing/length edge
cases, then consumes two real HLS segments through Aster's protected relay.
The unmodified pinned JAR fails its zero-time regression with ArithmeticException.
Native Windows/Linux playback, restart and decoded-frame checks remain separate
required checks. This fix alone is not evidence of all HLS streams working.
