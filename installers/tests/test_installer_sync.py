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
from functools import lru_cache
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
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


@lru_cache(maxsize=1)
def working_bash() -> str | None:
    """A bash that can actually run a POSIX snippet, or None if there is none.

    The two tests below run install-linux.sh's own msg/die functions rather than
    pattern-matching them, so they need a real shell. On the Windows runner the
    `bash` first on PATH is C:\Windows\System32\bash.exe, the WSL launcher,
    which exits 1 without writing to stderr when no distribution is installed:
    both tests failed with an empty message instead of a result. Git Bash ships
    with that image and does run the snippets, so probe each candidate and take
    the first that answers.
    """
    candidates = []
    found = shutil.which("bash")
    if found:
        candidates.append(found)
    if os.name == "nt":
        candidates += [r"C:\Program Files\Git\bin\bash.exe",
                       r"C:\Program Files (x86)\Git\bin\bash.exe"]
    for candidate in candidates:
        try:
            probe = subprocess.run([candidate, "-c", "printf ok"],
                                   capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0 and probe.stdout.strip() == "ok":
            return candidate
    return None


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

    def test_progress_messages_end_in_a_real_newline(self):
        """Every installer message ran together, each ending in a literal \\n.

        SCRIPT_HEADER is a raw Python string, so the printf format written as
        '%s\\n' reached the shell with both backslashes intact and printf emitted
        one backslash followed by an n instead of ending the line. The functions
        are extracted and run here rather than pattern-matched, because the bug
        survived two readings of the source and only shows up in the output.
        """
        shell = working_bash()
        if shell is None:
            self.skipTest("no working bash to run install-linux.sh's own functions")

        body = INSTALLER.read_text(encoding="utf-8", errors="replace")
        definitions = [line for line in body.splitlines()
                       if line.startswith(("msg() {", "die() {"))]
        self.assertEqual(len(definitions), 2, f"msg/die are not defined as expected in install-linux.sh. {REGENERATE}")

        script = "\n".join(definitions) + '\nmsg "first"\nmsg "second"\n'
        result = subprocess.run([shell, "-c", script], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, f"{shell} failed: {result.stderr or result.stdout!r}")
        self.assertEqual(
            result.stdout,
            "[Aster Installer] first\n[Aster Installer] second\n",
            "install-linux.sh prints a literal backslash-n instead of ending each message. "
            f"Fix the printf format in build_linux_installer.py. {REGENERATE}",
        )

    def test_die_reports_on_stderr_and_fails(self):
        shell = working_bash()
        if shell is None:
            self.skipTest("no working bash to run install-linux.sh's own functions")

        body = INSTALLER.read_text(encoding="utf-8", errors="replace")
        definition = next(line for line in body.splitlines() if line.startswith("die() {"))
        result = subprocess.run([shell, "-c", definition + '\ndie "broken"\n'],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 1, f"{shell}: {result.stderr or result.stdout!r}")
        self.assertEqual(result.stderr, "[Aster Installer] ERROR: broken\n")
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
