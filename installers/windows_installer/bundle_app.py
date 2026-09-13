"""Package the Aster Browser v16 application into a clean app_bundle.zip for distribution."""
import hashlib
import json
import os
import time
import zipfile

# Written into the bundle, and again into the install directory by the installer.
# It answers two questions a stale exe otherwise hides: which kit is inside this
# build, and has anyone edited the files since they were installed.
MANIFEST_NAME = "bundle_info.json"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def create_app_bundle(source_zip: str, output_zip: str, assets_dir: str):
    print(f"Creating application bundle from {source_zip}...")

    with open(source_zip, "rb") as f:
        kit_bytes = f.read()
    kit_digest = _sha256(kit_bytes)

    files: dict[str, str] = {}

    with zipfile.ZipFile(source_zip, "r") as src_z, zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as out_z:
        prefix = "aster-browser-windows-kit/"
        count = 0
        for item in src_z.infolist():
            if item.filename.startswith(prefix):
                rel_name = item.filename[len(prefix):]
                if not rel_name:
                    continue
                # Skip legacy update scripts in the app bundle to keep it lean
                if rel_name.startswith("tools/legacy-update-scripts/"):
                    continue
                data = src_z.read(item.filename)
                out_z.writestr(rel_name, data)
                if not rel_name.endswith("/"):
                    files[rel_name] = _sha256(data)
                count += 1

        # Also ensure aster.ico and aster_logo.png are in packaging/windows/
        ico_file = os.path.join(assets_dir, "aster.ico")
        if os.path.exists(ico_file):
            with open(ico_file, "rb") as f:
                data = f.read()
            out_z.writestr("packaging/windows/aster.ico", data)
            files["packaging/windows/aster.ico"] = _sha256(data)

        png_file = os.path.join(assets_dir, "aster_logo.png")
        if os.path.exists(png_file):
            with open(png_file, "rb") as f:
                data = f.read()
            out_z.writestr("packaging/windows/aster_logo.png", data)
            files["packaging/windows/aster_logo.png"] = _sha256(data)

        manifest = {
            "kit_name": os.path.basename(source_zip),
            "kit_sha256": kit_digest,
            "kit_size": len(kit_bytes),
            "built_at": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + "Z",
            "file_count": len(files),
            "files": dict(sorted(files.items())),
        }
        out_z.writestr(MANIFEST_NAME, json.dumps(manifest, indent=2, sort_keys=False))

        print(f"Successfully packaged {count} files into {output_zip} ({os.path.getsize(output_zip):,} bytes)")
        print(f"Bundle carries kit {kit_digest[:16]} ({len(files)} hashed files)")


if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    installer_dir = os.path.join(base_dir, "installers", "windows_installer")
    source_kit = os.path.join(base_dir, "Aster-Browser-Windows-Kit-v16.zip")
    output_bundle = os.path.join(installer_dir, "app_bundle.zip")
    create_app_bundle(source_kit, output_bundle, installer_dir)
