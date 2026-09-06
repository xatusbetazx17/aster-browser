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
    return subprocess.check_output([*ADB, *args], timeout=45, text=not binary)


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/aster-test.xml")
    raw = adb("shell", "cat", "/sdcard/aster-test.xml")
    return ET.fromstring(raw)


def wait_text(text, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = screen()
        for node in root.iter("node"):
            if text in node.attrib.get("text", "") or text in node.attrib.get("content-desc", ""):
                return node
        time.sleep(1)
    raise AssertionError(f"Android UI did not show {text!r}")


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
        adb("install", "-r", str(OUT / "aster-engine-preview.apk"))
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/io.aster.android.MainActivity")
        wait_text("Your space to explore.")
        (OUT / "aster-android-home.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        tap(wait_text("Website address"))
        # The native address field selects its current contents on focus.
        adb("shell", "input", "text", "http://127.0.0.1:8765/first")
        adb("shell", "input", "keyevent", "66")
        node = wait_text("Network page rendered by Aster.")
        bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        density = float(adb("shell", "wm", "density").strip().split()[-1]) / 160
        # The fixture's first link is at the engine's 24px content inset.
        adb("shell", "input", "tap", str(bounds[0] + int(50 * density)), str(bounds[1] + int(33 * density)))
        wait_text("Link navigation worked.")
        menu("Back")
        wait_text("Network page rendered by Aster.")
        menu("Bookmark this page")
        wait_text("Bookmark saved on this device.")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("install", "-r", str(OUT / "aster-engine-preview.apk"))
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


if __name__ == "__main__":
    main()
