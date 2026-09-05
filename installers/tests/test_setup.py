"""Failure/recovery tests for managed updates. No real package managers run."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("aster_setup", Path(__file__).resolve().parents[1] / "setup.py")
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


class FixtureSource:
    def __init__(self):
        self.commit = "1" * 40
        self.payload = b"print('first version')\n"
        self.fail = False
        self.corrupt = False
        self.calls = 0

    def head(self):
        return self.commit

    def files(self, _commit, _edition):
        return [{"path": "experiments/firefox/example.py", "sha": setup.git_hash(self.payload), "size": len(self.payload)}]

    def file(self, _commit, _entry):
        self.calls += 1
        if self.fail:
            raise setup.SetupError("Simulated network failure")
        return b"modified payload" if self.corrupt else self.payload

    def advance(self):
        self.commit = "2" * 40
        self.payload = b"print('updated version')\n"


class ManagedUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "Aster test with spaces"
        self.source = FixtureSource()

    def install(self, rollback=False):
        return setup.install(self.root, "firefox", "native", self.source, rollback)

    def state(self):
        return json.loads((self.root / setup.MARKER).read_text())

    def test_first_install_repeat_update_and_rollback_preserve_user_files(self):
        self.assertTrue(self.install()["changed"])
        first = self.state()["commit"]
        profile = self.root / "user-notes.txt"
        profile.write_text("keep my data")
        self.assertFalse(self.install()["changed"])
        self.assertEqual(self.source.calls, 1)
        self.source.advance()
        self.assertTrue(self.install()["changed"])
        self.assertEqual(self.state()["previous"], first)
        self.assertTrue((self.root / "releases" / first).is_dir())
        self.install(rollback=True)
        self.assertEqual(self.state()["commit"], first)
        self.assertEqual(profile.read_text(), "keep my data")

    def test_interrupted_download_preserves_active_version_and_can_retry(self):
        self.install()
        before = self.state()
        self.source.advance()
        self.source.fail = True
        with self.assertRaises(setup.SetupError):
            self.install()
        self.assertEqual(self.state(), before)
        self.assertFalse((self.root / "releases" / self.source.commit).exists())
        self.assertFalse((self.root / ".setup-lock").exists())
        self.source.fail = False
        self.assertTrue(self.install()["changed"])

    def test_hash_failure_does_not_publish_mixed_or_corrupt_files(self):
        self.install()
        before = self.state()
        self.source.advance()
        self.source.corrupt = True
        with self.assertRaisesRegex(setup.SetupError, "Integrity"):
            self.install()
        self.assertEqual(self.state(), before)

    def test_local_edits_are_preserved_and_update_refused(self):
        self.install()
        edited = self.root / "releases" / self.source.commit / "experiments/firefox/example.py"
        edited.write_text("my changes")
        self.source.advance()
        with self.assertRaisesRegex(setup.SetupError, "changed"):
            self.install()
        self.assertEqual(edited.read_text(), "my changes")
        self.assertEqual(self.state()["commit"], "1" * 40)

    def test_unmanaged_directory_and_wrong_edition_are_not_overwritten(self):
        self.root.mkdir()
        file = self.root / "existing-browser.txt"
        file.write_text("original")
        with self.assertRaisesRegex(setup.SetupError, "not managed"):
            self.install()
        self.assertEqual(file.read_text(), "original")
        file.unlink()
        self.install()
        with self.assertRaises(setup.SetupError):
            setup.install(self.root, "webkit", "webkit", self.source)

    def test_failed_pointer_write_keeps_old_version_and_retry_succeeds(self):
        self.install()
        before = self.state()
        self.source.advance()
        original = setup.atomic_json

        def fail_pointer(path, data):
            if path == self.root / setup.MARKER:
                raise OSError("Disk full")
            return original(path, data)

        with patch.object(setup, "atomic_json", side_effect=fail_pointer):
            with self.assertRaises(OSError):
                self.install()
        self.assertEqual(self.state(), before)
        self.install()
        self.assertEqual(self.state()["commit"], "2" * 40)

    def test_concurrent_update_is_refused(self):
        self.install()
        with setup.lock(self.root):
            with self.assertRaisesRegex(setup.SetupError, "Another setup"):
                self.install()

    def test_check_mode_does_not_download_install_or_create_a_directory(self):
        with patch.object(setup, "runtime_setup") as runtime, patch.object(setup.Source, "head") as head:
            result = setup.main(["--edition", "firefox", "--install-dir", str(self.root), "--check"])
            self.assertEqual(result, 0)
            runtime.assert_not_called()
            head.assert_not_called()
        self.assertFalse(self.root.exists())

    def test_corrupt_state_is_preserved(self):
        self.install()
        marker = self.root / setup.MARKER
        marker.write_text("invalid json")
        with self.assertRaises(setup.SetupError):
            self.install()
        self.assertEqual(marker.read_text(), "invalid json")

    def test_paths_cannot_escape_package(self):
        for path in ["../profile", "/etc/file", "C:/file", "a/../../file", "a\\file", "./file", ""]:
            with self.subTest(path=path), self.assertRaises(setup.SetupError):
                setup.safe_path(path)

    def test_flatpak_choice_does_not_silently_switch_to_native_firefox(self):
        with patch.object(setup.sys, "platform", "linux"), patch.object(setup, "firefox_native", return_value="/usr/bin/firefox"), patch.object(setup.shutil, "which", return_value="/usr/bin/flatpak"), patch.object(setup.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertEqual(setup.runtime_setup("firefox", True, "flatpak"), "flatpak")

    def test_linked_release_directory_cannot_redirect_writes(self):
        self.install()
        self.source.advance()
        release = self.root / "releases" / self.source.commit
        try:
            release.symlink_to(Path(self.temp.name), target_is_directory=True)
        except OSError:
            self.skipTest("This Windows account cannot create symlinks")
        with self.assertRaises(setup.SetupError):
            self.install()


if __name__ == "__main__":
    unittest.main()
