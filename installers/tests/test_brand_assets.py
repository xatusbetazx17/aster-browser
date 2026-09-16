"""The logo is one file; check every copy of it still matches.

The artwork is asterlogo.png in the repository root. assets/brand writes it out
as the vector and icon copies the browser, the extension, the new tab page and
both installers load, and nothing re-derives those at runtime - so without this
guard one of them silently keeps an older drawing, which is how the repository
ended up shipping three different marks before.

The copies are compared against the ones in assets/brand, which needs nothing
but the standard library, so this runs on a bare CI runner. Where Pillow is
installed the stricter check also runs: the vectors are traced from the bitmap
again and have to come out identical, which is what ties every copy back to the
artwork rather than to each other.
"""
from __future__ import annotations

from pathlib import Path
import re
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "assets" / "brand"), str(ROOT / "installers")]

import aster_brand as brand  # noqa: E402 - resolved via the paths above; needs no Pillow to import
import kit  # noqa: E402

REBUILD = "Regenerate them with: python assets/brand/build_brand_assets.py"

try:
    import PIL  # noqa: F401 - only the re-derivation below needs it
    HAS_PILLOW = True
except ImportError:  # pragma: no cover - depends on the runner
    HAS_PILLOW = False

#: Where each variant is published, and which one it is.
CANONICAL = {variant: f"assets/brand/aster-{variant}.svg" for variant in brand.VARIANTS}
COPIES = {
    "experiments/firefox/extension/icon.svg": "icon",
    "experiments/webkit/aster_webkit/aster-icon.svg": "icon",
}
KIT_COPIES = {
    "aster_browser/assets/icons/aster_logo.svg": "mark",
    # The desktop entry: the mark alone, on whatever the desktop puts behind it.
    "packaging/linux/aster.svg": "mark",
}


def canonical(variant: str) -> str:
    return (ROOT / CANONICAL[variant]).read_text(encoding="utf-8")


def bare(svg: str) -> str:
    """The same drawing however it was laid out or classed for its host."""
    return re.sub(r'\s+class="[^"]*"', "", re.sub(r">\s+<", "><", svg.strip()))


class Copies(unittest.TestCase):
    def test_the_repository_copies_match_the_brand_files(self):
        for relative, variant in COPIES.items():
            with self.subTest(file=relative):
                self.assertEqual((ROOT / relative).read_text(encoding="utf-8"), canonical(variant),
                                 f"{relative} is out of date. {REBUILD}")

    def test_the_kit_carries_the_same_logo(self):
        """The installed browser reads these two out of the kit at runtime."""
        for name, variant in KIT_COPIES.items():
            with self.subTest(file=name):
                self.assertEqual(kit.read_text(name), canonical(variant),
                                 f"the kit's {name} is out of date. {REBUILD}")

    def test_the_new_tab_page_inlines_the_logo(self):
        """WebKit gets that page as a string, so a linked file would not load."""
        page = (ROOT / "experiments/webkit/aster_webkit/home.html").read_text(encoding="utf-8")
        inline = re.search(r"<!--aster-logo-->(.*?)<!--/aster-logo-->", page, re.S)
        self.assertIsNotNone(inline, f"the new tab page has lost its logo markers. {REBUILD}")
        self.assertEqual(bare(inline.group(1)), bare(canonical("icon")),
                         f"the new tab page's inline logo is out of date. {REBUILD}")
        self.assertIn('class="mark"', inline.group(1), "the inline logo lost the class the page styles it with")


class WindowsIcon(unittest.TestCase):
    """The .ico is binary, so check what Windows itself reads out of it."""

    PATHS = ("installers/windows_installer/aster.ico", "assets/brand/aster.ico")

    def entries(self, data: bytes) -> list[tuple[int, int]]:
        reserved, kind, count = struct.unpack_from("<HHH", data, 0)
        self.assertEqual((reserved, kind), (0, 1), "not an icon file")
        sizes = []
        for index in range(count):
            width, height = struct.unpack_from("<BB", data, 6 + index * 16)
            length, offset = struct.unpack_from("<II", data, 6 + index * 16 + 8)
            self.assertLessEqual(offset + length, len(data), "an image runs past the end")
            self.assertEqual(data[offset:offset + 8], b"\x89PNG\r\n\x1a\n", "image is not a PNG")
            sizes.append((width or 256, height or 256))
        return sizes

    def test_every_size_windows_asks_for_is_present(self):
        for relative in self.PATHS:
            with self.subTest(file=relative):
                sizes = self.entries((ROOT / relative).read_bytes())
                self.assertEqual(sizes, [(size, size) for size in brand.ICO_SIZES],
                                 f"{relative} does not carry the expected sizes. {REBUILD}")

    def test_the_kit_and_the_installer_ship_the_same_icon(self):
        kit_icon = kit.read("aster_browser/assets/icons/aster.ico")
        self.assertEqual(self.entries(kit_icon), [(size, size) for size in brand.ICO_SIZES], REBUILD)
        self.assertEqual(kit_icon, (ROOT / "assets/brand/aster.ico").read_bytes(),
                         f"the kit's icon differs from the brand icon. {REBUILD}")


@unittest.skipUnless(HAS_PILLOW, "Pillow is needed to read the artwork")
class TracedFromTheArtwork(unittest.TestCase):
    """The stricter half: the vectors still come out of asterlogo.png."""

    def test_the_artwork_is_where_the_brand_files_expect_it(self):
        self.assertTrue(brand.SOURCE.is_file(), f"{brand.SOURCE.name} is missing from the repository root")

    def test_tracing_the_artwork_reproduces_the_brand_files(self):
        for variant in brand.VARIANTS:
            with self.subTest(variant=variant):
                self.assertEqual(canonical(variant), brand.svg(variant),
                                 f"assets/brand/aster-{variant}.svg no longer matches the artwork. {REBUILD}")


if __name__ == "__main__":
    unittest.main()
