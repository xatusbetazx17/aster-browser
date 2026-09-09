#!/usr/bin/env python3
"""Owner-only, one-time setup. Run on your computer with a JDK and GitHub CLI."""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import subprocess

REPO = "xatusbetazx17/aster-browser"
SECRET = "ASTER_ANDROID_SIGNING_JSON"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", type=Path, required=True, help="Existing private key, or a new backup path outside the repository")
    parser.add_argument("--create", action="store_true", help="Generate a new signing identity for the first release only")
    parser.add_argument("--alias", default="aster-preview")
    args = parser.parse_args()
    existing = json.loads(subprocess.check_output(["gh", "secret", "list", "--repo", REPO, "--json", "name"], text=True))
    if any(s["name"] == SECRET for s in existing):
        raise SystemExit("Signing is already configured. Refusing to replace the identity used by installed applications.")
    key = args.keystore.expanduser().resolve()
    repo_root = Path(__file__).resolve().parents[3]
    if key == repo_root or repo_root in key.parents:
        raise SystemExit("Keep the private keystore outside the repository.")
    if args.create and key.exists():
        raise SystemExit("The keystore already exists. Omit --create to use it.")
    password = getpass.getpass("Keystore password (keep this with your private backup): ")
    if len(password) < 12:
        raise SystemExit("Use a password of at least 12 characters.")
    env = os.environ.copy()
    env["ASTER_LOCAL_SIGN_PASSWORD"] = password
    if args.create:
        if password != getpass.getpass("Confirm password: "):
            raise SystemExit("Passwords do not match.")
        key.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["keytool", "-genkeypair", "-noprompt", "-keystore", str(key), "-storepass:env", "ASTER_LOCAL_SIGN_PASSWORD",
            "-keypass:env", "ASTER_LOCAL_SIGN_PASSWORD", "-alias", args.alias, "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "10950", "-dname", "CN=Aster Preview"], env=env, check=True)
        key.chmod(0o600)
        key_password = password
    else:
        key_password = getpass.getpass("Key password (Enter if same): ") or password
    subprocess.run(["keytool", "-list", "-keystore", str(key), "-storepass:env", "ASTER_LOCAL_SIGN_PASSWORD", "-alias", args.alias],
        env=env, check=True, stdout=subprocess.DEVNULL)
    payload = json.dumps({"keystore": base64.b64encode(key.read_bytes()).decode("ascii"), "store_password": password,
        "key_password": key_password, "alias": args.alias})
    subprocess.run(["gh", "secret", "set", SECRET, "--repo", REPO], input=payload, text=True, check=True)
    print("Private release signing configured. Keep the keystore and password in your secure backup.")
    print("Run the Aster original engine preview workflow on codex/aster-webkit-desktop to publish the Android update channel.")


if __name__ == "__main__":
    main()
