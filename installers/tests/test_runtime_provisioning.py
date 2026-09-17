"""The installer has to leave behind a Python that can actually run the browser.

Before this, AsterBrowser.bat looked for a runtime directory no step of the
install created, fell through to whatever `py -3` resolved to, and sent the
resulting import error to nul. On the machine the exe was built on that worked,
because PyQt6 was installed there. Anywhere else setup reported success and the
browser then failed to start in silence.

aster_runtime now builds that runtime - from an interpreter already on the
machine where there is a usable one, by fetching python.org's own installer
where there is not - and refuses to call the install finished until the
browser's imports have been proved against it. These tests drive that whole
sequence, including every way it can fail, with a fake machine: no network, no
Windows, no real Python install. The runtime directory is real, because the code
decides what to do by looking at the files that appear in it.
"""
from __future__ import annotations

from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "installers" / "windows_installer"))

import aster_runtime as runtime  # noqa: E402 - resolved via the path above


class Result:
    """Just enough of subprocess.CompletedProcess for the code under test."""

    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FakeMachine:
    """A machine that answers commands the way a real one would.

    Making a virtual environment or running python.org's installer puts real
    (empty) exe files where the real ones would land, because that is what
    aster_runtime reads to decide whether a step worked.
    """

    def __init__(self, *, system_python: str | None = "3.12", imports_ok_after_pip=True,
                 pip_returncode=0, installer_returncode=0, download_fails=()):
        self.system_python = system_python
        self.imports_ok_after_pip = imports_ok_after_pip
        self.pip_returncode = pip_returncode
        self.installer_returncode = installer_returncode
        self.download_fails = set(download_fails)
        self.commands: list[list[str]] = []
        self.downloads: list[str] = []
        self.pip_calls = 0
        #: Whether the runtime currently holds the browser's dependencies. Set
        #: by a successful pip run, or up front to stand in for a runtime an
        #: earlier install already populated.
        self.populated = False

    # -- the two injection points ----------------------------------------
    def run(self, args, timeout=None):
        args = [str(a) for a in args]
        self.commands.append(args)
        if "venv" in args:
            return self._make_venv(args[-1])
        if any(a.startswith("TargetDir=") for a in args):
            return self._run_python_installer(args)
        if args[1:3] == ["-m", "pip"]:
            self.pip_calls += 1
            if self.pip_returncode == 0:
                self.populated = True
            return Result(self.pip_returncode, stderr="ERROR: could not build wheel\nsee log\n")
        if "-c" in args:
            return self._probe(args[args.index("-c") + 1])
        return Result(0)

    def fetch(self, url, destination, progress=None):
        self.downloads.append(url)
        if any(bad in url for bad in self.download_fails):
            raise OSError("HTTP Error 404: Not Found")
        Path(destination).write_bytes(b"not really an installer")
        if progress is not None:
            progress(1.0)
        return destination

    # -- what each command does ------------------------------------------
    def _probe(self, source):
        if "version_info" in source:
            # find_system_python asking an interpreter whether it will do.
            return Result(1 if self.system_python is None else 0)
        if source == runtime.RUNTIME_PROBE:
            # Only a runtime pip has populated can import the browser's
            # dependencies; one that merely exists cannot.
            return Result(0 if (self.populated and self.imports_ok_after_pip) else 1)
        return Result(0)

    def _make_venv(self, target):
        scripts = Path(target) / "Scripts"
        scripts.mkdir(parents=True, exist_ok=True)
        (scripts / "python.exe").write_bytes(b"")
        (scripts / "pythonw.exe").write_bytes(b"")
        return Result(0)

    def _run_python_installer(self, args):
        if self.installer_returncode != 0:
            return Result(self.installer_returncode, stderr="install failed")
        target = next(a.split("=", 1)[1] for a in args if str(a).startswith("TargetDir="))
        root = Path(target)
        (root / "Scripts").mkdir(parents=True, exist_ok=True)
        (root / "python.exe").write_bytes(b"")
        (root / "pythonw.exe").write_bytes(b"")
        return Result(0)


class ProvisioningTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.root = self.tmp / "AsterBrowser"
        self.app = self.root / "app"
        self.app.mkdir(parents=True)
        (self.app / "requirements.txt").write_text("PyQt6>=6.10\nPyQt6-WebEngine>=6.10\n")
        self.logged: list[str] = []

    def provision(self, machine, **kwargs):
        return runtime.provision_runtime(
            str(self.root), str(self.app),
            log=self.logged.append, run=machine.run, fetch=machine.fetch, **kwargs,
        )

    def log_text(self) -> str:
        return "\n".join(self.logged)


class FindingAnInterpreter(ProvisioningTestBase):
    def test_a_system_python_is_used_and_nothing_is_downloaded(self):
        """The common case costs no network at all."""
        machine = FakeMachine(system_python="3.12")
        python_exe = self.provision(machine)

        self.assertTrue(Path(python_exe).is_file())
        self.assertEqual(machine.downloads, [], "downloaded a Python it did not need")
        self.assertEqual(machine.pip_calls, 1)

    def test_without_a_usable_python_one_is_fetched_and_installed_privately(self):
        machine = FakeMachine(system_python=None)
        python_exe = self.provision(machine)

        self.assertEqual(len(machine.downloads), 1)
        self.assertTrue(machine.downloads[0].endswith("-amd64.exe"))
        self.assertTrue(Path(python_exe).is_file())

    def test_the_fetched_python_is_installed_without_touching_the_system(self):
        """It must not land on PATH, take file associations, or register itself."""
        machine = FakeMachine(system_python=None)
        self.provision(machine)

        install = next(c for c in machine.commands
                       if any(str(a).startswith("TargetDir=") for a in c))
        self.assertIn("/quiet", install)
        self.assertIn("PrependPath=0", install)
        self.assertIn("InstallAllUsers=0", install)
        self.assertIn("AssociateFiles=0", install)
        self.assertIn("Include_launcher=0", install)
        self.assertIn("Include_pip=1", install)
        self.assertIn(f"TargetDir={self.root / runtime.RUNTIME_DIR_NAME}", install)

    def test_a_removed_point_release_falls_through_to_the_next(self):
        """python.org does retire downloads; a 404 must not fail the install."""
        machine = FakeMachine(system_python=None,
                              download_fails=[runtime.PYTHON_VERSIONS[0]])
        self.provision(machine)

        self.assertEqual(len(machine.downloads), 2)
        self.assertIn(runtime.PYTHON_VERSIONS[1], machine.downloads[1])

    def test_offline_with_no_python_says_so_instead_of_finishing(self):
        machine = FakeMachine(system_python=None,
                              download_fails=list(runtime.PYTHON_VERSIONS))
        with self.assertRaises(runtime.ProvisioningError) as caught:
            self.provision(machine)
        self.assertIn("internet", str(caught.exception).lower())


class ReusingWhatIsAlreadyThere(ProvisioningTestBase):
    def test_a_working_runtime_is_kept_so_an_update_costs_nothing(self):
        machine = FakeMachine(system_python="3.12")
        first = self.provision(machine)

        again = FakeMachine(system_python="3.12")
        again.populated = True  # the runtime the first install left behind
        second = self.provision(again)

        self.assertEqual(first, second)
        self.assertEqual(again.downloads, [])
        self.assertEqual(again.pip_calls, 0, "reinstalled dependencies it already had")
        self.assertNotIn("venv", [a for c in again.commands for a in c])

    def test_a_half_built_runtime_is_rebuilt_rather_than_patched(self):
        """A runtime whose imports fail is exactly what caused the silent crash."""
        stale = self.root / runtime.RUNTIME_DIR_NAME / "Scripts"
        stale.mkdir(parents=True)
        (stale / "python.exe").write_bytes(b"")
        leftover = stale / "leftover.txt"
        leftover.write_bytes(b"")

        machine = FakeMachine(system_python="3.12")
        self.provision(machine)

        self.assertFalse(leftover.exists(), "the broken runtime was patched, not replaced")
        self.assertIn("venv", [a for c in machine.commands for a in c])
        self.assertEqual(machine.pip_calls, 1)


class RefusingToFinishHalfDone(ProvisioningTestBase):
    def test_pip_failing_stops_the_install_and_reports_why(self):
        machine = FakeMachine(system_python="3.12", pip_returncode=1)
        with self.assertRaises(runtime.ProvisioningError) as caught:
            self.provision(machine)
        self.assertIn("could not build wheel", str(caught.exception))

    def test_imports_still_failing_after_pip_is_not_called_success(self):
        """The whole point: setup must not say 'done' to a browser that won't run."""
        machine = FakeMachine(system_python="3.12", imports_ok_after_pip=False)
        with self.assertRaises(runtime.ProvisioningError) as caught:
            self.provision(machine)
        self.assertIn(runtime.RUNTIME_PROBE, str(caught.exception))

    def test_a_bundle_without_requirements_stops_rather_than_guessing(self):
        (self.app / "requirements.txt").unlink()
        machine = FakeMachine(system_python="3.12")
        with self.assertRaises(runtime.ProvisioningError):
            self.provision(machine)


class ReadingEitherLayout(unittest.TestCase):
    """A venv keeps the interpreter in Scripts\\; a private install does not."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def test_both_layouts_are_found(self):
        venv = self.tmp / "venv"
        (venv / "Scripts").mkdir(parents=True)
        (venv / "Scripts" / "python.exe").write_bytes(b"")
        (venv / "Scripts" / "pythonw.exe").write_bytes(b"")
        self.assertEqual(runtime.runtime_python(str(venv)), str(venv / "Scripts" / "python.exe"))
        self.assertEqual(runtime.runtime_pythonw(str(venv)), str(venv / "Scripts" / "pythonw.exe"))

        private = self.tmp / "private"
        private.mkdir()
        (private / "python.exe").write_bytes(b"")
        (private / "pythonw.exe").write_bytes(b"")
        self.assertEqual(runtime.runtime_python(str(private)), str(private / "python.exe"))
        self.assertEqual(runtime.runtime_pythonw(str(private)), str(private / "pythonw.exe"))

    def test_nothing_there_reads_as_nothing_there(self):
        self.assertIsNone(runtime.runtime_python(str(self.tmp / "absent")))
        self.assertIsNone(runtime.runtime_pythonw(str(self.tmp / "absent")))


class RejectingUnusableInterpreters(unittest.TestCase):
    def test_an_interpreter_that_exits_non_zero_is_passed_over(self):
        """The Microsoft Store stub answers on PATH and runs nothing."""
        seen = []

        def run(args, timeout=None):
            seen.append(args[0])
            return Result(0 if args[0] == "python" else 1)

        self.assertEqual(runtime.find_system_python(run=run), ("python",))
        self.assertEqual(seen[-1], "python")

    def test_no_interpreter_at_all_reads_as_none(self):
        def run(args, timeout=None):
            raise FileNotFoundError(args[0])

        self.assertIsNone(runtime.find_system_python(run=run))

    def test_a_runtime_that_cannot_import_the_browser_is_not_satisfying(self):
        def run(args, timeout=None):
            return Result(1)

        self.assertFalse(runtime.runtime_satisfies("python.exe", run=run))


class TheLauncherItWrites(unittest.TestCase):
    """The .bat files are written verbatim, so check what they actually say."""

    def setUp(self):
        import types

        for name in ("winreg", "customtkinter"):
            sys.modules.setdefault(name, types.ModuleType(name))
        if not hasattr(sys.modules["customtkinter"], "CTk"):
            sys.modules["customtkinter"].CTk = type("CTk", (), {})
        if "PIL" not in sys.modules:
            pil = types.ModuleType("PIL")
            pil.Image = types.ModuleType("PIL.Image")
            sys.modules["PIL"] = pil
            sys.modules["PIL.Image"] = pil.Image
        import installer_gui

        self.gui = installer_gui

    def test_the_launcher_prefers_the_runtime_and_never_falls_back_to_path(self):
        launcher = self.gui.LAUNCHER_BAT
        self.assertIn(r"%ASTER_HOME%runtime\pythonw.exe", launcher)
        self.assertIn(r"%ASTER_HOME%runtime\Scripts\pythonw.exe", launcher)
        self.assertNotIn("py -3", launcher, "still falling back to whatever is on PATH")
        self.assertNotIn("2>nul", launcher, "still hiding the reason it would not start")

    def test_the_launcher_is_what_puts_the_app_in_portable_mode(self):
        """Shortcuts point here rather than straight at pythonw for this reason.

        A shortcut that skipped the launcher would leave ASTER_PORTABLE unset,
        so settings, containers and parked tabs would go under the user profile
        instead of state\\ - and the two launch routes would not see each
        other's data.
        """
        launcher = self.gui.LAUNCHER_BAT
        self.assertIn('set "ASTER_PORTABLE=1"', launcher)
        self.assertIn('set "ASTER_PORTABLE_ROOT=%ASTER_HOME%state"', launcher)

    def test_a_missing_runtime_is_reported_rather_than_flashing_and_closing(self):
        launcher = self.gui.LAUNCHER_BAT
        self.assertIn("baslatilamadi", launcher)
        self.assertIn("pause", launcher)

    def test_the_troubleshooting_script_shows_the_import_error(self):
        debug = self.gui.DEBUG_BAT
        self.assertIn("import PyQt6.QtWebEngineWidgets", debug)
        self.assertIn("pause", debug)
        self.assertNotIn("start \"\"", debug, "a detached process prints nowhere")


if __name__ == "__main__":
    unittest.main()
