"""Release gates must fail before any upload for incompatible or incomplete builds."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import subprocess

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("publisher", ROOT / "packaging/publish_release.py")
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
from signing import parse_config


class ReleaseTests(unittest.TestCase):
    def fixture(self, root, persistent=True):
        metadata = {"version": "0.2.1-preview.24", "version_code": 24, "native_version": "0.2.1.24", "base_version": "0.2.1", "commit": "a" * 40}
        identity = {"persistent": persistent, "certificate_sha256": "a" * 64, "package": "io.aster.browser.enginepreview", "version_code": 24, "version": metadata["version"]}
        for platform in ("Windows", "Linux", "Linux-Flatpak", "Android"):
            folder = root / ("aster-engine-" + platform)
            folder.mkdir(parents=True)
            (folder / "VERSION.json").write_text(json.dumps(metadata))
        for platform in ("Windows", "Linux"):
            folder = root / ("aster-engine-" + platform)
            (folder / "BENCHMARK.json").write_text(json.dumps({"schema": 1, "commit": metadata["commit"], "results": [{"identical_geometry": True}] * 6}))
            (folder / "BUILD-STATUS.txt").write_text("Core, browsing and reading checks: passed\nNative window: success\nVideo decoding: success\nHLS and page controls: " + ("failure" if platform == "Windows" else "success"))
        files = {"Windows": ["aster-windows-x64-setup.exe", "aster-engine-windows-x64.zip", "WINDOWS-SETUP-STATUS.txt"],
            "Linux": ["aster-engine-linux-x64.tar.gz"], "Linux-Flatpak": ["aster-linux-x64.flatpak", "FLATPAK-UPDATE-STATUS.txt"],
            "Android": ["android/aster-engine-preview.apk", "ANDROID-TEST-STATUS.txt"]}
        for platform, names in files.items():
            for name in names:
                path = root / ("aster-engine-" + platform) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"verified fixture bytes")
        (root / "aster-engine-Android/android/SIGNING.json").write_text(json.dumps(identity))
        return metadata, identity

    def test_complete_build_and_disclosed_windows_media_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, identity = self.fixture(root / "input")
            result = publisher.assemble(root / "input", root / "output", metadata["commit"])
            self.assertEqual(result[2], "aster-android-8-plus.apk")
            self.assertIn("aster-windows-x64-setup.exe", (root / "output/SHA256SUMS.txt").read_text())
            self.assertIn("install-linux.sh", (root / "output/SHA256SUMS.txt").read_text())
            self.assertEqual((root / "output/install-linux.sh").read_bytes(), (ROOT.parents[1] / "install-linux.sh").read_bytes())
            self.assertIn("failure", (root / "output/WINDOWS-BUILD-STATUS.txt").read_text())

    def test_wrong_benchmark_revision_blocks_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, _ = self.fixture(root / "input")
            report = root / "input/aster-engine-Linux/BENCHMARK.json"
            data = json.loads(report.read_text())
            data["commit"] = "b" * 40
            report.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "same-source renderer"):
                publisher.assemble(root / "input", root / "output", metadata["commit"])

    def test_missing_upgrade_evidence_blocks_all_packages(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, _ = self.fixture(root / "input")
            (root / "input/aster-engine-Windows/WINDOWS-SETUP-STATUS.txt").unlink()
            with self.assertRaisesRegex(ValueError, "installation/update"):
                publisher.assemble(root / "input", root / "output", metadata["commit"])
            self.assertFalse((root / "output").exists())

    def test_mixed_commits_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, _ = self.fixture(root / "input")
            with self.assertRaisesRegex(ValueError, "source revision"):
                publisher.assemble(root / "input", root / "output", "b" * 40)
            self.assertFalse((root / "output").exists())

    def test_disposable_key_cannot_be_called_an_updatable_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, _ = self.fixture(root / "input", persistent=False)
            _, signing, filename = publisher.assemble(root / "input", root / "output", metadata["commit"])
            self.assertEqual(filename, "aster-android-8-plus-test.apk")
            self.assertIn("updates are not enabled", publisher.notes(metadata, signing, filename, "test"))

    def test_identity_change_or_version_rollback_are_refused(self):
        base = {"package": "io.aster.browser.enginepreview", "persistent": True, "version_code": 24, "certificate_sha256": "a" * 64}
        publisher.validate_identity(dict(base, version_code=25), base)
        for changed in (dict(base), dict(base, version_code=23), dict(base, version_code=25, certificate_sha256="b" * 64), dict(base, version_code=25, persistent=False)):
            with self.assertRaises(ValueError):
                publisher.validate_identity(changed, base)

    def test_malformed_signing_secret_is_rejected_without_key_output(self):
        for raw in ("null", "{}", '{"password":"do-not-print-this"}'):
            with self.assertRaises(ValueError) as error:
                parse_config(raw)
            self.assertNotIn("do-not-print-this", str(error.exception))

    def test_server_error_after_publish_requires_confirmed_state(self):
        failure = subprocess.CalledProcessError(1, ["gh", "release", "edit"])
        with patch.object(publisher, "gh", side_effect=failure) as mutation, patch.object(publisher, "api", return_value={"draft": False, "prerelease": True}):
            publisher.finish_release("test")
            self.assertEqual(mutation.call_count, 1)
        with patch.object(publisher, "gh", side_effect=failure), patch.object(publisher, "api", return_value={"draft": True, "prerelease": True}):
            with self.assertRaises(subprocess.CalledProcessError):
                publisher.finish_release("test")


if __name__ == "__main__":
    unittest.main()
