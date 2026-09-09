"""Reproducible unsigned test XPI. Mozilla signing is a separate release step."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build():
    source = ROOT / "extension"
    manifest = json.loads((source / "manifest.json").read_text())
    destination = ROOT / "dist" / f"aster-companion-{manifest['version']}-unsigned.xpi"
    destination.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(source).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
    checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(".xpi.sha256").write_text(f"{checksum}  {destination.name}\n")
    print(destination)
    return destination


if __name__ == "__main__":
    build()
