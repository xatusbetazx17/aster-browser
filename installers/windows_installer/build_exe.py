"""Build the standalone single-file Aster-Browser-Setup.exe installer."""
import os
import subprocess
import sys

def build():
    installer_dir = os.path.dirname(os.path.abspath(__file__))
    root_dir = os.path.dirname(os.path.dirname(installer_dir))
    
    assets_script = os.path.join(installer_dir, "generate_assets.py")
    subprocess.run([sys.executable, assets_script], check=True)
    
    bundle_script = os.path.join(installer_dir, "bundle_app.py")
    subprocess.run([sys.executable, bundle_script], check=True)
    
    ico_path = os.path.join(installer_dir, "aster.ico")
    png_path = os.path.join(installer_dir, "aster_logo.png")
    bundle_path = os.path.join(installer_dir, "app_bundle.zip")
    script_path = os.path.join(installer_dir, "installer_gui.py")
    
    cmd = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--clean",
        "--noconfirm",
        "--onefile",
        "--windowed",
        "--name",
        "Aster-Browser-Setup",
        f"--icon={ico_path}",
        f"--add-data={bundle_path};.",
        f"--add-data={ico_path};.",
        f"--add-data={png_path};.",
        "--collect-all",
        "customtkinter",
        # aster_runtime sits next to installer_gui.py. PyInstaller normally
        # finds a sibling module anyway, but a miss here would only show up as
        # an exe that dies on its first import, so say where it is.
        f"--paths={installer_dir}",
        "--distpath",
        root_dir,
        "--workpath",
        os.path.join(installer_dir, "build"),
        "--specpath",
        installer_dir,
        script_path,
    ]
    
    print("Running PyInstaller command:")
    print(" ".join(cmd))
    result = subprocess.run(cmd)
    if result.returncode != 0:
        raise SystemExit(f"PyInstaller failed with code {result.returncode}")
        
    exe_path = os.path.join(root_dir, "Aster-Browser-Setup.exe")
    if os.path.exists(exe_path):
        print(f"\nSUCCESS: Built standalone installer at: {exe_path} ({os.path.getsize(exe_path):,} bytes)")
    else:
        raise SystemExit("Error: Output executable was not found.")

if __name__ == "__main__":
    build()
