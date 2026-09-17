"""Read, replace and add files inside the kit both installers are built from.

The Qt application's own source lives in Aster-Browser-Windows-Kit-v16.zip
rather than in the working tree, so changing the browser means rewriting entries
in that archive. The Windows installer bundles the kit directly and
install-linux.sh embeds a copy of it, so after replacing anything here rebuild
the Linux script with installers/build_linux_installer.py - otherwise its
payload still carries the old files and installers/tests/test_installer_sync.py
fails.
"""
from __future__ import annotations

import io
from pathlib import Path
from typing import Mapping
import zipfile

ROOT = Path(__file__).resolve().parents[1]
KIT = ROOT / "Aster-Browser-Windows-Kit-v16.zip"
PREFIX = "aster-browser-windows-kit/"


def read(name: str) -> bytes:
    with zipfile.ZipFile(KIT) as kit:
        return kit.read(PREFIX + name)


def read_text(name: str) -> str:
    return read(name).decode("utf-8")


def names() -> list[str]:
    with zipfile.ZipFile(KIT) as kit:
        return [item.filename[len(PREFIX):] for item in kit.infolist()
                if item.filename.startswith(PREFIX) and not item.is_dir()]


def replace(updates: Mapping[str, bytes | str]) -> bool:
    """Rewrite the kit with these files replaced. True when anything changed.

    Every other entry is copied across untouched, keeping its timestamp and
    compression, so an unrelated file is never rewritten by a logo change.
    """
    payload = {PREFIX + name: (data.encode("utf-8") if isinstance(data, str) else data)
               for name, data in updates.items()}

    with zipfile.ZipFile(KIT) as source:
        missing = sorted(set(payload) - set(source.namelist()))
        if missing:
            raise KeyError(f"{KIT.name} does not contain {[n[len(PREFIX):] for n in missing]}")
        if all(source.read(name) == data for name, data in payload.items()):
            return False
        items = [(item, payload.get(item.filename) or source.read(item.filename))
                 for item in source.infolist()]

    _rewrite(items)
    return True


def add(files: Mapping[str, bytes | str]) -> bool:
    """Write these files into the kit, new names included. True when it changed.

    replace() refuses a name the kit does not carry, so a typo cannot quietly
    drop a change. Shipping a new module is the one case that guard cannot
    serve. A new entry takes its metadata from one the kit already holds, and
    writing the same bytes twice is a no-op, so a build step can be re-run.
    """
    payload = {PREFIX + name: (data.encode("utf-8") if isinstance(data, str) else data)
               for name, data in files.items()}

    with zipfile.ZipFile(KIT) as source:
        held = {item.filename for item in source.infolist()}
        fresh = sorted(set(payload) - held)
        if not fresh and all(source.read(name) == data for name, data in payload.items()):
            return False
        template = next(item for item in source.infolist() if not item.is_dir())
        items = [(item, payload[item.filename] if item.filename in payload else source.read(item.filename))
                 for item in source.infolist()]

    for name in fresh:
        entry = zipfile.ZipInfo(name, date_time=template.date_time)
        entry.compress_type = template.compress_type
        entry.external_attr = template.external_attr
        entry.create_system = template.create_system
        items.append((entry, payload[name]))

    _rewrite(items)
    return True


def _rewrite(items) -> None:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as target:
        for item, data in items:
            info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            info.compress_type = item.compress_type
            info.external_attr = item.external_attr
            info.create_system = item.create_system
            target.writestr(info, data)
    KIT.write_bytes(buffer.getvalue())
