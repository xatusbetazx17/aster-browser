# The Aster mark

`asterlogo.png` in the repository root **is** the logo: an A with an orbit
around it and a bolt through it, white on black. Everything the project shows -
the icon in the browser's title bar, the Linux desktop entry, the Windows setup
executable and its window, the extension, the new tab page - is derived from
that one file, so a new logo means dropping in a new bitmap rather than hunting
down a dozen copies.

[`aster_brand.py`](aster_brand.py) reads it. The rasters are scaled from the
bitmap, so they are exactly the artwork; the vectors are traced from it, because
the title bar, the desktop entry and the extension all want an SVG, and tracing
keeps that SVG the same drawing rather than one that merely resembles it. The
trace follows the bitmap to within half a pixel at 512 px.

## Files

| File | What it is | Where it is used |
| --- | --- | --- |
| `aster-logo.svg` / `aster-logo.png` | the artwork's own framing, margin and all | the README, anywhere the logo is shown as artwork |
| `aster-icon.svg` | the same mark enlarged to fill the square | launchers, the desktop entry, the extension, the new tab page, the GTK tab strip |
| `aster-mark.svg` / `aster-mark.png` | the glyph alone, no tile | dark surfaces that supply their own background: the title bar button, the browser's home page, the installer window's header |
| `aster.ico` | 16-256 px, one PNG per size | the Windows setup executable, its window, and the installed browser |

The icon framing exists because at 16 px the artwork's margin leaves too little
glyph to recognise. It is the same mark, scaled - not a second logo.

## Changing the logo

Replace `asterlogo.png` with the new artwork - white on black, square, a couple
of thousand pixels wide - then write out every copy:

```bash
python assets/brand/build_brand_assets.py
```

That rewrites the files above, the copies inside
`Aster-Browser-Windows-Kit-v16.zip` that the installed browser loads, the new
tab page's inline copy, and then rebuilds `install-linux.sh`, whose payload
embeds the kit. Pass `--skip-installer` to stop before that last, slow step
while iterating.

`installers/tests/test_brand_assets.py` fails if any copy no longer matches, so
a forgotten rebuild shows up as a test failure rather than as a stale icon on
someone's desktop. Where Pillow is installed it also re-traces the artwork and
checks the vectors still come out of it byte for byte.

Pillow is what reads the bitmap; the rest is the standard library.
