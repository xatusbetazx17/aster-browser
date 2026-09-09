"""Package the Aster Browser v16 application into a clean app_bundle.zip for distribution."""
import os
import zipfile

def create_app_bundle(source_zip: str, output_zip: str, assets_dir: str):
    print(f"Creating application bundle from {source_zip}...")
    
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
                count += 1
        
        # Also ensure aster.ico and aster_logo.png are in packaging/windows/
        ico_file = os.path.join(assets_dir, "aster.ico")
        if os.path.exists(ico_file):
            with open(ico_file, "rb") as f:
                out_z.writestr("packaging/windows/aster.ico", f.read())
        
        png_file = os.path.join(assets_dir, "aster_logo.png")
        if os.path.exists(png_file):
            with open(png_file, "rb") as f:
                out_z.writestr("packaging/windows/aster_logo.png", f.read())
                
        print(f"Successfully packaged {count} files into {output_zip} ({os.path.getsize(output_zip):,} bytes)")

if __name__ == "__main__":
    base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    installer_dir = os.path.join(base_dir, "installers", "windows_installer")
    source_kit = os.path.join(base_dir, "Aster-Browser-Windows-Kit-v16.zip")
    output_bundle = os.path.join(installer_dir, "app_bundle.zip")
    create_app_bundle(source_kit, output_bundle, installer_dir)
