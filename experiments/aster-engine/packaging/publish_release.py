#!/usr/bin/env python3
"""Publish only this trusted branch run's verified artifacts as preview releases."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

REPO = "xatusbetazx17/aster-browser"
BRANCH = "refs/heads/codex/aster-webkit-desktop"
CHANNEL = "codex-preview"


def gh(*args, data=None):
    return subprocess.check_output(["gh", *args], input=None if data is None else json.dumps(data), text=True)


def api(path, data=None):
    args = ["api", f"repos/{REPO}/{path}"]
    if data is not None:
        args += ["--input", "-"]
    return json.loads(gh(*args, data=data))


def finish_release(tag, body=None):
    args = ["release", "edit", tag, "--repo", REPO, "--draft=false", "--prerelease"]
    if body is not None:
        args += ["--notes-file", str(body)]
    try:
        gh(*args)
    except subprocess.CalledProcessError:
        # GitHub can apply the mutation and then return HTTP 500. Read its state
        # before considering another write; never rebuild or re-upload blindly.
        actual = api(f"releases/tags/{tag}")
        expected_body = None if body is None else body.read_text(encoding="utf-8")
        if actual["draft"] or not actual["prerelease"] or (expected_body is not None and actual["body"] != expected_body):
            raise
        print("GitHub reported an error after publishing; the requested release state was confirmed.")


def validate_identity(current, previous):
    if current["package"] != "io.aster.browser.enginepreview":
        raise ValueError("Unexpected Android application identity")
    if not re.fullmatch(r"[0-9a-f]{64}", current["certificate_sha256"]):
        raise ValueError("Invalid Android signing certificate")
    if previous and previous["persistent"]:
        if not current["persistent"] or current["certificate_sha256"] != previous["certificate_sha256"]:
            raise ValueError("Refusing to replace the Android release signing identity")
        if current["version_code"] <= previous["version_code"]:
            raise ValueError("Android update version code must increase")


def assemble(artifacts, output, commit, previous=None):
    windows = artifacts / "aster-engine-Windows"
    linux = artifacts / "aster-engine-Linux"
    flatpak = artifacts / "aster-engine-Linux-Flatpak"
    android = artifacts / "aster-engine-Android"
    metadata = json.loads((windows / "VERSION.json").read_text())
    for folder in (windows, linux, flatpak, android):
        if json.loads((folder / "VERSION.json").read_text()) != metadata or metadata["commit"] != commit:
            raise ValueError("Release artifacts do not all describe the requested source revision")
    for folder in (windows, linux):
        status = (folder / "BUILD-STATUS.txt").read_text()
        if "Core, browsing and reading checks: passed" not in status or "Native window: success" not in status:
            raise ValueError("A desktop package did not pass browsing and native window checks")
        if folder == linux and ("Video decoding: success" not in status or "HLS and page controls: success" not in status):
            raise ValueError("The Linux media checks must pass before publication")
    for folder in (windows, linux):
        benchmark = json.loads((folder / "BENCHMARK.json").read_text())
        if benchmark.get("schema") != 1 or benchmark.get("commit") != commit or len(benchmark.get("results", [])) != 6 or not all(r.get("identical_geometry") for r in benchmark["results"]):
            raise ValueError("Missing same-source renderer measurements")
    for marker in (windows / "WINDOWS-SETUP-STATUS.txt", flatpak / "FLATPAK-UPDATE-STATUS.txt", android / "ANDROID-TEST-STATUS.txt"):
        if not marker.is_file() or not marker.read_text().strip():
            raise ValueError("Missing successful package installation/update check: " + marker.name)
    signing = json.loads((android / "android/SIGNING.json").read_text())
    if signing["version_code"] != metadata["version_code"] or signing["version"] != metadata["version"]:
        raise ValueError("Android version does not match the release")
    validate_identity(signing, previous)
    output.mkdir(parents=True, exist_ok=True)
    apk_name = "aster-android-8-plus.apk" if signing["persistent"] else "aster-android-8-plus-test.apk"
    files = {
        "aster-windows-x64-setup.exe": windows / "aster-windows-x64-setup.exe",
        "aster-windows-x64-portable.zip": windows / "aster-engine-windows-x64.zip",
        "aster-linux-x64.flatpak": flatpak / "aster-linux-x64.flatpak",
        "aster-linux-x64.tar.gz": linux / "aster-engine-linux-x64.tar.gz",
        apk_name: android / "android/aster-engine-preview.apk",
        "ANDROID-SIGNING.json": android / "android/SIGNING.json",
        "WINDOWS-BUILD-STATUS.txt": windows / "BUILD-STATUS.txt",
        "LINUX-BUILD-STATUS.txt": linux / "BUILD-STATUS.txt",
        "WINDOWS-SETUP-STATUS.txt": windows / "WINDOWS-SETUP-STATUS.txt",
        "FLATPAK-UPDATE-STATUS.txt": flatpak / "FLATPAK-UPDATE-STATUS.txt",
        "ANDROID-TEST-STATUS.txt": android / "ANDROID-TEST-STATUS.txt",
        "VERSION.json": windows / "VERSION.json",
        "WINDOWS-BENCHMARK.json": windows / "BENCHMARK.json",
        "LINUX-BENCHMARK.json": linux / "BENCHMARK.json",
    }
    for name, source in files.items():
        if not source.is_file() or source.stat().st_size == 0:
            raise ValueError("Missing release package: " + name)
        shutil.copyfile(source, output / name)
    checksums = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in files}
    (output / "SHA256SUMS.txt").write_text("".join(f"{digest}  {name}\n" for name, digest in sorted(checksums.items())), encoding="utf-8")
    return metadata, signing, apk_name


def notes(metadata, signing, apk_name, tag):
    base = f"https://github.com/{REPO}/releases/download/{tag}"
    android_update = ("Open the APK and choose **Update**. Future release APKs keep this private signing identity and increase the version code."
        if signing["persistent"] else
        "**Test installation only: Android updates are not enabled yet.** The owner must configure the private signing key once. "
        "This test APK cannot promise an in-place update from another CI run. Do not uninstall an older app containing data you need.")
    return f"""Aster {metadata['version']} — experimental original engine, built from `{metadata['commit']}`.

This update adds desktop cross-origin HTTP APIs with CORS enforcement, Request/Blob/File/FormData and URLSearchParams, asynchronous XMLHttpRequest, early response headers, upload/download progress and bounded gzip/deflate decoding. Desktop and Android also preserve the current path for query-only links. The existing website sessions, desktop storage, dark workspace, reading/notes and tab parking remain included. Android JavaScript/video is still unfinished. WINDOWS-BENCHMARK.json and LINUX-BENCHMARK.json contain the scoped renderer results; they do not compare Aster with other browsers. No GitHub account is needed to download the release assets. They have no 14-day artifact expiry.

| Device | Download | Install or update |
| --- | --- | --- |
| Windows 10/11 x64 | [Download Windows setup]({base}/aster-windows-x64-setup.exe) | Close Aster, run setup, then open Aster Preview from Start. Running a newer setup replaces the existing application and preserves its profile. |
| Linux x64 with Flatpak | [Download Linux Flatpak]({base}/aster-linux-x64.flatpak) | Open with your software installer, or run `flatpak install --user --or-update ./aster-linux-x64.flatpak`. |
| Android 8+ | [Download Android APK]({base}/{apk_name}) | {android_update} |

Windows includes Java; no separate Java installation is required. The Windows package is unsigned and Windows may display an unknown-publisher warning. Windows video/HLS remains unreliable; read WINDOWS-BUILD-STATUS.txt. Basic session fixtures now pass; full web compatibility, advanced account flows, Netflix, Prime Video and cloud gaming remain unfinished.

Linux uses the same `io.aster.browser.EnginePreview` application and `preview` branch for replacements. Its Flatpak profile stays separate from the native tarball profile. Flatpak is intended to cover many desktop distributions, including immutable systems with Flatpak support; every Linux distribution/CPU/device has not been verified. Desktop packages are x64 only.

Older Qt/Chromium kits, WebKit builds and differently signed Android test APKs are separate installations. Their data is not automatically converted. Close Aster before updating; downloads already saved outside the application remain in place. Updates are initiated by downloading a newer package; there is no background auto-updater.

See [site protection, CSS layout and the remaining engine roadmap](https://github.com/{REPO}/blob/{metadata['commit']}/experiments/aster-engine/RELEASE_0.6.md), and [installation and update help](https://github.com/{REPO}/blob/codex/aster-webkit-desktop/DOWNLOADS.md). The portable Windows ZIP and Linux tarball are optional alternatives under Assets. SHA256SUMS.txt covers every delivered package and its verification reports.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    args = parser.parse_args()
    if os.environ.get("GITHUB_REPOSITORY") != REPO or os.environ.get("GITHUB_REF") != BRANCH or os.environ.get("GITHUB_EVENT_NAME") not in {"push", "workflow_dispatch"}:
        raise SystemExit("Publishing is restricted to an explicit push or manual run on the Codex branch")
    commit = os.environ["GITHUB_SHA"]
    branch_head = api("git/ref/heads/codex/aster-webkit-desktop")["object"]["sha"]
    if commit != branch_head:
        raise SystemExit("A newer branch commit exists; this superseded run will not publish")
    releases = api("releases?per_page=100")
    channel = next((r for r in releases if r["tag_name"] == CHANNEL), None)
    if channel and channel.get("immutable"):
        raise SystemExit("The rolling preview release is immutable. Use versioned downloads until the channel is configured separately.")
    previous = None
    with tempfile.TemporaryDirectory(prefix="aster-publish-") as temp:
        root = Path(temp)
        if channel:
            asset = next((a for a in channel["assets"] if a["name"] == "ANDROID-SIGNING.json"), None)
            if asset is None:
                raise ValueError("Existing preview channel is missing its Android signing record")
            gh("release", "download", CHANNEL, "--repo", REPO, "--pattern", "ANDROID-SIGNING.json", "--dir", str(root))
            previous = json.loads((root / "ANDROID-SIGNING.json").read_text())
        packages = root / "packages"
        metadata, signing, apk_name = assemble(args.artifacts, packages, commit, previous)
        tag = "v" + metadata["version"]
        # Versioned releases are never overwritten. Start a fresh manual workflow
        # run for a new build number after an interrupted publication.
        if any(r["tag_name"] == tag and not r["draft"] for r in releases):
            raise SystemExit("This version was already published; increase the build number for another release")
        body = root / "notes.md"
        body.write_text(notes(metadata, signing, apk_name, tag), encoding="utf-8")
        assets = [str(p) for p in sorted(packages.iterdir())]
        if not any(r["tag_name"] == tag for r in releases):
            gh("release", "create", tag, "--repo", REPO, "--target", commit, "--title", f"Aster {metadata['version']}",
               "--notes-file", str(body), "--prerelease", "--draft")
        gh("release", "upload", tag, *assets, "--repo", REPO, "--clobber")
        finish_release(tag)
        # Keep one clearly named prerelease channel for stable download links.
        body.write_text(notes(metadata, signing, apk_name, CHANNEL) + f"\n[This exact version](https://github.com/{REPO}/releases/tag/{tag}).\n", encoding="utf-8")
        if channel is None:
            gh("release", "create", CHANNEL, "--repo", REPO, "--target", commit, "--title", "Latest Codex preview",
               "--notes-file", str(body), "--prerelease", "--draft")
        gh("release", "upload", CHANNEL, *assets, "--repo", REPO, "--clobber")
        if channel:
            gh("api", f"repos/{REPO}/git/refs/tags/{CHANNEL}", "--method", "PATCH", "--input", "-", data={"sha": commit, "force": True})
            # Remove the explicitly named old test APK only after a persistent APK
            # is uploaded. All historical versioned releases remain available.
            if signing["persistent"] and any(a["name"] == "aster-android-8-plus-test.apk" for a in channel["assets"]):
                gh("release", "delete-asset", CHANNEL, "aster-android-8-plus-test.apk", "--repo", REPO, "--yes")
        finish_release(CHANNEL, body)
        final = api(f"releases/tags/{CHANNEL}")
        sizes = {a["name"]: a["size"] for a in final["assets"]}
        if any(sizes.get(p.name) != p.stat().st_size for p in packages.iterdir()):
            raise ValueError("The published release asset list does not match the packages")
        digests = {a["name"]: a.get("digest") for a in final["assets"]}
        if any(digests.get(p.name) != "sha256:" + hashlib.sha256(p.read_bytes()).hexdigest() for p in packages.iterdir()):
            raise ValueError("GitHub's uploaded asset digests do not match the release packages")
        print(final["html_url"])


if __name__ == "__main__":
    main()
