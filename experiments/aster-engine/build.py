#!/usr/bin/env python3
"""Build Aster's own Java engine with a JDK; Android additionally needs the official SDK.

No package manager, browser engine download, shell command interpolation or Gradle plugin.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from version import VERSION, build_number, display_version, native_version

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True, cwd=ROOT)


def compile_java(sources, output, classpath=None, release="8"):
    output.mkdir(parents=True, exist_ok=True)
    javac = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
    args = [*javac, "--release", release, "-encoding", "UTF-8", "-d", output]
    if classpath:
        args += ["-cp", classpath]
    run(*args, *sorted(sources))


def make_jar(folder, target, main=None):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT.parents[1] / "LICENSE", "META-INF/LICENSE")
        if main:
            cp='Class-Path: '+' '.join('lib/javafx-'+name+'.jar' for name in ['base','graphics','media','swing'])
            wrapped=cp[:70]+'\n '+cp[70:] if len(cp)>70 else cp
            archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nMain-Class: {main}\n{wrapped}\n\n")
        for path in sorted(folder.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(folder).as_posix())


def desktop(test=False, package=False):
    if package and (sys.platform not in {"linux", "win32"} or platform.machine().lower() not in {"x86_64", "amd64"}):
        raise SystemExit("Bundled previews currently target Linux/Windows x64. Omit --package to build the portable JAR.")
    from desktop_deps import script_host, javafx
    host = script_host()
    libraries = javafx()
    classes = BUILD / "desktop-classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java(list((ROOT / "core/src").rglob("*.java")) + list((ROOT / "desktop/src").rglob("*.java")), classes, libraries, release="11")
    for resource in (ROOT / 'desktop/resources').iterdir():
        if resource.is_file():
            shutil.copyfile(resource, classes / resource.name)
    jar = BUILD / "jar/aster-engine-preview.jar"
    make_jar(classes, jar, "io.aster.desktop.PreviewMain")
    if test:
        tests = BUILD / "tests"
        # The localhost HTTP fixture uses the JDK's test server (not shipped in the application).
        tests.mkdir(parents=True, exist_ok=True)
        javac = [shutil.which("javac")] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        run(*javac, "-encoding", "UTF-8", "-cp", str(classes)+os.pathsep+libraries, "-d", tests, *sorted((ROOT / "tests").rglob("*.java")))
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.tests.EngineTests")
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.desktop.DownloadTests")
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.desktop.ScriptTests")
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.desktop.NetworkTests")
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.desktop.CompatibilityTests")
        run("java", "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [classes, tests]))+os.pathsep+libraries, "io.aster.desktop.ProtectionTests")
        run("java", "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [classes, tests]))+os.pathsep+libraries, "io.aster.desktop.BoxLayoutTests", BUILD)
        run("java", "-cp", os.pathsep.join(map(str, [classes, tests])), "io.aster.desktop.MediaRelayTests")
        run("java", "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [classes, tests]))+os.pathsep+libraries, "io.aster.desktop.SiteDataTests")
        run("java", "-Djava.awt.headless=true", "-jar", jar, "--render-test", BUILD / "aster-page.png")
        run("java", "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [classes, tests]))+os.pathsep+libraries, "io.aster.desktop.DesktopTests", BUILD)
        run("java", "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [classes, tests]))+os.pathsep+libraries, "io.aster.desktop.ReadingTests", BUILD)
        run("java", "-Djava.awt.headless=true", "-cp", os.pathsep.join(map(str, [classes, tests]))+os.pathsep+libraries, "io.aster.desktop.WorkspaceTests", BUILD)
        run("java", "-Djava.awt.headless=true", "-jar", jar, "--benchmark", BUILD / "BENCHMARK.json")
    if package:
        image = BUILD / "native/AsterEnginePreview"
        if image.exists():
            shutil.rmtree(image)
        run("jpackage", "--type", "app-image", "--name", "AsterEnginePreview", "--app-version", native_version(),
            "--icon", ROOT / ('packaging/aster.ico' if sys.platform == 'win32' else 'desktop/resources/aster_logo.png'),
            "--vendor", "Aster Browser", "--input", jar.parent, "--main-jar", jar.name,
            "--add-modules", "java.desktop,java.prefs,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,jdk.unsupported.desktop,java.xml,java.logging", "--dest", image.parent)
        shutil.copyfile(ROOT.parents[1] / "LICENSE", image / "LICENSE")
        shutil.copyfile(ROOT / "README.md", image / "README.md")
        shutil.copyfile(BUILD / "VERSION.json", image / "VERSION.json")
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
    manifest = ET.parse(ROOT / "android/AndroidManifest.xml")
    manifest.getroot().set("{http://schemas.android.com/apk/res/android}versionCode", str(build_number()))
    manifest.getroot().set("{http://schemas.android.com/apk/res/android}versionName", display_version())
    generated_manifest = out / "AndroidManifest.xml"
    manifest.write(generated_manifest, encoding="utf-8", xml_declaration=True)
    run(tools / ("aapt2" + exe), "link", "-o", unsigned, "-I", platform, "--manifest", generated_manifest)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT.parents[1] / "LICENSE", "assets/LICENSE")
        for path in sorted(dex.glob("*.dex")):
            archive.write(path, path.name)
    aligned = out / "aligned.apk"
    run(tools / ("zipalign" + exe), "-f", "-p", "4", unsigned, aligned)
    apk = out / "aster-engine-preview.apk"
    from signing import sign_apk
    sign_apk(tools / "lib/apksigner.jar", aligned, apk, out / "SIGNING.json")
    print(f"Android application: {apk}")


def hashes():
    files = list(BUILD.glob("*.zip")) + list(BUILD.glob("*.tar.gz")) + list((BUILD / "android").glob("aster-engine-preview.apk"))
    (BUILD / "SHA256SUMS.txt").write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(BUILD).as_posix()}\n" for path in sorted(files)), encoding="utf-8")


def metadata():
    (BUILD / "VERSION.json").write_text(json.dumps({"version": display_version(), "native_version": native_version(),
        "version_code": build_number(), "base_version": VERSION, "commit": os.environ.get("ASTER_BUILD_COMMIT", "local")}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=["desktop", "android"])
    parser.add_argument("--test", action="store_true", help="Run engine, real HTTP and Java2D tests (desktop)")
    parser.add_argument("--package", action="store_true", help="Bundle a native launcher and Java runtime (desktop)")
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    metadata()
    if args.target == "desktop":
        desktop(args.test, args.package)
    else:
        android()
    hashes()
