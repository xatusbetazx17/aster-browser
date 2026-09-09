"""Open an isolated Aster workspace profile in an installed official Firefox.

This launcher neither installs Firefox nor installs/signs the companion add-on.
It does not download or redistribute DRM components or alter Firefox policies.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.parse import urlsplit


def command(explicit=None, flatpak=False):
    if flatpak:
        executable = shutil.which("flatpak")
        if not executable:
            raise RuntimeError("Flatpak is not installed.")
        if subprocess.run([executable, "info", "org.mozilla.firefox"], capture_output=True).returncode:
            raise RuntimeError("Install Mozilla Firefox from Flathub first.")
        return [executable, "run", "org.mozilla.firefox"]
    if explicit:
        candidate = Path(explicit).expanduser().resolve()
        if not candidate.is_file():
            raise RuntimeError("The specified Firefox executable does not exist.")
        return [str(candidate)]
    detected = shutil.which("firefox") or shutil.which("firefox-esr")
    if detected:
        return [detected]
    for base in (os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"), os.environ.get("LOCALAPPDATA")):
        if base and (Path(base) / "Mozilla Firefox/firefox.exe").is_file():
            return [str(Path(base) / "Mozilla Firefox/firefox.exe")]
    if sys.platform == "darwin":
        candidate = Path("/Applications/Firefox.app/Contents/MacOS/firefox")
        if candidate.is_file():
            return [str(candidate)]
    raise RuntimeError("Install official Firefox, supply --firefox PATH, or use --flatpak on Linux/Steam Deck.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", nargs="?", default="https://www.primevideo.com/")
    parser.add_argument("--firefox", help="Path to an installed Firefox executable")
    parser.add_argument("--flatpak", action="store_true", help="Use installed org.mozilla.firefox on Linux")
    parser.add_argument("--check", action="store_true", help="Show launch configuration without creating a profile")
    args = parser.parse_args()
    try:
        url = urlsplit(args.url)
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or any(c.isspace() for c in args.url) or "\\" in args.url:
            raise RuntimeError("Supply an HTTP or HTTPS address without credentials.")
        executable = command(args.firefox, args.flatpak)
        if args.flatpak:
            profile = Path.home() / ".var/app/org.mozilla.firefox/aster-profile"
        elif sys.platform == "win32":
            profile = Path(os.environ.get("LOCALAPPDATA") or Path.home()) / "AsterCompanion/firefox-profile"
        else:
            profile = Path.home() / ".local/share/aster-companion/firefox-profile"
        launch = [*executable, "--no-remote", "--profile", str(profile), args.url]
        if args.check:
            print("Firefox command:", launch)
            print("The companion add-on must be installed separately. No playback verification was performed.")
            return 0
        profile.mkdir(parents=True, exist_ok=True, mode=0o700)
        return subprocess.call(launch)
    except (RuntimeError, OSError, ValueError) as error:
        print(f"Aster: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
