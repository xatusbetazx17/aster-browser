"""Optional DRM capsule: a separate licensed runtime for protected streams.

Aster's own engine path cannot play protected video. WebKit defaults
ENABLE_ENCRYPTED_MEDIA to ENABLE_EXPERIMENTAL_FEATURES, so distribution WebKitGTK
packages ship with EME compiled out and no Aster tab can hold a Widevine session
whatever setting is enabled. Rather than imply otherwise, Aster can hand one
protected service to a *capsule*: a separate
Chromium-family or Firefox runtime that carries its own licensed CDM, in its own
per-service profile directory.

Aster never downloads, bundles or redistributes a CDM. The capsule runtime
installs its own on the user's machine under that runtime's licence. Detecting a
capsule is not evidence that a service will play; only a real session is.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from .core import is_web_uri, profile_paths


@dataclass(frozen=True)
class ServiceRule:
    """A streaming service Aster recognises, and whether it needs protected media."""

    label: str
    hosts: tuple[str, ...]
    path_tokens: tuple[str, ...] = ()
    protected: bool = True


# Only the routing hint lives here. Aster makes no claim that any of these
# services accepts a capsule runtime; each one decides that for itself.
SERVICE_RULES: tuple[ServiceRule, ...] = (
    ServiceRule("Prime Video", ("primevideo.com",)),
    ServiceRule("Prime Video", ("amazon.com",), path_tokens=("/gp/video", "/video")),
    ServiceRule("Netflix", ("netflix.com",)),
    ServiceRule("Disney+", ("disneyplus.com",)),
    ServiceRule("Max", ("max.com", "hbomax.com")),
    ServiceRule("Hulu", ("hulu.com",)),
    ServiceRule("Peacock", ("peacocktv.com",)),
    ServiceRule("Paramount+", ("paramountplus.com",)),
    ServiceRule("YouTube TV", ("tv.youtube.com",)),
    ServiceRule("Apple TV+", ("tv.apple.com",)),
    ServiceRule("Spotify", ("open.spotify.com",)),
    ServiceRule("Boosteroid", ("boosteroid.com",), protected=False),
    ServiceRule("Xbox Cloud Gaming", ("xbox.com",), path_tokens=("/play",), protected=False),
    ServiceRule("GeForce NOW", ("geforcenow.com",), protected=False),
    ServiceRule("YouTube", ("youtube.com", "youtu.be"), protected=False),
    ServiceRule("Crunchyroll", ("crunchyroll.com",), protected=False),
)

_CHROMIUM_NATIVE: tuple[tuple[str, str], ...] = (
    ("google-chrome-stable", "Google Chrome"),
    ("google-chrome", "Google Chrome"),
    ("microsoft-edge-stable", "Microsoft Edge"),
    ("brave-browser", "Brave"),
    ("chromium", "Chromium"),
    ("chromium-browser", "Chromium"),
)

_FIREFOX_NATIVE: tuple[tuple[str, str], ...] = (("firefox", "Firefox"),)

# (app id, label, family, supports --app window mode)
_FLATPAK_APPS: tuple[tuple[str, str, str, bool], ...] = (
    ("com.google.Chrome", "Google Chrome", "chromium", True),
    ("org.chromium.Chromium", "Chromium", "chromium", True),
    ("com.brave.Browser", "Brave", "chromium", True),
    ("com.microsoft.Edge", "Microsoft Edge", "chromium", True),
    ("org.mozilla.firefox", "Firefox", "firefox", False),
)

SETUP_COMMAND = "bash experiments/webkit/tools/setup_chromium_drm_capsule.sh"


def matching_service(uri: str) -> ServiceRule | None:
    """Return the rule for a URL, matching the host suffix and any path token."""
    parsed = urlsplit(uri)
    host = (parsed.hostname or "").lower()
    path = parsed.path or "/"
    for rule in SERVICE_RULES:
        for candidate in rule.hosts:
            if host == candidate or host.endswith(f".{candidate}"):
                # Match whole path segments: a substring test also caught
                # /gp/video-games, which is a store page, not Prime Video.
                if not rule.path_tokens or any(path == token or path.startswith(f"{token}/")
                                               for token in rule.path_tokens):
                    return rule
    return None


def service_label(uri: str) -> str:
    rule = matching_service(uri)
    return rule.label if rule else (urlsplit(uri).hostname or "site").lower()


def needs_capsule(uri: str) -> bool:
    """True when the URL is a service Aster knows needs protected media."""
    rule = matching_service(uri)
    return bool(rule and rule.protected)


@dataclass(frozen=True)
class CapsuleRuntime:
    label: str
    argv: tuple[str, ...]
    family: str  # electron_ecs | chromium | firefox | custom | none
    source: str  # packaged | native | flatpak | override | missing
    flatpak_app_id: str = ""
    url_placeholder: bool = False

    @property
    def available(self) -> bool:
        return self.family != "none"


UNAVAILABLE = CapsuleRuntime(
    label="No capsule runtime installed",
    argv=(),
    family="none",
    source="missing",
)


@dataclass(frozen=True)
class CapsuleLaunch:
    ok: bool
    service: str
    runtime: str
    command: tuple[str, ...]
    profile_dir: str | None
    note: str

    def command_text(self) -> str:
        return " ".join(shlex.quote(part) for part in self.command)


def capsule_root() -> Path:
    """Where the bundled CastLabs/Electron capsule is installed, if it is."""
    override = os.environ.get("ASTER_CAPSULE_ROOT", "").strip()
    if override:
        return Path(override).expanduser()
    return Path(__file__).resolve().parent.parent / "packaging" / "electron-drm-capsule"


def _electron_binary(root: Path) -> Path | None:
    for relative in (
        Path("node_modules") / ".bin" / "electron",
        Path("node_modules") / "electron" / "dist" / "electron",
        Path("node_modules") / "electron" / "dist" / "Electron.app" / "Contents" / "MacOS" / "Electron",
    ):
        candidate = root / relative
        if candidate.exists():
            return candidate
    return None


class CapsuleManager:
    """Detect a capsule runtime and build the command that opens one service in it."""

    def __init__(self, command_override: str = "", runtime: CapsuleRuntime | None = None,
                 profile_root: Path | None = None):
        self.command_override = command_override.strip()
        self._forced = runtime
        self._profile_root = Path(profile_root) if profile_root else None
        self._cached: CapsuleRuntime | None = None

    def runtime(self) -> CapsuleRuntime:
        if self._forced is not None:
            return self._forced
        if self._cached is None:
            self._cached = self._detect()
        return self._cached

    def _detect(self) -> CapsuleRuntime:
        override = self.command_override or os.environ.get("ASTER_CAPSULE_COMMAND", "").strip()
        if override:
            return self._from_override(override)

        root = capsule_root()
        electron = _electron_binary(root)
        if electron and (root / "main.js").exists():
            return CapsuleRuntime(
                label="Aster DRM capsule (CastLabs Electron)",
                argv=(str(electron), str(root)),
                family="electron_ecs",
                source="packaged",
            )

        for command, label in _CHROMIUM_NATIVE:
            path = shutil.which(command)
            if path:
                return CapsuleRuntime(label=label, argv=(path,), family="chromium", source="native")

        for command, label in _FIREFOX_NATIVE:
            path = shutil.which(command)
            if path:
                return CapsuleRuntime(label=label, argv=(path,), family="firefox", source="native")

        if shutil.which("flatpak"):
            for app_id, label, family, _app_mode in _FLATPAK_APPS:
                try:
                    found = subprocess.run(["flatpak", "info", app_id], stdout=subprocess.DEVNULL,
                                           stderr=subprocess.DEVNULL, check=False, timeout=6)
                except (OSError, subprocess.SubprocessError):
                    continue
                if found.returncode == 0:
                    return CapsuleRuntime(label=f"{label} (Flatpak)", argv=("flatpak", "run", app_id),
                                          family=family, source="flatpak", flatpak_app_id=app_id)

        # No xdg-open fallback: Aster may itself be the default handler, and
        # sending the URL back to Aster would loop while claiming a capsule ran.
        return UNAVAILABLE

    @staticmethod
    def _from_override(override: str) -> CapsuleRuntime:
        parts = shlex.split(override)
        if not parts:
            return UNAVAILABLE
        name = os.path.basename(parts[0]).lower()
        if "electron" in name:
            family = "electron_ecs"
        elif any(token in name for token in ("chrome", "chromium", "edge", "brave")):
            family = "chromium"
        elif "firefox" in name:
            family = "firefox"
        else:
            family = "custom"
        return CapsuleRuntime(label=f"Custom capsule command ({parts[0]})", argv=tuple(parts),
                              family=family, source="override", url_placeholder="{url}" in override)

    @staticmethod
    def _slug(label: str) -> str:
        return re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-") or "service"

    def profile_base(self) -> Path:
        if self._profile_root is not None:
            return self._profile_root
        runtime = self.runtime()
        if runtime.source == "flatpak" and runtime.flatpak_app_id:
            # A Flatpak runtime cannot read Aster's own data directory.
            return Path.home() / ".var" / "app" / runtime.flatpak_app_id / "data" / "aster-capsules"
        return profile_paths()[0] / "capsules"

    def profile_dir(self, service: str) -> Path:
        directory = self.profile_base() / self._slug(service)
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        manifest = directory / "capsule.json"
        record: dict[str, object] = {}
        try:
            loaded = json.loads(manifest.read_text(encoding="utf-8"))
            record = loaded if isinstance(loaded, dict) else {}
        except (OSError, UnicodeError, json.JSONDecodeError):
            record = {}
        record.setdefault("created_at", int(time.time()))
        record.update({"service": service, "runtime": self.runtime().label, "updated_at": int(time.time())})
        try:
            manifest.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        except OSError:
            pass  # A capsule still launches without its manifest.
        return directory

    def reset_profile(self, service: str) -> bool:
        """Delete one service's capsule profile, leaving every other service alone."""
        directory = self.profile_base() / self._slug(service)
        if not directory.is_dir():
            return False
        shutil.rmtree(directory)
        return True

    def build_command(self, uri: str) -> tuple[list[str], str | None, str]:
        if not is_web_uri(uri):
            raise ValueError("A capsule only opens an http or https address.")
        runtime = self.runtime()
        if not runtime.available:
            raise ValueError(f"No capsule runtime is installed. Set one up with: {SETUP_COMMAND}")
        service = service_label(uri)

        if runtime.url_placeholder:
            return [part.replace("{url}", uri) for part in runtime.argv], None, service

        if runtime.family == "electron_ecs":
            profile = str(self.profile_dir(service))
            command = [*runtime.argv, "--", f"--aster-url={uri}", f"--aster-service={service}",
                       f"--aster-profile={profile}"]
            return command, profile, service

        if runtime.family == "chromium":
            profile = str(self.profile_dir(service))
            command = [*runtime.argv, f"--user-data-dir={profile}",
                       "--autoplay-policy=no-user-gesture-required"]
            # A protected service gets its own chrome-less window; anything else
            # opens as an ordinary tab so the runtime still looks like a browser.
            command.extend([f"--app={uri}"] if needs_capsule(uri) else ["--new-window", uri])
            return command, profile, service

        if runtime.family == "firefox":
            profile = str(self.profile_dir(service))
            return [*runtime.argv, "--new-instance", "--profile", profile, "--new-window", uri], profile, service

        return [*runtime.argv, uri], None, service

    def launch(self, uri: str) -> CapsuleLaunch:
        command, profile, service = self.build_command(uri)
        runtime = self.runtime()
        try:
            subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
        except (OSError, ValueError) as error:
            return CapsuleLaunch(False, service, runtime.label, tuple(command), profile,
                                 f"The capsule did not start: {error}")
        return CapsuleLaunch(
            True, service, runtime.label, tuple(command), profile,
            f"Opened {service} in a separate capsule using {runtime.label}. Its profile and sign-in stay "
            "out of your Aster tabs. Aster supplies no CDM; whether the service plays is its decision.")

    def status_text(self) -> str:
        runtime = self.runtime()
        if not runtime.available:
            return ("DRM capsule: not installed.\n\n"
                    "Aster's WebKitGTK build has no encrypted-media support, so protected services "
                    "cannot play in an Aster tab. A capsule runs one service in a separate runtime that "
                    f"carries its own licensed CDM.\n\nSet one up with:\n  {SETUP_COMMAND}\n\n"
                    "An already-installed Chrome, Chromium, Brave, Edge or Firefox is detected and used "
                    "automatically if you prefer not to install the bundled capsule.")
        lines = [
            "DRM capsule: available",
            f"Runtime: {runtime.label}",
            f"Family: {runtime.family}",
            f"Source: {runtime.source}",
            f"Profiles: {self.profile_base()}",
            "",
            "Each service gets its own profile, so resetting one leaves the others signed in.",
            "Aster does not download, bundle or redistribute a CDM, and does not bypass DRM. "
            "A detected runtime is not proof that a service will play; only a real session is.",
        ]
        return "\n".join(lines)
