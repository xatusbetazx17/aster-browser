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
import struct
import zlib

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


def wait_text(text, timeout=90, recover_system_ui=False):
    deadline = time.monotonic() + timeout
    observed = []
    system_ui_waits = 0
    while time.monotonic() < deadline:
        root = screen()
        observed = [(n.attrib.get("text", "")[:300], n.attrib.get("content-desc", "")[:300]) for n in root.iter("node") if n.attrib.get("text") or n.attrib.get("content-desc")]
        # API 26 software emulation can show a boot-time System UI ANR. Allow
        # two explicit Wait actions at startup only; never dismiss an app ANR.
        if recover_system_ui and system_ui_waits < 2 and any(t == "System UI isn't responding" for t, _ in observed):
            wait = next((n for n in root.iter("node") if n.attrib.get("text") == "Wait"), None)
            if wait is not None:
                print("Waiting for the emulator's System UI to recover at startup.", flush=True)
                tap(wait)
                system_ui_waits += 1
                continue
        for node in root.iter("node"):
            # Android themes may capitalize native button labels for display.
            if text.casefold() in node.attrib.get("text", "").casefold() or text.casefold() in node.attrib.get("content-desc", "").casefold():
                return node
        time.sleep(1)
    raise AssertionError(f"Android UI did not show {text!r}; observed: {observed!r}")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def menu(label):
    tap(wait_text("Aster menu"))
    # Native popup menus scroll on the 480x800 API 26 fixture display.
    for _ in range(6):
        root = screen()
        node = next((n for n in root.iter("node") if n.attrib.get("text") == label), None)
        if node is not None:
            tap(node)
            return
        scroller = next((n for n in root.iter("node") if n.attrib.get("scrollable") == "true"), None)
        if scroller is None:
            break
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroller.attrib["bounds"]))
        x = str((x1 + x2) // 2)
        adb("shell", "input", "swipe", x, str(y2 - 30), x, str(y1 + 30), "400")
    raise AssertionError(f"Android menu item was not reachable: {label!r}")


def image_fixture():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 8, 8, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress((b'\x00' + b'\x11\x53\x97' * 8) * 8)) + chunk(b'IEND', b'')


class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/image.png':
            body = image_fixture()
            self.send_response(200)
            self.send_header('Content-Type', 'image/png')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        html = "<title>Second fixture</title><h1>Second fixture</h1><p>Link navigation worked.</p>" if self.path == "/second" else "<title>First fixture</title><a href='/second'>Open second fixture</a><p>Network page rendered by Aster.</p>"
        if self.path != '/second':
            html += "<img src='/image.png' width='80' height='40' alt='Aster image fixture'><form action='/submitted' method='post'><input name='q' value='android8'></form>"
        body = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        data = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path != '/submitted' or data != b'q=android8':
            raise AssertionError(f'Incorrect native form submission: {self.path} {data!r}')
        body = b'<h1>Android form submitted</h1><p>Native POST reached the server.</p>'
        self.send_response(200)
        self.send_header('Content-Type', 'text/html')
        self.send_header('Content-Length', str(len(body)))
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
        wait_text("Your space to explore.", recover_system_ui=True)
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
        deadline = time.monotonic() + 15
        while True:
            raw = adb('exec-out', 'screencap', binary=True)
            width, height, pixel_format = struct.unpack('<III', raw[:12])
            if pixel_format != 1:
                raise AssertionError(f'Expected emulator RGBA8888, got {pixel_format}')
            pixels = raw[-width * height * 4:]
            count = sum(pixels[i:i + 3] == b'\x11\x53\x97' for i in range(0, len(pixels), 4))
            if count > 100:
                break
            if time.monotonic() > deadline:
                raise AssertionError('Android did not paint the fetched image pixels')
            time.sleep(0.5)
        (OUT / 'aster-android-images.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        print('Fetched image pixels were rendered by the native Android Canvas.', flush=True)
        bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        density = float(adb("shell", "wm", "density").strip().split()[-1]) / 160
        # The fixture's first link is at the engine's 24px content inset.
        adb("shell", "input", "tap", str(bounds[0] + int(50 * density)), str(bounds[1] + int(33 * density)))
        wait_text("Link navigation worked.")
        print("Link tap navigated successfully.", flush=True)
        menu("Back")
        wait_text("Network page rendered by Aster.")
        menu('Read page / Find')
        wait_text('Save notes')
        wait_text('Network page rendered by Aster.')
        (OUT / 'aster-android-reader.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        tap(wait_text('Close'))
        print('Native reader text and controls opened.', flush=True)
        menu('Page forms')
        tap(wait_text('Submit'))
        wait_text('Native POST reached the server.')
        print('Native form POST submitted successfully.', flush=True)
        menu('Back')
        wait_text('Network page rendered by Aster.')
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
        print("Android passed: native Canvas, actual fetched image pixels, real HTTP/link/Back, native form POST, reader controls, bookmark-preserving APK replacement, MediaDrm query.")
    finally:
        server.shutdown()
        logs = adb("logcat", "-d", "-s", "AndroidRuntime:E", "ActivityManager:E")
        OUT.joinpath("android-runtime.log").write_text(logs, encoding="utf-8")
        if "FATAL EXCEPTION" in logs:
            print(logs[-8000:], flush=True)


if __name__ == "__main__":
    main()
