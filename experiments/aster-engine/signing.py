"""Private, persistent Android release signing; disposable keys are testing-only."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from version import build_number, display_version


def parse_config(raw):
    config = json.loads(raw)
    if not isinstance(config, dict) or set(config) != {"keystore", "store_password", "key_password", "alias"}:
        raise ValueError("Android signing secret has an invalid shape")
    if not all(isinstance(v, str) and v for v in config.values()):
        raise ValueError("Android signing fields must be nonempty strings")
    data = base64.b64decode(config["keystore"], validate=True)
    if not 512 <= len(data) <= 24000:
        raise ValueError("Android signing keystore has an invalid size")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", config["alias"]):
        raise ValueError("Android signing alias has an invalid format")
    return config, data


def sign_apk(signer, source, target, report):
    raw = os.environ.get("ASTER_ANDROID_SIGNING_JSON", "")
    # Never write private key bytes into the checkout, caches or build artifacts.
    with tempfile.TemporaryDirectory(prefix="aster-private-signing-") as private:
        env = os.environ.copy()
        if raw:
            config, data = parse_config(raw)
            key = Path(private) / "signing.keystore"
            key.write_bytes(data)
            key.chmod(0o600)
            alias = config["alias"]
            env["ASTER_SIGN_STORE_PASSWORD"] = config["store_password"]
            env["ASTER_SIGN_KEY_PASSWORD"] = config["key_password"]
        else:
            if os.environ.get("ASTER_REQUIRE_RELEASE_SIGNING") == "1":
                raise ValueError("Configure ASTER_ANDROID_SIGNING_JSON before building an updatable release APK")
            key = Path(os.environ.get("ASTER_PREVIEW_KEYSTORE", str(Path.home() / ".android/aster-engine-preview.keystore")))
            key.parent.mkdir(parents=True, exist_ok=True)
            alias = "aster-preview"
            env["ASTER_SIGN_STORE_PASSWORD"] = env["ASTER_SIGN_KEY_PASSWORD"] = "android"
            if not key.exists():
                subprocess.run(["keytool", "-genkeypair", "-noprompt", "-keystore", str(key), "-storepass", "android",
                    "-keypass", "android", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                    "-dname", "CN=Aster Development Preview"], check=True)
        subprocess.run(["java", "-jar", str(signer), "sign", "--ks", str(key), "--ks-key-alias", alias,
            "--ks-pass", "env:ASTER_SIGN_STORE_PASSWORD", "--key-pass", "env:ASTER_SIGN_KEY_PASSWORD",
            "--out", str(target), str(source)], env=env, check=True)
    verification = subprocess.check_output(["java", "-jar", str(signer), "verify", "--verbose", "--print-certs", str(target)], text=True)
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", verification)
    if not match:
        raise ValueError("Could not verify the APK signing identity")
    report.write_text(json.dumps({"persistent": bool(raw), "certificate_sha256": match.group(1).lower(),
        "package": "io.aster.browser.enginepreview", "version_code": build_number(), "version": display_version()}, indent=2) + "\n", encoding="utf-8")
    print("APK signature verified. Persistent release identity: " + str(bool(raw)))
