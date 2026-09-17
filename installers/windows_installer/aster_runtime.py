"""Stand up the Python the installed browser runs on.

Aster is a PyQt application, and PyQt6 with QtWebEngine is an order of magnitude
larger than this installer: bundling the wheels would turn a 20 MB download into
a 200 MB one. So the exe carries the application source and nothing else, and
the interpreter has to come from somewhere at install time.

It used to come from PATH. AsterBrowser.bat looked for a runtime directory that
no step of the install ever created, fell through to `py -3`, and ran the app on
whatever that happened to be. On the machine it was built on that worked, because
PyQt6 was installed there for development. Anywhere else the install reported
success and the browser then failed to start with nothing on screen to say why -
the launcher even sent the import error to nul.

The installer now builds a runtime of its own beside the application. A suitable
interpreter already on the machine is used to make a virtual environment, which
costs no download; failing that, python.org's own installer is fetched and run
privately into the install directory. Either way the requirements go into that
runtime and not into anyone's system Python, and the install does not finish
until the browser's imports have been proved to work.

Everything here takes its subprocess runner and its downloader as arguments, so
installers/tests/test_runtime_provisioning.py can drive the whole sequence -
including the failures - without a network, a Windows box, or a real Python
install.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile

RUNTIME_DIR_NAME = "runtime"

#: What the browser has to be able to import before an install is called done.
#: QtWebEngine is the piece that is missing most often: it ships as a separate
#: wheel from PyQt6 itself, so an interpreter can import PyQt6 happily and still
#: be unable to open a single page.
RUNTIME_PROBE = "import PyQt6.QtWebEngineWidgets"

#: PyQt6 6.10 publishes no wheels below the first of these, and none yet for the
#: second, so an interpreter outside the range cannot run the browser however
#: healthy it looks.
SUPPORTED_PYTHON = ((3, 10), (3, 14))

#: Interpreters to try building the runtime from before downloading one, newest
#: first. The Microsoft Store stub answers on PATH but exits non-zero without
#: running anything, so it fails the probe below and is passed over like any
#: other unusable interpreter rather than being special-cased.
SYSTEM_PYTHONS = (
    ("py", "-3.13"),
    ("py", "-3.12"),
    ("py", "-3.11"),
    ("py", "-3.10"),
    ("py", "-3"),
    ("python",),
)

#: Tried in order; the first that downloads is used. Several are listed because
#: python.org does remove point releases, and an installer that dies on a 404 is
#: worse than one that is a patch version behind.
PYTHON_VERSIONS = ("3.12.10", "3.12.8", "3.12.7", "3.11.9")
PYTHON_URL = "https://www.python.org/ftp/python/{version}/python-{version}-amd64.exe"

#: A private install, not a system one: no PATH entry, no file associations, no
#: Start menu shortcuts, no launcher registration. Nothing here may touch a
#: Python the person installed themselves - this runtime belongs to Aster and
#: disappears with it.
PYTHON_INSTALL_OPTIONS = (
    "/quiet",
    "InstallAllUsers=0",
    "PrependPath=0",
    "AssociateFiles=0",
    "Shortcuts=0",
    "Include_launcher=0",
    "InstallLauncherAllUsers=0",
    "Include_doc=0",
    "Include_test=0",
    "Include_tcltk=0",
    "Include_pip=1",
)


class ProvisioningError(RuntimeError):
    """Raised with a message meant for the person running setup, not a trace."""


def _run(args, timeout: int = 1800):
    """Run a child process without flashing a console window at anyone."""
    return subprocess.run(
        list(args),
        capture_output=True,
        text=True,
        timeout=timeout,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def runtime_python(runtime_dir: str, exists=os.path.isfile) -> str | None:
    """The console interpreter inside a runtime, whichever way it was built.

    A virtual environment keeps it in Scripts\\; a private python.org install
    keeps it at the top. Both layouts are read here so an installation made by
    either route stays launchable, including one made by an older installer.
    """
    for relative in (("python.exe",), ("Scripts", "python.exe")):
        candidate = os.path.join(runtime_dir, *relative)
        if exists(candidate):
            return candidate
    return None


def runtime_pythonw(runtime_dir: str, exists=os.path.isfile) -> str | None:
    """The windowed interpreter, which is what the launcher should prefer.

    Running the browser on python.exe leaves a console window open behind it for
    as long as it is running.
    """
    for relative in (("pythonw.exe",), ("Scripts", "pythonw.exe")):
        candidate = os.path.join(runtime_dir, *relative)
        if exists(candidate):
            return candidate
    return None


def runtime_satisfies(python_exe: str, run=_run) -> bool:
    """True when this interpreter can import what the browser needs."""
    try:
        return run([python_exe, "-c", RUNTIME_PROBE], timeout=120).returncode == 0
    except Exception:
        # A missing exe, a timeout, a runtime that was half deleted - all of it
        # means the same thing here, which is that it has to be rebuilt.
        return False


def usable_python(candidate, run=_run) -> bool:
    """True when the runtime can be built from this interpreter."""
    low, high = SUPPORTED_PYTHON
    probe = (
        "import sys, venv; "
        f"raise SystemExit(0 if {low} <= sys.version_info[:2] < {high} else 1)"
    )
    try:
        return run([*candidate, "-c", probe], timeout=60).returncode == 0
    except Exception:
        return False


def find_system_python(run=_run, candidates=SYSTEM_PYTHONS):
    """The first interpreter on the machine the runtime can be built from."""
    for candidate in candidates:
        if usable_python(candidate, run=run):
            return tuple(candidate)
    return None


def python_install_command(installer_exe: str, runtime_dir: str) -> list[str]:
    return [installer_exe, *PYTHON_INSTALL_OPTIONS, f"TargetDir={runtime_dir}"]


def pip_install_command(python_exe: str, requirements: str) -> list[str]:
    return [
        python_exe, "-m", "pip", "install",
        "--no-input",
        "--disable-pip-version-check",
        "--no-warn-script-location",
        "-r", requirements,
    ]


def _tail(result, lines: int = 12) -> str:
    """The end of a failed command's output - the part that says why."""
    text = ((result.stderr or "") + "\n" + (result.stdout or "")).strip()
    return "\n".join(text.splitlines()[-lines:])


def download(url: str, destination: str, progress=None) -> str:
    """Fetch a file, reporting how far along it is as it goes."""
    import urllib.request

    with urllib.request.urlopen(url, timeout=60) as response:
        total = int(response.headers.get("Content-Length") or 0)
        done = 0
        with open(destination, "wb") as handle:
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                handle.write(chunk)
                done += len(chunk)
                if progress is not None and total:
                    progress(done / total)
    return destination


def install_private_python(runtime_dir, *, log, run=_run, fetch=download,
                           versions=PYTHON_VERSIONS, progress=None) -> None:
    """Download python.org's installer and run it into the install directory."""
    failures = []
    for version in versions:
        url = PYTHON_URL.format(version=version)
        target = os.path.join(tempfile.gettempdir(), f"python-{version}-amd64.exe")
        try:
            log(f"Python {version} indiriliyor...")
            fetch(url, target, progress)
        except Exception as error:
            failures.append(f"{version}: indirilemedi ({error})")
            log(f"   alinamadi: {error}")
            continue

        log(f"Python {version} kuruluyor: {runtime_dir}")
        try:
            result = run(python_install_command(target, runtime_dir))
        except Exception as error:
            failures.append(f"{version}: kurulum calistirilamadi ({error})")
            continue
        finally:
            # The installer is ~27 MB of temp file nobody needs afterwards, and
            # leaving it behind is how a Downloads folder fills up.
            try:
                os.remove(target)
            except OSError:
                pass

        if result.returncode == 0 and runtime_python(runtime_dir):
            return
        failures.append(f"{version}: kurulum {result.returncode} ile bitti")
        shutil.rmtree(runtime_dir, ignore_errors=True)

    raise ProvisioningError(
        "Python ortami kurulamadi. Kurulum icin internet baglantisi gerekiyor.\n"
        + "\n".join(failures)
    )


def install_requirements(python_exe: str, app_dir: str, *, log, run=_run) -> None:
    requirements = os.path.join(app_dir, "requirements.txt")
    if not os.path.isfile(requirements):
        raise ProvisioningError(
            "requirements.txt paketten cikmadi; hangi bagimliliklarin gerektigi "
            "bilinmeden kurulum tamamlanamaz."
        )
    log("Bagimliliklar kuruluyor (PyQt6, QtWebEngine) - birkac dakika surebilir...")
    result = run(pip_install_command(python_exe, requirements), timeout=3600)
    if result.returncode != 0:
        raise ProvisioningError("Bagimliliklar kurulamadi:\n" + _tail(result))


def provision_runtime(root: str, app_dir: str, *, log, status=None, run=_run,
                      fetch=download, versions=PYTHON_VERSIONS,
                      candidates=SYSTEM_PYTHONS) -> str:
    """Return the interpreter Aster will run on, building one if it has to.

    A runtime left by an earlier install is kept when it still satisfies the
    browser's imports, so an update costs no download at all. One that no longer
    does is rebuilt rather than patched: a half-built environment is the thing
    that produced the silent startup failure in the first place.
    """
    def say(text: str, fraction: float) -> None:
        if status is not None:
            status(text, fraction)

    runtime_dir = os.path.join(root, RUNTIME_DIR_NAME)
    existing = runtime_python(runtime_dir, exists=os.path.isfile)
    if existing and runtime_satisfies(existing, run=run):
        log(f"Mevcut Python ortami kullanilabilir durumda: {existing}")
        return existing
    if existing:
        log("Mevcut Python ortami eksik; yeniden kuruluyor.")
    shutil.rmtree(runtime_dir, ignore_errors=True)

    say("Python ortami hazirlaniyor...", 0.46)
    source = find_system_python(run=run, candidates=candidates)
    if source:
        log(f"Sistemdeki Python kullaniliyor: {' '.join(source)}")
        try:
            result = run([*source, "-m", "venv", runtime_dir], timeout=600)
        except Exception as error:  # pragma: no cover - depends on the machine
            log(f"Sanal ortam kurulamadi: {error}")
        else:
            if result.returncode != 0:
                log("Sanal ortam kurulamadi: " + _tail(result, 6))
                shutil.rmtree(runtime_dir, ignore_errors=True)
    else:
        log("Uygun bir Python bulunamadi; Aster kendi Python'unu kuracak.")

    if runtime_python(runtime_dir) is None:
        say("Python indiriliyor...", 0.50)
        install_private_python(
            runtime_dir, log=log, run=run, fetch=fetch, versions=versions,
            progress=lambda done: say(f"Python indiriliyor... %{int(done * 100)}",
                                      0.50 + 0.08 * done),
        )

    python_exe = runtime_python(runtime_dir)
    if python_exe is None:  # pragma: no cover - install_private_python raises first
        raise ProvisioningError("Python ortami kurulamadi.")

    say("Bagimliliklar kuruluyor...", 0.60)
    install_requirements(python_exe, app_dir, log=log, run=run)

    say("Kurulum dogrulaniyor...", 0.70)
    if not runtime_satisfies(python_exe, run=run):
        raise ProvisioningError(
            "Bagimliliklar kuruldu ama tarayici hala baslatilamiyor: "
            f"'{RUNTIME_PROBE}' calismadi. Kurulum yarim birakildi."
        )
    log(f"Python ortami hazir: {python_exe}")
    return python_exe
