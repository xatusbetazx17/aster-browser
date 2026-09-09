#!/usr/bin/env python3
"""Read-only check of the actual public preview downloads, without download auth."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import re
import subprocess
from urllib.request import urlopen

REPO = "xatusbetazx17/aster-browser"
CHANNEL = "codex-preview"
BASE = f"https://github.com/{REPO}/releases/download/{CHANNEL}"


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{REPO}/{path}"], text=True))


def public_bytes(name, limit=1048576):
    # No token or Authorization header is sent with public asset requests.
    with urlopen(BASE + "/" + name, timeout=60) as response:
        value = response.read(limit + 1)
    if len(value) > limit:
        raise ValueError("Unexpectedly large release metadata")
    return value


def verify_asset(asset):
    digest = hashlib.sha256()
    total = 0
    with urlopen(BASE + "/" + asset["name"], timeout=60) as response:
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > asset["size"]:
                raise ValueError("Downloaded asset exceeded its declared size")
            digest.update(chunk)
    if total != asset["size"] or "sha256:" + digest.hexdigest() != asset["digest"]:
        raise ValueError("Public download did not match its published digest: " + asset["name"])
    return asset["name"]


def main():
    channel = api(f"releases/tags/{CHANNEL}")
    if channel["draft"] or not channel["prerelease"]:
        raise ValueError("Preview channel must be a published prerelease")
    metadata = json.loads(public_bytes("VERSION.json"))
    tag = "v" + metadata["version"]
    versioned = api("releases/tags/" + tag)
    if versioned["draft"] or versioned["target_commitish"] != metadata["commit"]:
        raise ValueError("Versioned release does not identify the tested commit")
    reference = api("git/ref/tags/" + CHANNEL)
    if reference["object"]["sha"] != metadata["commit"]:
        raise ValueError("Preview tag does not identify the tested commit")
    assets = {a["name"]: a for a in channel["assets"]}
    archived = {a["name"]: a for a in versioned["assets"]}
    if set(assets) != set(archived) or any(assets[n]["digest"] != archived[n]["digest"] for n in assets):
        raise ValueError("Channel downloads differ from the versioned release")
    signing = json.loads(public_bytes("ANDROID-SIGNING.json"))
    apk = "aster-android-8-plus.apk" if signing["persistent"] else "aster-android-8-plus-test.apk"
    if not {"aster-windows-x64-setup.exe", "aster-linux-x64.flatpak", apk} <= set(assets):
        raise ValueError("A platform download is missing")
    expected = {}
    for line in public_bytes("SHA256SUMS.txt").decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in expected:
            raise ValueError("Invalid release checksum manifest")
        expected[match[2]] = "sha256:" + match[1]
    if set(expected) != set(assets) - {"SHA256SUMS.txt"} or any(assets[n]["digest"] != v for n, v in expected.items()):
        raise ValueError("Checksum manifest does not match the published packages")
    with ThreadPoolExecutor(max_workers=3) as workers:
        for name in workers.map(verify_asset, assets.values()):
            print("Public download and SHA-256 verified: " + name)
    print("Source commit: " + metadata["commit"])
    print("Persistent Android updates enabled: " + str(signing["persistent"]))


if __name__ == "__main__":
    main()
