"""Check install-linux.sh still carries exactly what the kit holds.

install-linux.sh is generated from Aster-Browser-Windows-Kit-v16.zip, and it went
stale once already: it shipped 184 files instead of 197, dropping the whole
aster_browser/locales package. This test is the guard against that happening again.

It deliberately compares the *payload contents*, not the bytes of the script.
zlib's deflate output differs between platforms and versions, so a script built on
Windows and one built on Linux are both correct yet not byte-identical. Comparing
file names and hashes checks what actually matters and passes on every runner.
"""
from __future__ import annotations

import base64
import hashlib
import io
from pathlib import Path
import tarfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
KIT = ROOT / "Aster-Browser-Windows-Kit-v16.zip"
INSTALLER = ROOT / "install-linux.sh"
KIT_PREFIX = "aster-browser-windows-kit/"
SKIPPED_PREFIX = "tools/legacy-update-scripts/"
PAYLOAD_MARKER = "__ASTER_PAYLOAD_BELOW__"
REGENERATE = "Regenerate it with: python installers/build_linux_installer.py"


def kit_entries() -> dict[str, str]:
    """The files build_linux_installer.py would put in the payload, by sha256."""
    entries = {}
    with zipfile.ZipFile(KIT) as kit:
        for item in kit.infolist():
            if not item.filename.startswith(KIT_PREFIX):
                continue
            name = item.filename[len(KIT_PREFIX):]
            if not name or name.endswith("/") or name.startswith(SKIPPED_PREFIX):
                continue
            entries[name] = hashlib.sha256(kit.read(item.filename)).hexdigest()
    return entries


def payload_entries() -> dict[str, str]:
    """The files install-linux.sh actually ships, by sha256."""
    raw = INSTALLER.read_bytes()
    marker = raw.find(("\n" + PAYLOAD_MARKER + "\n").encode())
    if marker == -1:
        raise AssertionError(f"{PAYLOAD_MARKER} is missing from install-linux.sh. {REGENERATE}")
    encoded = raw[marker + len(PAYLOAD_MARKER) + 2:]
    entries = {}
    with tarfile.open(fileobj=io.BytesIO(base64.b64decode(encoded)), mode="r:gz") as tar:
        for member in tar:
            if not member.isfile():
                continue
            handle = tar.extractfile(member)
            assert handle is not None, member.name
            name = member.name[2:] if member.name.startswith("./") else member.name
            entries[name] = hashlib.sha256(handle.read()).hexdigest()
    return entries


class InstallerMatchesKit(unittest.TestCase):
    def setUp(self):
        self.expected = kit_entries()
        self.actual = payload_entries()

    def test_no_files_are_missing(self):
        missing = sorted(set(self.expected) - set(self.actual))
        self.assertFalse(missing, f"install-linux.sh is stale and omits {len(missing)} kit files, "
                                  f"starting with {missing[:5]}. {REGENERATE}")

    def test_no_unexpected_files_are_shipped(self):
        extra = sorted(set(self.actual) - set(self.expected))
        self.assertFalse(extra, f"install-linux.sh ships {len(extra)} files the kit does not have: "
                                f"{extra[:5]}. {REGENERATE}")

    def test_shipped_files_match_the_kit(self):
        changed = sorted(n for n in set(self.expected) & set(self.actual)
                         if self.expected[n] != self.actual[n])
        self.assertFalse(changed, f"install-linux.sh ships {len(changed)} files whose contents differ "
                                  f"from the kit: {changed[:5]}. {REGENERATE}")

    def test_localization_is_present(self):
        """The exact regression this guard exists for."""
        self.assertIn("aster_browser/translations.py", self.actual, REGENERATE)
        catalogs = [n for n in self.actual if n.startswith("aster_browser/locales/") and n.endswith(".py")]
        self.assertGreaterEqual(len(catalogs), 12, f"only {len(catalogs)} locale catalogs shipped. {REGENERATE}")

    def test_the_launcher_is_present(self):
        self.assertIn("run_aster.py", self.actual, REGENERATE)


class InstallerScriptHeader(unittest.TestCase):
    def test_header_matches_the_generator(self):
        """A hand-edited script body would be silently overwritten on the next build."""
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "aster_build_linux_installer", ROOT / "installers" / "build_linux_installer.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        expected = module.SCRIPT_HEADER.replace("\r\n", "\n").encode("utf-8")
        self.assertTrue(INSTALLER.read_bytes().startswith(expected),
                        "install-linux.sh's script body differs from build_linux_installer.py's "
                        f"SCRIPT_HEADER. Edit the generator, not the script. {REGENERATE}")


if __name__ == "__main__":
    unittest.main()
