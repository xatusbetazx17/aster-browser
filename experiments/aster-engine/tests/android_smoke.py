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
import base64
import hashlib

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


def wait_text(text, timeout=90, recover_system_ui=False, exact=False):
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
        # Prefer an exact label. For controls, require it: "Submit" must not
        # select the form destination ending in "/submitted".
        nodes = list(root.iter("node"))
        needle = text.casefold()
        for node in nodes:
            if needle in (node.attrib.get("text", "").casefold(), node.attrib.get("content-desc", "").casefold()):
                return node
        if not exact:
            for node in nodes:
                if needle in node.attrib.get("text", "").casefold() or needle in node.attrib.get("content-desc", "").casefold():
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
    image_requests = 0
    cross_origin_errors = []
    css = b'@media screen and (max-width:600px){section{background:#b0ebdf !important}}'
    def do_GET(self):
        if self.path in ('/image.png', '/cdn.css'):
            if self.headers.get('Origin') != 'http://127.0.0.1:8765' or self.headers.get('Cookie'):
                Fixture.cross_origin_errors.append('CDN origin missing or first-party cookie leaked')
            image = self.path == '/image.png'
            if image:
                Fixture.image_requests += 1
            body = image_fixture() if image else Fixture.css
            self.send_response(200)
            self.send_header('Content-Type', 'image/png' if image else 'text/css')
            self.send_header('Access-Control-Allow-Origin', 'http://127.0.0.1:8765')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        html = "<title>Second fixture</title><h1>Second fixture</h1><p>Link navigation worked.</p>" if self.path == "/second" else "<title>First fixture</title><a href='/second'>Open second fixture</a><p>Network page rendered by Aster.</p>"
        if self.path != '/second':
            integrity = base64.b64encode(hashlib.sha384(Fixture.css).digest()).decode()
            html += f"<link rel='stylesheet' href='http://127.0.0.1:8766/cdn.css' crossorigin integrity='sha384-{integrity}'>"
            html += "<img src='http://127.0.0.1:8766/image.png' crossorigin width='80' height='40' alt='Aster image fixture'><form action='/submitted' method='post'><input name='q' value='android8'></form>"
            html += "<section style='background:#ff0000;padding:12px;border:2px solid #267861;margin:8px 0'><p style='margin:0'>Native CSS box fixture.</p><div style='display:flex;gap:10px;margin-top:6px'><span style='flex:1;min-width:0;height:28px;background:#e6b54a'>Flex one</span><span style='flex:1;min-width:0;height:28px;background:#a6c7f5'>Flex two</span></div></section>"
        if "login=android8" in self.headers.get("Cookie", ""):
            html += "<p>Session cookie restored.</p>"
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
        self.send_header('Set-Cookie', 'login=android8; Path=/; HttpOnly; Max-Age=3600')
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
    cdn = ThreadingHTTPServer(("127.0.0.1", 8766), Fixture)
    threading.Thread(target=cdn.serve_forever, daemon=True).start()
    try:
        adb("wait-for-device")
        adb("reverse", "tcp:8765", "tcp:8765")
        adb("reverse", "tcp:8766", "tcp:8766")
        baseline = Path(os.environ.get("ASTER_ANDROID_BASELINE_APK", str(OUT / "aster-engine-preview.apk")))
        adb("install", "--no-incremental", "--no-streaming", "-r", str(baseline))
        print("APK installed.", flush=True)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/io.aster.android.MainActivity")
        wait_text("Your space to explore.", recover_system_ui=True)
        print("Native Aster workspace home opened.", flush=True)
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
            box_count = sum(pixels[i:i + 3] == b'\xb0\xeb\xdf' for i in range(0, len(pixels), 4))
            if count > 100 and box_count > 100:
                break
            if time.monotonic() > deadline:
                raise AssertionError('Android did not paint the fetched image and CSS box pixels')
            time.sleep(0.5)
        (OUT / 'aster-android-images.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        if Fixture.cross_origin_errors:
            raise AssertionError(Fixture.cross_origin_errors)
        flex_bounds = []
        for color in (b'\xe6\xb5\x4a', b'\xa6\xc7\xf5'):
            locations = [i // 4 for i in range(0, len(pixels), 4) if pixels[i:i + 3] == color]
            if len(locations) < 100:
                raise AssertionError('Android did not paint both flex item backgrounds')
            flex_bounds.append((min(i % width for i in locations), min(i // width for i in locations),
                                max(i % width for i in locations), max(i // width for i in locations)))
        a, b = flex_bounds
        if abs(a[1] - b[1]) > 1 or abs(a[3] - b[3]) > 1 or a[2] >= b[0] or abs((a[2] - a[0]) - (b[2] - b[0])) > 2:
            raise AssertionError(f'Android flex growth/gap/row placement is wrong: {flex_bounds}')
        print('CORS CDN image, verified responsive stylesheet and two equal flex items with a gap rendered by native Android Canvas.', flush=True)
        menu('Site protection')
        tap(wait_text('Custom blocked hostnames', exact=True))
        adb('shell', 'input', 'text', '127.0.0.1')
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        tap(wait_text('Save hostname rules', exact=True))
        wait_text('Protection settings saved.')
        tap(wait_text('Done', exact=True))
        before_images = Fixture.image_requests
        menu('Reload')
        wait_text('Network page rendered by Aster.')
        menu('Site protection')
        wait_text('requests blocked for this origin this session.')
        editor = wait_text('Custom blocked hostnames', exact=True)
        if editor.attrib.get('focused') == 'true' or editor.attrib.get('text') != '127.0.0.1':
            raise AssertionError('Opening protection stole focus for editing or changed saved rules')
        activity = ET.tostring(screen(), encoding='unicode')
        if '2 requests blocked for this origin this session.' not in activity or Fixture.image_requests != before_images:
            raise AssertionError('Android blocking did not prevent the actual CDN stylesheet and image requests')
        tap(wait_text('Allow requests on this site', exact=True))
        wait_text('Protection settings saved.')
        if wait_text('Allow requests on this site', exact=True).attrib.get('checked') != 'true':
            raise AssertionError('Native protection site switch did not become checked')
        tap(wait_text('Reload', exact=True))
        wait_text('Network page rendered by Aster.')
        deadline = time.monotonic() + 10
        while Fixture.image_requests <= before_images:
            if time.monotonic() > deadline:
                raise AssertionError('Android site exception did not restore image requests')
            time.sleep(0.1)
        print('Android native protection blocked a real image request; its site exception restored it.', flush=True)
        node = wait_text('Network page rendered by Aster.')
        bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
        density = float(adb("shell", "wm", "density").strip().split()[-1]) / 160
        # The fixture's first link is at the engine's 24px content inset.
        adb("shell", "input", "tap", str(bounds[0] + int(50 * density)), str(bounds[1] + int(33 * density)))
        wait_text("Link navigation worked.")
        print("Link tap navigated successfully.", flush=True)
        menu("Back")
        wait_text("Network page rendered by Aster.")
        tap(wait_text("Show tabs", exact=True))
        tap(wait_text("New tab", exact=True))
        wait_text("Your space to explore.")
        tap(wait_text("Show tabs", exact=True))
        tap(wait_text("◌ First fixture", exact=True))
        wait_text("Network page rendered by Aster.")
        forward = wait_text("Forward", exact=True)
        if forward.attrib.get("enabled") != "true":
            raise AssertionError("Switching Android tabs discarded Forward history")
        tap(forward)
        wait_text("Link navigation worked.")
        tap(wait_text("Back", exact=True))
        wait_text("Network page rendered by Aster.")
        print("Visible navigation controls preserved each tab's Back/Forward history.", flush=True)
        menu('Read page / Find')
        wait_text('Save notes', exact=True)
        wait_text('Network page rendered by Aster.')
        (OUT / 'aster-android-reader.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        tap(wait_text('Close', exact=True))
        print('Native reader text and controls opened.', flush=True)
        menu('Page forms')
        tap(wait_text('Submit', exact=True))
        wait_text('Native POST reached the server.')
        if "could not be saved" in ET.tostring(screen(), encoding="unicode"):
            raise AssertionError("Android could not persist the new website session")
        print('Native form POST submitted successfully.', flush=True)
        menu('Back')
        wait_text('Network page rendered by Aster.')
        wait_text("Session cookie restored.")
        print("Native POST established a real website session.", flush=True)
        menu("Bookmark this page")
        wait_text("Bookmark saved on this device.")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("install", "--no-incremental", "--no-streaming", "-r", str(OUT / "aster-engine-preview.apk"))
        if os.environ.get("ASTER_ANDROID_BASELINE_APK"):
            package_info = adb("shell", "dumpsys", "package", PACKAGE)
            if not re.search(r"versionCode=" + re.escape(os.environ["ASTER_BUILD_NUMBER"]) + r"\b", package_info):
                raise AssertionError('Android did not install the higher version code')
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/io.aster.android.MainActivity")
        wait_text("Your space to explore.")
        menu("Open bookmark")
        tap(wait_text("First fixture"))
        wait_text("Network page rendered by Aster.")
        wait_text("Session cookie restored.")
        print("Persistent website cookie survived process restart and APK replacement.", flush=True)
        menu('Site protection')
        exception = wait_text('Allow requests on this site', exact=True)
        if exception.attrib.get('checked') != 'true' or wait_text('Custom blocked hostnames', exact=True).attrib.get('text') != '127.0.0.1':
            raise AssertionError('Android lost protection rules or exception after APK replacement')
        tap(wait_text('Done', exact=True))
        print('Protection rules and site exception survived APK replacement.', flush=True)
        menu("Streaming support")
        wait_text("Device Widevine:")
        report = screen()
        OUT.joinpath("drm-device-report.xml").write_text(ET.tostring(report, encoding="unicode"), encoding="utf-8")
        OUT.joinpath("aster-android-drm.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        tap(wait_text("OK", exact=True))
        menu("Clear website data")
        tap(wait_text("Clear", exact=True))
        wait_text("Your space to explore.")
        menu("Open bookmark")
        tap(wait_text("First fixture"))
        wait_text("Network page rendered by Aster.")
        if "Session cookie restored." in ET.tostring(screen(), encoding="unicode"):
            raise AssertionError("Clear website data kept the login session")
        print("Clear website data signed out while preserving the bookmark.", flush=True)
        if Fixture.cross_origin_errors:
            raise AssertionError(Fixture.cross_origin_errors)
        print("Android passed: native Canvas, actual fetched image pixels, real HTTP/link/Back, native form POST, reader controls, bookmark-preserving APK replacement, MediaDrm query.")
    finally:
        server.shutdown()
        cdn.shutdown()
        logs = adb("logcat", "-d", "-s", "AndroidRuntime:E", "ActivityManager:E", "AsterSiteData:W")
        OUT.joinpath("android-runtime.log").write_text(logs, encoding="utf-8")
        if "FATAL EXCEPTION" in logs:
            print(logs[-8000:], flush=True)


if __name__ == "__main__":
    main()
