"""Navigation and bookmark storage without a GUI dependency."""

from __future__ import annotations

import ipaddress
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import quote_plus, urlsplit

SEARCH_URL = "https://duckduckgo.com/?q="
BLOCKED_SCHEMES = {"javascript", "data", "file", "vbscript", "blob", "about"}


def is_web_uri(uri: str) -> bool:
    """Permit absolute HTTP(S) URLs, without embedded credentials or controls."""
    if not isinstance(uri, str) or any(ord(c) < 32 or ord(c) == 127 for c in uri):
        return False
    if any(c.isspace() for c in uri) or "\\" in uri:
        return False
    try:
        parsed = urlsplit(uri)
        return bool(
            parsed.scheme.lower() in {"http", "https"}
            and parsed.hostname
            and parsed.username is None
            and parsed.password is None
            and (parsed.port is None or 0 < parsed.port <= 65535)
        )
    except ValueError:
        return False


def navigation_target(value: str) -> str:
    """Turn an address-bar entry into a URL or an explicitly submitted search."""
    value = value.strip()
    if not value:
        return "about:blank"
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError("The address contains a control character.")
    if value == "about:blank":
        return value
    scheme = re.match(r"^([a-zA-Z][a-zA-Z0-9+.-]*):", value)
    if scheme and scheme[1].lower() in BLOCKED_SCHEMES:
        raise ValueError("Use an http or https address, or enter a search.")
    if value.lower().startswith(("http:", "https:")):
        if not is_web_uri(value):
            raise ValueError("That web address is invalid. Check the host and port.")
        return value
    if "://" in value:
        raise ValueError("This version supports http and https addresses.")
    if not any(c.isspace() for c in value):
        try:
            parsed = urlsplit("//" + value)
            host = parsed.hostname or ""
            port = parsed.port
            local = host == "localhost" or host.endswith(".localhost")
            try:
                ipaddress.ip_address(host)
                address = True
            except ValueError:
                address = False
            if local or address or "." in host or port is not None:
                prefix = "http://" if local or address or port is not None else "https://"
                candidate = prefix + value
                if not is_web_uri(candidate):
                    raise ValueError("That web address is invalid.")
                return candidate
        except ValueError as error:
            raise ValueError("That web address is invalid. Check the host and port.") from error
    return SEARCH_URL + quote_plus(value)


def profile_paths() -> tuple[Path, Path]:
    """Use a separate profile; never reuse Aster's Qt profile or Chrome's data."""
    override = os.environ.get("ASTER_WEBKIT_PROFILE_DIR")
    if override:
        data = Path(override).expanduser().resolve()
        return data, data / "cache"
    data_home = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share")
    cache_home = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache")
    if not data_home.is_absolute():
        data_home = Path.home() / ".local/share"
    if not cache_home.is_absolute():
        cache_home = Path.home() / ".cache"
    return data_home / "aster-webkit", cache_home / "aster-webkit"


class BookmarkStore:
    """Atomic local bookmarks; invalid input is reported, never silently replaced."""

    def __init__(self, path: Path):
        self.path = path
        self.items: list[dict[str, str]] = []
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise ValueError(f"Could not read bookmarks: {path}") from error
        if not isinstance(payload, list) or any(
            not isinstance(item, dict)
            or not isinstance(item.get("title"), str)
            or not is_web_uri(item.get("uri"))
            for item in payload
        ):
            raise ValueError(f"Invalid bookmarks file: {path}")
        seen: set[str] = set()
        for item in payload:
            if item["uri"] not in seen:
                self.items.append({"title": item["title"], "uri": item["uri"]})
                seen.add(item["uri"])

    def contains(self, uri: str) -> bool:
        return any(item["uri"] == uri for item in self.items)

    def toggle(self, uri: str, title: str) -> bool:
        if not is_web_uri(uri):
            raise ValueError("Only web pages can be bookmarked.")
        added = not self.contains(uri)
        updated = [item for item in self.items if item["uri"] != uri]
        if added:
            updated.append({"uri": uri, "title": title or uri})
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        descriptor, temporary = tempfile.mkstemp(prefix=".bookmarks-", dir=self.path.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                json.dump(updated, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        self.items = updated
        return added
