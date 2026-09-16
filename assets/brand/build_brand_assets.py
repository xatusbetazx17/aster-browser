"""Write every copy of the Aster logo from assets/brand/aster_brand.py.

Run this after changing the mark:

    python assets/brand/build_brand_assets.py

It writes the three vector variants next to this file, then the copies the
browser, the extension and the installers load at runtime. The Linux installer
carries the application inside Aster-Browser-Windows-Kit-v16.zip, so the icons
in the kit are replaced too and install-linux.sh is rebuilt from it; without
that last step the installed copy would still show the old icon.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(Path(__file__).resolve().parent), str(ROOT / "installers")]

import aster_brand as brand  # noqa: E402 - resolved via the paths above
import kit as kit_archive  # noqa: E402

BRAND_DIR = ROOT / "assets" / "brand"

# Where each variant belongs. The mark (no tile) is for surfaces that are
# already dark; the icon (black tile) is for launchers, tabs and title bars.
SVG_COPIES = {
    "experiments/firefox/extension/icon.svg": "icon",
    "experiments/webkit/aster_webkit/aster-icon.svg": "icon",
}
KIT_SVG_COPIES = {
    "aster_browser/assets/icons/aster_logo.svg": "mark",
    "packaging/linux/aster.svg": "icon",
}
# Pages that carry the mark inline, between <!--aster-logo--> markers. The new
# tab page is handed to WebKit as a string with about:blank as its base, so a
# linked file would never load.
INLINE_COPIES = {
    "experiments/webkit/aster_webkit/home.html": ("icon", "mark"),
}
MARKERS = ("<!--aster-logo-->", "<!--/aster-logo-->")


def write_brand_dir() -> list[Path]:
    written = []
    for variant in brand.VARIANTS:
        target = BRAND_DIR / f"aster-{variant}.svg"
        target.write_text(brand.svg(variant), encoding="utf-8")
        written.append(target)
    png = BRAND_DIR / "aster-logo.png"
    brand.render(512, "logo").save(png, format="PNG")
    written.append(png)
    mark_png = BRAND_DIR / "aster-mark.png"
    brand.render(512, "mark").save(mark_png, format="PNG")
    written.append(mark_png)
    ico = BRAND_DIR / "aster.ico"
    brand.write_ico(ico)
    written.append(ico)
    return written


def write_repo_copies() -> list[Path]:
    written = []
    for relative, variant in SVG_COPIES.items():
        target = ROOT / relative
        target.write_text(brand.svg(variant), encoding="utf-8")
        written.append(target)

    for relative, (variant, class_name) in INLINE_COPIES.items():
        target = ROOT / relative
        text = target.read_text(encoding="utf-8")
        start, end = MARKERS
        before, _, rest = text.partition(start)
        _, _, after = rest.partition(end)
        if not rest or not after:
            raise SystemExit(f"{relative} has no {start}...{end} markers")
        inline = brand.svg(variant, class_name=class_name, compact=True)
        target.write_text(before + start + inline + end + after, encoding="utf-8")
        written.append(target)

    installer_dir = ROOT / "installers" / "windows_installer"
    shutil.copyfile(BRAND_DIR / "aster.ico", installer_dir / "aster.ico")
    written.append(installer_dir / "aster.ico")
    # The installer window paints its own dark header, so the logo goes on it
    # without a tile of its own.
    brand.render(512, "mark").save(installer_dir / "aster_logo.png", format="PNG")
    written.append(installer_dir / "aster_logo.png")
    return written


def update_kit() -> bool:
    """Replace the icons inside the kit the installers ship. True if it changed."""
    replacements = {name: brand.svg(variant) for name, variant in KIT_SVG_COPIES.items()}
    replacements["aster_browser/assets/icons/aster.ico"] = (BRAND_DIR / "aster.ico").read_bytes()
    return kit_archive.replace(replacements)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-installer", action="store_true",
                        help="do not rebuild install-linux.sh from the kit")
    args = parser.parse_args()

    for path in write_brand_dir() + write_repo_copies():
        print(f"wrote {path.relative_to(ROOT)}")

    if update_kit():
        print(f"updated {kit_archive.KIT.name}")
        if not args.skip_installer:
            subprocess.run([sys.executable, str(ROOT / "installers" / "build_linux_installer.py")], check=True)
    else:
        print(f"{kit_archive.KIT.name} already carries this logo")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
