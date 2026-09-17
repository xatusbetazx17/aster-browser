"""Check the two ways a change reaches the kit both installers are built from.

The Qt application's source lives inside Aster-Browser-Windows-Kit-v16.zip, so
every fix to it is a rewrite of that archive. replace() guards against a typo by
refusing an unknown name; add() is what ships a new module, and it has to leave
every other entry byte-identical - an archive rewritten badly is not something a
diff shows.

The tests build their own small archive and point kit at it, so the shipped kit
is never touched here.
"""
from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "installers")]

import kit  # noqa: E402 - resolved via the path above


class KitWriteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.archive = self.tmp / "kit.zip"
        with zipfile.ZipFile(self.archive, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(kit.PREFIX + "run_aster.py", "print('aster')\n")
            archive.writestr(kit.PREFIX + "aster_browser/config.py", "THEME = 1\n")
        self.original = kit.KIT
        kit.KIT = self.archive
        self.addCleanup(setattr, kit, "KIT", self.original)

    def held(self) -> dict[str, bytes]:
        with zipfile.ZipFile(self.archive) as archive:
            return {name: archive.read(name) for name in archive.namelist()}

    def test_replace_refuses_a_name_the_kit_does_not_carry(self):
        with self.assertRaises(KeyError):
            kit.replace({"aster_browser/scrolling.py": "SMOOTH = 1\n"})
        self.assertNotIn(kit.PREFIX + "aster_browser/scrolling.py", self.held())

    def test_add_ships_a_new_module_and_leaves_the_rest_alone(self):
        before = self.held()
        self.assertTrue(kit.add({"aster_browser/scrolling.py": "SMOOTH = 1\n"}))
        after = self.held()
        self.assertEqual(after[kit.PREFIX + "aster_browser/scrolling.py"], b"SMOOTH = 1\n")
        for name, data in before.items():
            self.assertEqual(after[name], data, name)

    def test_add_also_rewrites_a_name_already_there(self):
        self.assertTrue(kit.add({"aster_browser/config.py": "THEME = 2\n"}))
        self.assertEqual(kit.read_text("aster_browser/config.py"), "THEME = 2\n")

    def test_writing_the_same_bytes_twice_changes_nothing(self):
        self.assertTrue(kit.add({"aster_browser/flags.py": "SMOOTH = 1\n"}))
        stamp = self.archive.read_bytes()
        self.assertFalse(kit.add({"aster_browser/flags.py": "SMOOTH = 1\n"}))
        self.assertEqual(self.archive.read_bytes(), stamp)

    def test_a_new_entry_is_readable_the_usual_way(self):
        kit.add({"aster_browser/flags.py": "SMOOTH = 1\n"})
        self.assertIn("aster_browser/flags.py", kit.names())
        self.assertEqual(kit.read("aster_browser/flags.py"), b"SMOOTH = 1\n")


if __name__ == "__main__":
    unittest.main()
