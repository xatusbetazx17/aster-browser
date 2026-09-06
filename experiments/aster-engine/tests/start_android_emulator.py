#!/usr/bin/env python3
"""Start the official Android emulator for CI; use software virtualization when KVM is unavailable."""
import os
from pathlib import Path
import subprocess
import time

sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
adb = str(sdk / "platform-tools/adb")
out = Path(__file__).resolve().parents[1] / "build/android"
out.mkdir(parents=True, exist_ok=True)
environment = os.environ.copy()
environment["ANDROID_USER_HOME"] = str(out / "emulator-user")
environment["ANDROID_AVD_HOME"] = str(out / "emulator-user/avd")
Path(environment["ANDROID_AVD_HOME"]).mkdir(parents=True, exist_ok=True)
api = os.environ.get("ASTER_EMULATOR_API", "26")
if api not in {"26", "35"}:
    raise SystemExit("ASTER_EMULATOR_API must be 26 or 35")
abi = "x86" if api == "26" else "x86_64"
image = f"system-images;android-{api};default;{abi}"
subprocess.run([str(sdk / "cmdline-tools/latest/bin/sdkmanager"), image], check=True, timeout=360)
subprocess.run([str(sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "--force", "--name", "aster-ci", "--package", image, "--device", "pixel_2"], input="no\n", text=True, check=True, timeout=60, env=environment)
config = Path(environment["ANDROID_AVD_HOME"]) / "aster-ci.avd/config.ini"
values = dict(line.split("=", 1) for line in config.read_text().splitlines() if "=" in line)
# A compact display makes the software-only emulator practical on hosted runners.
values.update({"hw.lcd.width": "480", "hw.lcd.height": "800", "hw.lcd.density": "160", "showDeviceFrame": "no"})
config.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
command = [str(sdk / "emulator/emulator"), "-avd", "aster-ci", "-port", "5554", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-no-metrics", "-gpu", "swiftshader_indirect", "-memory", "2048", "-cores", "2"]
if not os.access("/dev/kvm", os.R_OK | os.W_OK):
    command += ["-accel", "off"]
with (out / "emulator.log").open("w") as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True, env=environment)
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
            for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                subprocess.run([adb, "-s", "emulator-5554", "shell", "settings", "put", "global", setting, "0"], check=True, timeout=45)
            print(f"Android API {api} ({abi}) emulator booted.")
            break
        time.sleep(3)
    else:
        process.terminate()
        print((out / "emulator.log").read_text(errors="replace")[-12000:])
        raise SystemExit("Android emulator boot timed out; inspect emulator.log")
