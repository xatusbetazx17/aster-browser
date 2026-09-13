"""Check the installer records what it wrote and refuses to delete edited files.

Two failures put work at risk before this guard existed.

The exe embeds app_bundle.zip at build time, so a kit updated afterwards never
reached anyone who ran that exe, and nothing in the installed copy said which kit
it came from. bundle_app.py now writes bundle_info.json - the kit's digest, the
build time, and a hash per file - so a stale build is visible at install time.

Worse, the installer deleted the application directory outright before extracting.
Anyone who had patched a module lost it, silently, and the exe's older code took
its place. installer_gui.py now compares the directory against that manifest and
moves an edited installation aside instead of removing it.

installer_gui imports winreg, PIL and customtkinter, none of which exist on the
Linux runner, so the platform modules are stubbed to reach the pure-Python file
checks underneath.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import types
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
INSTALLER_DIR = ROOT / "installers" / "windows_installer"


def _load_installer_gui():
    for name in ("winreg", "customtkinter"):
        if name not in sys.modules:
            sys.modules[name] = types.ModuleType(name)
    if not hasattr(sys.modules["customtkinter"], "CTk"):
        sys.modules["customtkinter"].CTk = type("CTk", (), {})
    if "PIL" not in sys.modules:
        pil = types.ModuleType("PIL")
        pil.Image = types.ModuleType("PIL.Image")
        sys.modules["PIL"] = pil
        sys.modules["PIL.Image"] = pil.Image
    if str(INSTALLER_DIR) not in sys.path:
        sys.path.insert(0, str(INSTALLER_DIR))
    import installer_gui  # noqa: PLC0415 - deliberately imported after the stubs

    return installer_gui


def _make_kit(path: Path) -> None:
    """A miniature stand-in for the real kit, same layout, a fraction of the size."""
    with zipfile.ZipFile(path, "w") as kit:
        kit.writestr("aster-browser-windows-kit/run_aster.py", "print('aster')\n")
        kit.writestr("aster-browser-windows-kit/aster_browser/__init__.py", "")
        kit.writestr("aster-browser-windows-kit/aster_browser/browser.py", "URL_BAR = 1\n")
        kit.writestr("aster-browser-windows-kit/aster_browser/paths.py", "HOME = 2\n")
        kit.writestr("aster-browser-windows-kit/tools/legacy-update-scripts/old.py", "raise SystemExit\n")


class InstallerTestBase(unittest.TestCase):
    """Builds a bundle from a miniature kit and extracts it as an installation."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.kit = self.tmp / "kit.zip"
        self.bundle = self.tmp / "app_bundle.zip"
        _make_kit(self.kit)

        sys.path.insert(0, str(INSTALLER_DIR))
        self.addCleanup(lambda: sys.path.remove(str(INSTALLER_DIR)) if str(INSTALLER_DIR) in sys.path else None)
        import bundle_app

        bundle_app.create_app_bundle(str(self.kit), str(self.bundle), str(self.tmp))
        self.gui = _load_installer_gui()

        self.app_dir = self.tmp / "app"
        with zipfile.ZipFile(self.bundle) as z:
            z.extractall(self.app_dir)

    def manifest(self):
        return self.gui.read_manifest(str(self.app_dir))

    def edits(self):
        return self.gui.find_local_edits(str(self.app_dir), self.manifest())


class BundleManifestTests(InstallerTestBase):
    def test_manifest_identifies_the_kit_the_bundle_was_built_from(self):
        manifest = self.manifest()
        self.assertIsNotNone(manifest)
        self.assertEqual(manifest["kit_name"], "kit.zip")
        self.assertEqual(manifest["kit_sha256"], hashlib.sha256(self.kit.read_bytes()).hexdigest())
        self.assertEqual(manifest["file_count"], len(manifest["files"]))

    def test_every_recorded_hash_matches_the_file_shipped_beside_it(self):
        manifest = self.manifest()
        with zipfile.ZipFile(self.bundle) as z:
            shipped = set(z.namelist())
            for rel, digest in manifest["files"].items():
                self.assertIn(rel, shipped)
                self.assertEqual(hashlib.sha256(z.read(rel)).hexdigest(), digest, rel)

    def test_legacy_update_scripts_stay_out_of_the_bundle(self):
        with zipfile.ZipFile(self.bundle) as z:
            self.assertNotIn("tools/legacy-update-scripts/old.py", z.namelist())

    def test_a_freshly_extracted_install_reports_no_edits(self):
        self.assertEqual(self.edits(), ([], [], []))

    def test_files_python_and_the_app_create_at_runtime_are_not_edits(self):
        cache = self.app_dir / "aster_browser" / "__pycache__"
        cache.mkdir(parents=True)
        (cache / "browser.cpython-311.pyc").write_bytes(b"\x00")
        (self.app_dir / "aster_browser" / "aster.log").write_text("run", encoding="utf-8")
        self.assertEqual(self.edits(), ([], [], []))

    def test_a_patched_module_is_reported(self):
        target = self.app_dir / "aster_browser" / "browser.py"
        target.write_text(target.read_text(encoding="utf-8") + "URL_BAR = 99\n", encoding="utf-8")
        modified, removed, added = self.edits()
        self.assertEqual(modified, ["aster_browser/browser.py"])
        self.assertEqual((removed, added), ([], []))

    def test_an_added_file_is_reported(self):
        (self.app_dir / "aster_browser" / "my_plugin.py").write_text("x = 1\n", encoding="utf-8")
        modified, removed, added = self.edits()
        self.assertEqual(added, ["aster_browser/my_plugin.py"])
        self.assertEqual((modified, removed), ([], []))

    def test_a_deleted_file_is_reported(self):
        (self.app_dir / "aster_browser" / "paths.py").unlink()
        modified, removed, added = self.edits()
        self.assertEqual(removed, ["aster_browser/paths.py"])
        self.assertEqual((modified, added), ([], []))

    def test_an_install_without_a_manifest_cannot_be_verified(self):
        (self.app_dir / "bundle_info.json").unlink()
        self.assertIsNone(self.manifest())

    def test_a_corrupt_manifest_cannot_be_verified(self):
        (self.app_dir / "bundle_info.json").write_text("{not json", encoding="utf-8")
        self.assertIsNone(self.manifest())
        (self.app_dir / "bundle_info.json").write_text(json.dumps({"files": "wrong type"}), encoding="utf-8")
        self.assertIsNone(self.manifest())


class PreserveExistingAppTests(InstallerTestBase):
    """The install step itself, driven through the method that does the deleting."""

    def installer(self):
        app = object.__new__(self.gui.AsterInstallerApp)
        app.logged = []
        app._log = app.logged.append
        return app

    def backups(self):
        return sorted(p for p in self.tmp.iterdir() if p.name.startswith("app-backup-"))

    def test_an_unmodified_install_is_replaced_without_a_backup(self):
        app = self.installer()
        app._preserve_existing_app(str(self.tmp), str(self.app_dir))
        self.assertFalse(self.app_dir.exists())
        self.assertEqual(self.backups(), [])

    def test_a_patched_install_is_moved_aside_with_every_file_intact(self):
        target = self.app_dir / "aster_browser" / "browser.py"
        target.write_text("URL_BAR = 99  # mine\n", encoding="utf-8")
        (self.app_dir / "aster_browser" / "my_plugin.py").write_text("x = 1\n", encoding="utf-8")

        app = self.installer()
        app._preserve_existing_app(str(self.tmp), str(self.app_dir))

        self.assertFalse(self.app_dir.exists())
        backups = self.backups()
        self.assertEqual(len(backups), 1)
        kept = backups[0]
        self.assertEqual((kept / "aster_browser" / "browser.py").read_text(encoding="utf-8"), "URL_BAR = 99  # mine\n")
        self.assertEqual((kept / "aster_browser" / "my_plugin.py").read_text(encoding="utf-8"), "x = 1\n")
        self.assertTrue(any("DİKKAT" in line for line in app.logged))
        self.assertTrue(any("aster_browser/browser.py" in line for line in app.logged))

    def test_an_install_with_no_manifest_is_kept_rather_than_assumed_disposable(self):
        (self.app_dir / "bundle_info.json").unlink()
        app = self.installer()
        app._preserve_existing_app(str(self.tmp), str(self.app_dir))
        self.assertEqual(len(self.backups()), 1)
        self.assertTrue(any("doğrulanamadı" in line for line in app.logged))

    def test_a_failed_backup_stops_the_install_instead_of_deleting(self):
        (self.app_dir / "aster_browser" / "browser.py").write_text("edited\n", encoding="utf-8")
        app = self.installer()
        original_move = shutil.move

        def refuse(*_args, **_kwargs):
            raise OSError("disk full")

        shutil.move = refuse
        try:
            with self.assertRaises(RuntimeError):
                app._preserve_existing_app(str(self.tmp), str(self.app_dir))
        finally:
            shutil.move = original_move
        # The edited work is still where it was.
        self.assertTrue(self.app_dir.exists())
        self.assertEqual((self.app_dir / "aster_browser" / "browser.py").read_text(encoding="utf-8"), "edited\n")


if __name__ == "__main__":
    unittest.main()
