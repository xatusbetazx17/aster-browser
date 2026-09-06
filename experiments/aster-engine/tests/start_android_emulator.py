#!/usr/bin/env python3
"""Start the official Android emulator for CI; use software virtualization when KVM is unavailable."""
import os
from pathlib import Path
import subprocess
import time

sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
adb = str(sdk / "platform-tools/adb")
image = "system-images;android-35;default;x86_64"
subprocess.run([str(sdk / "cmdline-tools/latest/bin/sdkmanager"), image], check=True, timeout=360)
subprocess.run([str(sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "--force", "--name", "aster-ci", "--package", image, "--device", "pixel_2"], input="no\n", text=True, check=True, timeout=60)
out = Path(__file__).resolve().parents[1] / "build/android"
out.mkdir(parents=True, exist_ok=True)
command = [str(sdk / "emulator/emulator"), "-avd", "aster-ci", "-port", "5554", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader_indirect", "-memory", "2048", "-cores", "2"]
if not os.access("/dev/kvm", os.R_OK | os.W_OK):
    command += ["-accel", "off"]
with (out / "emulator.log").open("w") as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + 420
    while time.monotonic() < deadline:
        if process.poll() is not None:
            print((out / "emulator.log").read_text(errors="replace")[-12000:])
            raise SystemExit("Android emulator exited; inspect emulator.log")
        try:
            result = subprocess.run([adb, "-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"], capture_output=True, text=True, timeout=15)
        except subprocess.TimeoutExpired:
            continue
        if result.stdout.strip() == "1":
            subprocess.run([adb, "-s", "emulator-5554", "shell", "input", "keyevent", "82"], check=True)
            print("Android API 35 emulator booted.")
            break
        time.sleep(3)
    else:
        process.terminate()
        print((out / "emulator.log").read_text(errors="replace")[-12000:])
        raise SystemExit("Android emulator boot timed out; inspect emulator.log")
