"""Real GTK/WebKit smoke test. Requires a display (or Xvfb), never a mock engine."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import subprocess
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class Fixture(BaseHTTPRequestHandler):
    cookies = []
    video = b""

    def do_GET(self):
        if self.path == "/sample.webm":
            self.send_response(200)
            self.send_header("Content-Type", "video/webm")
            self.send_header("Content-Length", str(len(self.video)))
            self.end_headers()
            self.wfile.write(self.video)
            return
        self.cookies.append(self.headers.get("Cookie", ""))
        second = self.path.startswith("/second")
        title = "Second" if second else "Script ran"
        html = f"<!doctype html><title>Waiting</title><p>Aster smoke test</p><script>document.title='{title}';</script>"
        data = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Set-Cookie", "aster_smoke=one; Path=/; SameSite=Lax")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_):
        pass


def main():
    with tempfile.TemporaryDirectory(prefix="aster-webkit-smoke-") as profile:
        os.environ["ASTER_WEBKIT_PROFILE_DIR"] = profile
        from aster_webkit.app import BrowserApplication, GLib
        from aster_webkit.core import BookmarkStore

        context = GLib.MainContext.default()
        callback_errors = []
        previous_hook = sys.excepthook

        def exception_hook(kind, error, traceback):
            callback_errors.append(error)
            previous_hook(kind, error, traceback)

        sys.excepthook = exception_hook

        def wait_for(condition, message):
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                while context.pending():
                    context.iteration(False)
                if callback_errors:
                    raise AssertionError("GTK callback raised an exception") from callback_errors[0]
                if condition():
                    return
                time.sleep(0.01)
            raise AssertionError(message)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        app = BrowserApplication()
        window = None
        try:
            assert app.register(None)
            app.activate()
            window = app.get_active_window()
            assert window is not None
            origin = f"http://127.0.0.1:{server.server_port}"
            first = window.current
            first.navigate(origin + "/first")
            wait_for(lambda: first.view.get_title() == "Script ran" and not first.view.is_loading(), "HTML/JavaScript did not render")
            first.navigate(origin + "/second")
            wait_for(lambda: first.view.get_title() == "Second" and not first.view.is_loading(), "Second page did not load")
            assert first.view.can_go_back()
            first.view.go_back()
            wait_for(lambda: first.view.get_title() == "Script ran" and not first.view.is_loading(), "Back navigation failed")
            assert first.view.can_go_forward()
            first.view.go_forward()
            wait_for(lambda: first.view.get_title() == "Second" and not first.view.is_loading(), "Forward navigation failed")
            window.toggle_bookmark()
            assert BookmarkStore(Path(profile) / "bookmarks.json").contains(origin + "/second")
            window.zoom(0.1)
            assert abs(first.view.get_zoom_level() - 1.1) < 0.001
            window.show_find()
            window.find_entry.set_text("Aster")
            window.close_find()
            second = window.new_tab(origin + "/first")
            wait_for(lambda: second.view.get_title() == "Script ran" and not second.view.is_loading(), "New tab failed")
            assert any("aster_smoke=one" in cookie for cookie in Fixture.cookies)
            assert window.tabs.get_n_pages() == 2
            window.close_tab()
            wait_for(lambda: window.tabs.get_n_pages() == 1, "Close tab failed")
            window.reopen_tab()
            wait_for(lambda: window.tabs.get_n_pages() == 2 and window.current.view.get_title() == "Script ran", "Reopen tab failed")
            # Exercise features in the actual native panel, using a real DOCX ZIP.
            doc = Path(profile) / "Reading with spaces.docx"
            with zipfile.ZipFile(doc, "w") as archive:
                archive.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Aster reads Word documents locally. Hola Marcelo.</w:t></w:r></w:p></w:body></w:document>')
            window.tools.open_document(doc)
            wait_for(lambda: window.tools.document is not None, "Word document did not open in Aster")
            assert "Hola Marcelo" in window.tools.buffer_text(window.tools.reader)
            window.tools.reader_search.set_text("Marcelo")
            window.tools.find_document(window.tools.reader_search)
            assert window.tools.buffer_text(window.tools.reader, selection=True) == "Marcelo"
            window.tools.show("assistant")
            window.tools.include_context.set_active(True)
            window.tools.prompt.set_text("summarize")
            # Remove selection to summarize the whole document.
            buffer = window.tools.reader.get_buffer()
            buffer.place_cursor(buffer.get_start_iter())
            window.tools.ask()
            wait_for(lambda: not window.tools.busy, "Offline assistant did not finish")
            assert "Word documents locally" in window.tools.buffer_text(window.tools.answer)
            window.tools.prompt.set_text("new tab")
            window.tools.ask()
            assert window.tabs.get_n_pages() == 3
            window.close_tab()
            wait_for(lambda: window.tabs.get_n_pages() == 2, "Assistant-created tab did not close")
            window.set_fullscreen_ui(True)
            assert not window.toolbar.get_visible()
            window.set_fullscreen_ui(False)
            assert window.toolbar.get_visible()

            def javascript(body):
                completed = []
                failures = []
                def done(view, result, *_):
                    try:
                        completed.append(view.call_async_javascript_function_finish(result).to_string())
                    except GLib.Error as error:
                        failures.append(error)
                window.current.view.call_async_javascript_function(body, -1, None, "aster-test", None, None, done, None)
                wait_for(lambda: completed or failures, "JavaScript feature check timed out")
                if failures:
                    raise AssertionError("JavaScript feature check failed") from failures[0]
                return completed[0]

            from aster_webkit.media import MEDIA_PROBE
            capabilities = json.loads(javascript(MEDIA_PROBE))
            assert capabilities["secure"]
            assert isinstance(capabilities["widevine"], str)
            assert "codecs" in capabilities
            print("Media capabilities:", json.dumps(capabilities))

            # Decode and play an authored, unencrypted video through the real engine.
            video = Path(profile) / "sample.webm"
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                            "testsrc=size=160x90:rate=10", "-t", "2", "-pix_fmt", "yuv420p", "-c:v", "libvpx",
                            "-an", str(video)], check=True, timeout=25)
            Fixture.video = video.read_bytes()
            media = json.loads(javascript("""
                const video = document.createElement('video');
                video.muted = true; video.playsInline = true; video.src = '/sample.webm';
                document.body.append(video);
                await video.play();
                await new Promise((resolve, reject) => {
                  const timer = setTimeout(() => reject(new Error('Playback did not advance')), 8000);
                  video.ontimeupdate = () => {if (video.currentTime > .1) {clearTimeout(timer); resolve();}};
                });
                const result = {width: video.videoWidth, time: video.currentTime};
                video.pause(); video.remove(); return JSON.stringify(result);
            """))
            assert media["width"] == 160 and media["time"] > 0.1
            # Test real offline speech synthesis without requiring an audio device.
            speech = Path(profile) / "read-aloud.wav"
            subprocess.run(["espeak-ng", "--stdin", "-v", "en", "-w", str(speech)],
                           input="Aster reads Word documents locally.", text=True, check=True, timeout=15)
            assert speech.stat().st_size > 1000
            screenshot_path = os.environ.get("ASTER_SMOKE_SCREENSHOT")
            if screenshot_path:
                from PIL import ImageGrab

                home = window.new_tab()
                window.tools_revealer.set_reveal_child(False)
                wait_for(lambda: home.view.get_title() == "New tab" and not home.view.is_loading(), "New-tab page failed")
                frames = []

                def content_painted():
                    frame = ImageGrab.grab()
                    # Inspect only the web content, excluding native controls,
                    # window borders and the toast area. A blank renderer must
                    # not pass merely because the DOM and page title loaded.
                    colors = frame.crop((160, 160, window.get_width() - 160,
                                         window.get_height() - 160)).getcolors(128)
                    if colors is None or len(colors) > 20:
                        frames.append(frame)
                        return True
                    return False

                wait_for(content_painted, "WebKit loaded HTML but did not paint the page")
                destination = Path(screenshot_path)
                destination.parent.mkdir(parents=True, exist_ok=True)
                frames[-1].save(destination)
                window.tools.show("reader")
                wait_for(lambda: window.tools_revealer.get_child_revealed(), "Reader panel did not open")
                ImageGrab.grab().save(destination.with_name("aster-reader.png"))
            assert not callback_errors
            print("PASS: real WebKit HTML/JS, navigation, tabs, bookmarks, cookies, Word reader, offline assistant commands/excerpts, fullscreen UI, VP8 video playback and speech synthesis")
        finally:
            if window:
                window.close()
            app.quit()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            sys.excepthook = previous_hook


if __name__ == "__main__":
    main()
