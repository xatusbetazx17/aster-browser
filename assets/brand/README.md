# The Aster mark

Aster's logo is an italic **A** with an orbit sweeping around it into a tail, and
a spark at the shoulder. It is drawn as geometry in
[`aster_brand.py`](aster_brand.py) rather than stored as a hand-edited file, so
the browser, the extension, the new tab page and both installers all show the
same drawing instead of slowly drifting apart.

## Files

| File | What it is | Where it is used |
| --- | --- | --- |
| `aster-logo.svg` / `aster-logo.png` | the mark on its black square, with the brand's own margin | the README, anywhere the logo is shown as artwork |
| `aster-icon.svg` | the same mark enlarged to fill the square | launchers, the desktop entry, the extension, the new tab page, the GTK tab strip |
| `aster-mark.svg` / `aster-mark.png` | the glyph alone, no tile | dark surfaces that supply their own background: the title bar button, the browser's home page, the installer window's header |
| `aster.ico` | 16-256 px, one PNG per size | the Windows setup executable, its window, and the installed browser |

The icon framing exists because at 16 px the logo's margin leaves too little
glyph to recognise. It is the same mark, scaled - not a second logo.

## Changing the logo

Edit the geometry in `aster_brand.py`, then write out every copy:

```bash
python assets/brand/build_brand_assets.py
```

That rewrites the files above, the copies inside
`Aster-Browser-Windows-Kit-v16.zip` that the installed browser loads, and then
rebuilds `install-linux.sh`, whose payload embeds the kit. Pass
`--skip-installer` to stop before that last, slow step while iterating.

`installers/tests/test_brand_assets.py` fails if any copy no longer matches the
definition, so a forgotten rebuild shows up as a test failure rather than as a
stale icon on someone's desktop.

Pillow is needed for the PNG and .ico output; the SVG side is pure Python.
