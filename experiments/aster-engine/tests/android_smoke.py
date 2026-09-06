#!/usr/bin/env python3
"""Real Android device/emulator checks: Canvas, HTTP links, history, bookmark update, DRM report."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/android"
PACKAGE = "io.aster.browser.enginepreview"
sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
ADB = [str(sdk / "platform-tools/adb")]
if os.environ.get("ANDROID_SERIAL"):
    ADB += ["-s", os.environ["ANDROID_SERIAL"]]


def adb(*args, binary=False):
    return subprocess.check_output([*ADB, *args], timeout=180 if args and args[0] == "install" else 60, text=not binary)


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/aster-test.xml")
    raw = adb("shell", "cat", "/sdcard/aster-test.xml")
    return ET.fromstring(raw)


def wait_text(text, timeout=90):
    deadline = time.monotonic() + timeout
    observed = []
    while time.monotonic() < deadline:
        root = screen()
        observed = [(n.attrib.get("text", "")[:300], n.attrib.get("content-desc", "")[:300]) for n in root.iter("node") if n.attrib.get("text") or n.attrib.get("content-desc")]
        for node in root.iter("node"):
            if text in node.attrib.get("text", "") or text in node.attrib.get("content-desc", ""):
                return node
        time.sleep(1)
    raise AssertionError(f"Android UI did not show {text!r}; observed: {observed!r}")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def menu(label):
    tap(wait_text("Aster menu"))
    tap(wait_text(label))


class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        html = "<title>Second fixture</title><h1>Second fixture</h1><p>Link navigation worked.</p>" if self.path == "/second" else "<title>First fixture</title><a href='/second'>Open second fixture</a><p>Network page rendered by Aster.</p>"
        body = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 8765), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        adb("wait-for-device")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("install", "--no-incremental", "--no-streaming", "-r", str(OUT / "aster-engine-preview.apk"))
        print("APK installed.", flush=True)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/io.aster.android.MainActivity")
        wait_text("Your space to explore.")
        print("Native Canvas home page opened.", flush=True)
        (OUT / "aster-android-home.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        field = wait_text("Website address")
        tap(field)
        # Explicitly replace the address, regardless of keyboard selection behavior.
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * (len(field.attrib.get("text", "")) + 1)))
        adb("shell", "input", "text", "http://127.0.0.1:8765/first")
        entered = wait_text("Website address").attrib.get("text", "")
        if entered != "http://127.0.0.1:8765/first":
            raise AssertionError(f"Address entry did not replace previous URL: {entered!r}")
        adb("shell", "input", "keyevent", "66")
        node = wait_text("Network page rendered by Aster.")
        print("HTTP fixture loaded.", flush=True)
        bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        density = float(adb("shell", "wm", "density").strip().split()[-1]) / 160
        # The fixture's first link is at the engine's 24px content inset.
        adb("shell", "input", "tap", str(bounds[0] + int(50 * density)), str(bounds[1] + int(33 * density)))
        wait_text("Link navigation worked.")
        print("Link tap navigated successfully.", flush=True)
        menu("Back")
        wait_text("Network page rendered by Aster.")
        menu("Bookmark this page")
        wait_text("Bookmark saved on this device.")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("install", "--no-incremental", "--no-streaming", "-r", str(OUT / "aster-engine-preview.apk"))
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/io.aster.android.MainActivity")
        wait_text("Your space to explore.")
        menu("Open bookmark")
        tap(wait_text("First fixture"))
        wait_text("Network page rendered by Aster.")
        menu("Streaming support")
        wait_text("Device Widevine:")
        report = screen()
        OUT.joinpath("drm-device-report.xml").write_text(ET.tostring(report, encoding="unicode"), encoding="utf-8")
        OUT.joinpath("aster-android-drm.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        print("Android passed: native Canvas, real HTTP, tapped link, Back, bookmark-preserving APK replacement, MediaDrm capability query.")
    finally:
        server.shutdown()
        logs = adb("logcat", "-d", "-s", "AndroidRuntime:E")
        OUT.joinpath("android-runtime.log").write_text(logs, encoding="utf-8")
        if "FATAL EXCEPTION" in logs:
            print(logs[-8000:], flush=True)


if __name__ == "__main__":
    main()
