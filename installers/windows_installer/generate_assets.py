"""Write the Windows installer's icon files from the shared brand definition.

build_exe.py runs this before PyInstaller, so the setup executable, its window
and the browser it installs all carry the same mark. The mark itself is defined
once in assets/brand/aster_brand.py; assets/brand/build_brand_assets.py
refreshes every copy of it in the repository, this one included.
"""
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "assets" / "brand"))

import aster_brand as brand  # noqa: E402 - resolved via the path above


def generate_aster_assets(output_dir: str) -> None:
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)

    ico_path = directory / "aster.ico"
    brand.write_ico(ico_path)
    print(f"Generated {ico_path}")

    # The installer window paints its own dark header, so the logo goes on it
    # without a tile of its own.
    png_path = directory / "aster_logo.png"
    brand.render(512, "mark").save(png_path, format="PNG")
    print(f"Generated {png_path}")


if __name__ == "__main__":
    generate_aster_assets(str(Path(__file__).resolve().parent))
