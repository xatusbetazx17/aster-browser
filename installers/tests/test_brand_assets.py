"""The logo is defined once; check every copy of it still matches.

Aster's mark lives in assets/brand/aster_brand.py and is written out to the
browser, the extension, the new tab page and both installers by
assets/brand/build_brand_assets.py. Nothing at runtime re-derives those copies,
so without this guard one of them silently keeps an older drawing - which is
exactly how the repository ended up shipping two different icons before.

Pillow is not installed on every runner, so the checks here stay on the vector
and archive side: the SVG text, and the .ico container's own header.
"""
from __future__ import annotations

from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "assets" / "brand"), str(ROOT / "installers")]

import aster_brand as brand  # noqa: E402 - resolved via the paths above
import kit  # noqa: E402

REBUILD = "Regenerate them with: python assets/brand/build_brand_assets.py"


class SvgCopies(unittest.TestCase):
    def test_repository_copies_match_the_definition(self):
        for relative, variant in {
            "assets/brand/aster-logo.svg": "logo",
            "assets/brand/aster-icon.svg": "icon",
            "assets/brand/aster-mark.svg": "mark",
            "experiments/firefox/extension/icon.svg": "icon",
            "experiments/webkit/aster_webkit/aster-icon.svg": "icon",
        }.items():
            with self.subTest(file=relative):
                self.assertEqual((ROOT / relative).read_text(encoding="utf-8"),
                                 brand.svg(variant), f"{relative} is out of date. {REBUILD}")

    def test_the_kit_carries_the_same_logo(self):
        """The installed browser reads these two out of the kit at runtime."""
        for name, variant in {
            "aster_browser/assets/icons/aster_logo.svg": "mark",
            "packaging/linux/aster.svg": "icon",
        }.items():
            with self.subTest(file=name):
                self.assertEqual(kit.read_text(name), brand.svg(variant),
                                 f"the kit's {name} is out of date. {REBUILD}")

    def test_the_new_tab_page_inlines_the_logo(self):
        """WebKit gets that page as a string, so a linked file would not load."""
        page = (ROOT / "experiments/webkit/aster_webkit/home.html").read_text(encoding="utf-8")
        inline = brand.svg("icon", class_name="mark", compact=True)
        self.assertIn(f"<!--aster-logo-->{inline}<!--/aster-logo-->", page,
                      f"the new tab page's inline logo is out of date. {REBUILD}")


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


if __name__ == "__main__":
    unittest.main()
