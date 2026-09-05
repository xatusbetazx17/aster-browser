"""Real GTK/WebKit smoke test. Requires a display (or Xvfb), never a mock engine."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class Fixture(BaseHTTPRequestHandler):
    cookies = []

    def do_GET(self):
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
            assert not callback_errors
            print("PASS: real WebKit HTML/JS, navigation, tabs, bookmarks, shared cookies, zoom, and find controls")
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
