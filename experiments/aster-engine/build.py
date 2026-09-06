#!/usr/bin/env python3
"""Build Aster's own Java engine with a JDK; Android additionally needs the official SDK.

No package manager, browser engine download, shell command interpolation or Gradle plugin.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def compile_java(sources, output, classpath=None):
    output.mkdir(parents=True, exist_ok=True)
    javac = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
    args = [*javac, "--release", "8", "-encoding", "UTF-8", "-d", output]
    if classpath:
        args += ["-cp", classpath]
    run(*args, *sorted(sources))


def make_jar(folder, target, main=None):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT.parents[1] / "LICENSE", "META-INF/LICENSE")
        if main:
            archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nMain-Class: {main}\n\n")
        for path in sorted(folder.rglob("*.class")):
            archive.write(path, path.relative_to(folder).as_posix())


def desktop(test=False, package=False):
    classes = BUILD / "desktop-classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java(list((ROOT / "core/src").rglob("*.java")) + list((ROOT / "desktop/src").rglob("*.java")), classes)
    jar = BUILD / "jar/aster-engine-preview.jar"
    make_jar(classes, jar, "io.aster.desktop.PreviewMain")
    if test:
        tests = BUILD / "tests"
        # The localhost HTTP fixture uses the JDK's test server (not shipped in the application).
        tests.mkdir(parents=True, exist_ok=True)
        javac = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        run(*javac, "-encoding", "UTF-8", "-cp", classes, "-d", tests, *sorted((ROOT / "tests").rglob("*.java")))
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.tests.EngineTests")
        run("java", "-Djava.awt.headless=true", "-jar", jar, "--render-test", BUILD / "aster-page.png")
    if package:
        image = BUILD / "native/AsterEnginePreview"
        if image.exists():
            shutil.rmtree(image)
        run("jpackage", "--type", "app-image", "--name", "AsterEnginePreview", "--app-version", "0.1.0",
            "--vendor", "Aster Browser", "--input", jar.parent, "--main-jar", jar.name,
            "--add-modules", "java.desktop,java.prefs,jdk.crypto.ec", "--dest", image.parent)
        shutil.copyfile(ROOT.parents[1] / "LICENSE", image / "LICENSE")
        shutil.copyfile(ROOT / "README.md", image / "README.md")
        if sys.platform == "win32":
            shutil.make_archive(str(BUILD / "aster-engine-windows-x64"), "zip", image.parent, image.name)
        else:
            shutil.make_archive(str(BUILD / "aster-engine-linux-x64"), "gztar", image.parent, image.name)
    print(f"Desktop application: {jar}")


def android():
    sdk_value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_value:
        raise SystemExit("Android SDK missing: set ANDROID_SDK_ROOT to your official SDK directory.")
    sdk = Path(sdk_value)
    tools = sdk / "build-tools/35.0.0"
    platform = sdk / "platforms/android-35/android.jar"
    if not platform.is_file() or not tools.is_dir():
        raise SystemExit('Install official SDK packages "platforms;android-35" and "build-tools;35.0.0" first.')
    out = BUILD / "android"
    out.mkdir(parents=True, exist_ok=True)
    classes = out / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java(list((ROOT / "core/src").rglob("*.java")) + list((ROOT / "android/src").rglob("*.java")), classes, platform)
    jar = out / "classes.jar"
    make_jar(classes, jar)
    exe = ".exe" if os.name == "nt" else ""
    dex = out / "dex"
    dex.mkdir(exist_ok=True)
    # Invoking D8's Java entry point also works without .bat execution on Windows.
    run("java", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26", "--lib", platform, "--output", dex, jar)
    unsigned = out / "unsigned.apk"
    run(tools / ("aapt2" + exe), "link", "-o", unsigned, "-I", platform, "--manifest", ROOT / "android/AndroidManifest.xml")
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT.parents[1] / "LICENSE", "assets/LICENSE")
        for path in sorted(dex.glob("*.dex")):
            archive.write(path, path.name)
    aligned = out / "aligned.apk"
    run(tools / ("zipalign" + exe), "-f", "-p", "4", unsigned, aligned)
    # Development signing only. The key stays outside source control. Keep it for upgrades.
    key = Path(os.environ.get("ASTER_PREVIEW_KEYSTORE", str(Path.home() / ".android/aster-engine-preview.keystore")))
    key.parent.mkdir(parents=True, exist_ok=True)
    if not key.exists():
        run("keytool", "-genkeypair", "-noprompt", "-keystore", key, "-storepass", "android", "-keypass", "android", "-alias", "aster-preview",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Aster Development Preview")
    apk = out / "aster-engine-preview.apk"
    run("java", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-key-alias", "aster-preview",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, aligned)
    run("java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk)
    print(f"Android application: {apk}")


def hashes():
    files = list(BUILD.glob("*.zip")) + list(BUILD.glob("*.tar.gz")) + list((BUILD / "android").glob("aster-engine-preview.apk"))
    (BUILD / "SHA256SUMS.txt").write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in sorted(files)), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=["desktop", "android"])
    parser.add_argument("--test", action="store_true", help="Run engine, real HTTP and Java2D tests (desktop)")
    parser.add_argument("--package", action="store_true", help="Bundle a native launcher and Java runtime (desktop)")
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    if args.target == "desktop":
        desktop(args.test, args.package)
    else:
        android()
    hashes()
