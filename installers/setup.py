#!/usr/bin/env python3
"""Install or update Aster experiments from a single verified GitHub revision.

Python 3.10+, standard library only. Browser profiles live outside this managed
code directory. This installer is for the standalone Linux WebKit application.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
from urllib.parse import quote
from urllib.request import Request, urlopen

REPOSITORY = "xatusbetazx17/aster-browser"
BRANCH = "codex/aster-webkit-desktop"
API = f"https://api.github.com/repos/{REPOSITORY}"
MAX_FILE = 2 * 1024 * 1024
MAX_TOTAL = 12 * 1024 * 1024
MARKER = "aster-install.json"
# The previously generated launcher remains valid for existing WebKit installs.
PREVIOUS_LAUNCHER_SHA256 = '195abe6683fcb4d93960d3cdd79c8781a9d92cfee29821d10f8f9d2de2886eea'
LAUNCHER = '''"""Launch the currently installed Aster experiment, or update its code."""
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent
state = json.loads((root / "aster-install.json").read_text(encoding="utf-8"))
commit = state["commit"]
if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
    raise SystemExit("Invalid installation state. Run the setup script again.")
release = root / "releases" / commit
args = sys.argv[1:]
if args and args[0] == "--update":
    command = [sys.executable, str(release / "installers/setup.py"),
               "--edition", state["edition"], "--install-dir", str(root), *args[1:]]
elif state["edition"] == "webkit":
    command = ["/usr/bin/python3", str(release / "experiments/webkit/run_aster_webkit.py"), *args]
else:
    raise SystemExit("This setup supports standalone Aster only. See the platform guides.")
raise SystemExit(subprocess.call(command))
'''


class SetupError(RuntimeError):
    pass


def safe_path(value: str) -> str:
    if not isinstance(value, str) or "\\" in value or ":" in value or any(ord(c) < 32 for c in value):
        raise SetupError("Invalid path in update metadata.")
    p = PurePosixPath(value)
    if p.is_absolute() or not p.parts or any(part in {".", ".."} for part in value.split("/")):
        raise SetupError("Unsafe path in update metadata.")
    return p.as_posix()


def git_hash(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def download(url: str, limit: int = MAX_FILE) -> bytes:
    request = Request(url, headers={"User-Agent": "Aster-Setup/1", "Accept": "application/vnd.github+json"})
    try:
        with urlopen(request, timeout=45) as response:
            if not response.url.startswith("https://"):
                raise SetupError("An update download redirected away from HTTPS.")
            data = response.read(limit + 1)
    except OSError as error:
        raise SetupError("Download failed. Check the connection or GitHub rate limit and retry. " + str(error)) from error
    if len(data) > limit:
        raise SetupError("Update file exceeded the size limit.")
    return data


class Source:
    def head(self) -> str:
        data = json.loads(download(API + "/git/ref/heads/" + quote(BRANCH, safe="/")))
        value = data["object"]["sha"]
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
            raise SetupError("GitHub returned an invalid revision.")
        return value

    def files(self, commit: str, edition: str) -> list[dict]:
        data = json.loads(download(API + f"/git/trees/{commit}?recursive=1"))
        if data.get("truncated"):
            raise SetupError("GitHub returned an incomplete file list.")
        selected = []
        for entry in data["tree"]:
            path = safe_path(entry["path"])
            wanted = path in {"LICENSE", "README.md", "PROJECT_DIRECTION.md"} or path.startswith((f"experiments/{edition}/", "installers/", "docs/setup/"))
            if not wanted or entry["type"] == "tree":
                continue
            if entry["type"] != "blob" or entry["mode"] not in {"100644", "100755"}:
                raise SetupError("Links and special files are not accepted in updates.")
            if not re.fullmatch(r"[0-9a-f]{40}", entry["sha"]) or not 0 <= entry["size"] <= MAX_FILE:
                raise SetupError("Invalid update file metadata.")
            selected.append({"path": path, "sha": entry["sha"], "size": entry["size"]})
        paths = {f["path"] for f in selected}
        required = {"installers/setup.py", "LICENSE", "README.md"}
        required.add("experiments/webkit/run_aster_webkit.py")
        if not required <= paths or len(paths) != len(selected) or len(selected) > 150 or sum(f["size"] for f in selected) > MAX_TOTAL:
            raise SetupError("The revision does not contain a complete, bounded Aster package.")
        return selected

    def file(self, commit: str, entry: dict) -> bytes:
        data = download(f"https://raw.githubusercontent.com/{REPOSITORY}/{commit}/" + quote(entry["path"], safe="/"))
        if len(data) != entry["size"] or git_hash(data) != entry["sha"]:
            raise SetupError("Integrity check failed for " + entry["path"])
        return data


def atomic_json(path: Path, value: dict):
    fd, name = tempfile.mkstemp(prefix=".aster-state-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(value, out, indent=2)
            out.write("\n")
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def read_state(root: Path, edition: str):
    marker = root / MARKER
    if root.is_symlink() or marker.is_symlink():
        raise SetupError("Choose a normal installation directory, not a symbolic link.")
    if not marker.exists():
        if root.exists() and any(root.iterdir()):
            raise SetupError("This directory is not managed by Aster Setup. Choose a new --install-dir; existing files were preserved.")
        return None
    try:
        state = json.loads(marker.read_text(encoding="utf-8"))
        if state["format"] != 1 or state["repository"] != REPOSITORY or state["branch"] != BRANCH or state["edition"] != edition:
            raise ValueError("Wrong installation identity")
        for key in ("commit", "previous"):
            if state.get(key) is not None and not re.fullmatch(r"[0-9a-f]{40}", state[key]):
                raise ValueError("Invalid commit")
        return state
    except (OSError, KeyError, ValueError, TypeError) as error:
        raise SetupError("Installation state is invalid. It was preserved; choose another --install-dir.") from error


@contextmanager
def lock(root: Path):
    path = root / ".setup-lock"
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as error:
        raise SetupError(f"Another setup may be running. If it crashed, close it before removing {path}.") from error
    try:
        with os.fdopen(fd, "w") as out:
            out.write(str(os.getpid()))
        yield
    finally:
        path.unlink(missing_ok=True)


def verify_release(release: Path):
    try:
        if release.is_symlink():
            raise ValueError("linked release")
        entries = json.loads((release / "aster-files.json").read_text(encoding="utf-8"))
        if not isinstance(entries, list) or not entries:
            raise ValueError("empty file manifest")
        for entry in entries:
            path = release / safe_path(entry["path"])
            if path.is_symlink() or release.resolve() not in path.resolve().parents or git_hash(path.read_bytes()) != entry["sha"]:
                raise ValueError(entry["path"])
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SetupError("Installed source files have changed or are missing. Keep your edits and use another --install-dir, or restore the files before updating.") from error


def install(root: Path, edition: str, runtime: str, source: Source, rollback: bool = False) -> dict:
    if edition != "webkit":
        raise SetupError("Only standalone Aster WebKit is supported by this installer.")
    state = read_state(root, edition)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    with lock(root):
        # Re-read after locking, so concurrent setup cannot use stale state.
        if (root / MARKER).exists():
            state = read_state(root, edition)
        else:
            state = {"format": 1, "repository": REPOSITORY, "branch": BRANCH, "edition": edition, "commit": None, "previous": None, "runtime": runtime}
            atomic_json(root / MARKER, state)
        if (root / "releases").is_symlink():
            raise SetupError("The releases directory must not be a symbolic link.")
        if state["commit"]:
            verify_release(root / "releases" / state["commit"])
        launcher = root / "start-aster.py"
        launcher_hash = hashlib.sha256(launcher.read_text(encoding="utf-8").encode()).hexdigest() if launcher.exists() and not launcher.is_symlink() else None
        if launcher.is_symlink() or (launcher_hash is not None and launcher_hash not in {hashlib.sha256(LAUNCHER.encode()).hexdigest(), PREVIOUS_LAUNCHER_SHA256}):
            raise SetupError("The generated launcher has been edited. Preserve it and use another --install-dir.")
        target = state.get("previous") if rollback else source.head()
        if not target:
            raise SetupError("No previous release is available to roll back to.")
        release = root / "releases" / target
        if release.exists():
            verify_release(release)
        elif rollback:
            raise SetupError("The previous release directory is missing.")
        else:
            entries = source.files(target, edition)
            release.parent.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(prefix=".aster-download-", dir=root) as temporary:
                stage = Path(temporary) / "release"
                stage.mkdir()

                def fetch(entry):
                    data = source.file(target, entry)
                    # Also verify injected/test sources at this trust boundary.
                    if len(data) != entry["size"] or git_hash(data) != entry["sha"]:
                        raise SetupError("Integrity check failed for " + entry["path"])
                    path = stage / safe_path(entry["path"])
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)

                with ThreadPoolExecutor(max_workers=6) as pool:
                    list(pool.map(fetch, entries))
                atomic_json(stage / "aster-files.json", entries)
                verify_release(stage)
                stage.rename(release)
        if not launcher.exists():
            launcher.write_text(LAUNCHER, encoding="utf-8")
        next_state = {**state, "commit": target, "runtime": runtime}
        if target != state["commit"]:
            next_state["previous"] = state["commit"]
        atomic_json(root / MARKER, next_state)
        return {**next_state, "changed": target != state["commit"]}


def distro() -> dict[str, str]:
    try:
        return dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().replace('"', '').splitlines() if "=" in line)
    except OSError:
        return {}


def system(command):
    print("Running:", " ".join(command), flush=True)
    result = subprocess.run(command)
    if result.returncode:
        raise SetupError("The package/runtime command failed. Existing Aster code was not replaced.")


def linux_packages(packages: dict[str, list[str]]):
    info = distro()
    family = " ".join((info.get("ID", ""), info.get("ID_LIKE", ""))).split()
    if info.get("ID") == "steamos" or Path("/run/ostree-booted").exists():
        raise SetupError("A standalone Aster package for this immutable system is not ready. See docs/setup/linux.md; the OS image was not changed.")
    sudo = [] if os.geteuid() == 0 else ["sudo"]
    if any(x in family for x in ("debian", "ubuntu")):
        system([*sudo, "apt-get", "update"])
        system([*sudo, "apt-get", "install", "-y", *packages["apt"]])
    elif "arch" in family:
        system([*sudo, "pacman", "-S", "--needed", *packages["pacman"]])
    elif "fedora" in family:
        system([*sudo, "dnf", "install", "-y", *packages["dnf"]])
    else:
        raise SetupError("Automatic dependencies support Debian/Ubuntu, Arch and Fedora. Install the runtime yourself and use --skip-dependencies; see docs/setup/linux.md.")


def runtime_setup(edition: str, skip: bool, preferred: str | None = None) -> str:
    if edition != "webkit" or sys.platform != "linux":
        raise SetupError("A standalone Windows/Android build is not available yet. This setup supports Linux Aster only.")
    if distro().get("ID") == "steamos" or Path("/run/ostree-booted").exists():
        raise SetupError("A standalone SteamOS/immutable-Linux package is not ready. The OS image was not changed.")
    if not skip:
        linux_packages({"apt": ["python3-gi", "gir1.2-gtk-4.0", "gir1.2-adw-1", "gir1.2-webkit-6.0"], "pacman": ["python", "python-gobject", "gtk4", "libadwaita", "webkitgtk-6.0"], "dnf": ["python3-gobject", "gtk4", "libadwaita", "webkitgtk6.0"]})
    system(["/usr/bin/python3", "-c", "import gi; gi.require_version('Gtk','4.0'); gi.require_version('Adw','1'); gi.require_version('WebKit','6.0'); from gi.repository import Gtk,Adw,WebKit"])
    return "webkit"


def main(argv=None):
    if sys.version_info < (3, 10):
        raise SystemExit("Aster setup requires Python 3.10 or newer.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edition", choices=("webkit",), default="webkit")
    parser.add_argument("--install-dir", type=Path)
    parser.add_argument("--skip-dependencies", action="store_true", help="Only check the runtime; do not invoke a package manager")
    parser.add_argument("--check", action="store_true", help="Show local installation status without downloading or installing")
    parser.add_argument("--rollback", action="store_true", help="Switch back to the previous managed code revision")
    args = parser.parse_args(argv)
    base = Path(os.environ.get("LOCALAPPDATA") or Path.home()) if sys.platform == "win32" else Path.home() / ".local/share"
    root = (args.install_dir or base / f"aster-testing-{args.edition}").expanduser().absolute()
    try:
        state = read_state(root, args.edition)
        if sys.platform != "linux" and not args.check:
            raise SetupError("A standalone build for this platform is not available yet. No browser was installed or substituted.")
        if args.check:
            print("Edition:", args.edition, "\nInstall directory:", root)
            print("Revision:", state["commit"] if state else "not installed")
            return 0
        if args.rollback:
            if not state:
                raise SetupError("No installation to roll back.")
            runtime = state["runtime"]
        else:
            if state and state["commit"]:
                verify_release(root / "releases" / state["commit"])
            runtime = runtime_setup(args.edition, args.skip_dependencies, state.get("runtime") if state else None)
        result = install(root, args.edition, runtime, Source(), args.rollback)
        print("Aster code updated." if result["changed"] else "Aster code is already up to date.")
        print("Revision:", result["commit"])
        print("Launcher:", root / "start-aster.py")
        print("To update again, run this setup command again, or run the launcher with --update.")
        return 0
    except (SetupError, OSError, ValueError, KeyError) as error:
        print("Aster setup:", error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
