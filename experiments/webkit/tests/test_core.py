import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aster_webkit.core import BookmarkStore, is_web_uri, navigation_target, profile_paths


class NavigationTests(unittest.TestCase):
    def test_addresses_and_local_development_servers(self):
        cases = {
            "": "about:blank",
            "about:blank": "about:blank",
            " example.com/path?q=a%20b ": "https://example.com/path?q=a%20b",
            "https://example.com/path#section": "https://example.com/path#section",
            "http://example.com": "http://example.com",
            "localhost:3000/test": "http://localhost:3000/test",
            "app.localhost:8080": "http://app.localhost:8080",
            "127.0.0.1:8080": "http://127.0.0.1:8080",
            "[::1]:8000/test": "http://[::1]:8000/test",
            "router.local:8080": "http://router.local:8080",
            "mañana.example": "https://mañana.example",
        }
        for value, expected in cases.items():
            with self.subTest(value=value):
                self.assertEqual(navigation_target(value), expected)

    def test_search_is_encoded_and_not_executed(self):
        cases = {
            "network engineer": "network+engineer",
            "C++ & Python": "C%2B%2B+%26+Python",
            "<script>alert(1)</script>": "%3Cscript%3Ealert%281%29%3C%2Fscript%3E",
            "site:example.com networking": "site%3Aexample.com+networking",
        }
        for value, query in cases.items():
            with self.subTest(value=value):
                self.assertEqual(navigation_target(value), "https://duckduckgo.com/?q=" + query)

    def test_active_schemes_malformed_urls_and_credentials_are_rejected(self):
        values = [
            "javascript:alert(1)", "JaVaScRiPt: alert(1)", "data:text/html,<script>",
            "file:///etc/passwd", "blob:https://example.com/id", "about:config",
            "ftp://example.com/file", "https:///missing-host", "https://example.com:99999",
            "https://good.example@evil.example", "https://example.com\\@evil.example",
            "localhost:not-a-port", "https://example.com/\nnext", "https://[::1",
            "https://example.com:0", "https://example.com/a b",
        ]
        for value in values:
            with self.subTest(value=value), self.assertRaises(ValueError):
                navigation_target(value)

    def test_web_uri_filter_handles_untrusted_bookmark_types(self):
        for value in [None, 5, [], {}, "javascript:alert(1)", "about:blank", "https://", "https://a.example/a\tb"]:
            with self.subTest(value=value):
                self.assertFalse(is_web_uri(value))
        self.assertTrue(is_web_uri("https://example.com/?a=1&b=2"))


class BookmarkTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "profile" / "bookmarks.json"

    def test_bookmarks_survive_restart_and_can_be_removed(self):
        store = BookmarkStore(self.path)
        self.assertTrue(store.toggle("https://example.com", "Español <b>& friends"))
        restarted = BookmarkStore(self.path)
        self.assertEqual(restarted.items, store.items)
        self.assertFalse(restarted.toggle("https://example.com", "Renamed"))
        self.assertEqual(BookmarkStore(self.path).items, [])
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_invalid_bookmark_cannot_be_written(self):
        store = BookmarkStore(self.path)
        with self.assertRaises(ValueError):
            store.toggle("javascript:alert(1)", "Invalid")
        self.assertFalse(self.path.exists())

    def test_damaged_files_are_preserved(self):
        self.path.parent.mkdir()
        for payload in (b"not JSON", b"\xff", b"{}", b'[{"title":"Bad","uri":"file:///etc/passwd"}]'):
            with self.subTest(payload=payload):
                self.path.write_bytes(payload)
                with self.assertRaises(ValueError):
                    BookmarkStore(self.path)
                self.assertEqual(self.path.read_bytes(), payload)

    def test_failed_write_keeps_previous_bookmarks_and_removes_temporary_file(self):
        store = BookmarkStore(self.path)
        store.toggle("https://example.com", "Original")
        original = self.path.read_bytes()
        with patch("aster_webkit.core.os.replace", side_effect=OSError("disk error")):
            with self.assertRaises(OSError):
                store.toggle("https://other.example", "New")
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(store.items, json.loads(original))
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_existing_duplicates_are_normalized(self):
        self.path.parent.mkdir()
        item = {"title": "One", "uri": "https://example.com"}
        self.path.write_text(json.dumps([item, item]), encoding="utf-8")
        self.assertEqual(BookmarkStore(self.path).items, [item])


class ProfileTests(unittest.TestCase):
    def test_profile_override_is_isolated(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, {"ASTER_WEBKIT_PROFILE_DIR": directory}):
                data, cache = profile_paths()
                self.assertEqual(data, Path(directory).resolve())
                self.assertEqual(cache, data / "cache")

    def test_xdg_directories(self):
        with patch.dict(os.environ, {"XDG_DATA_HOME": "/tmp/aster-test-data", "XDG_CACHE_HOME": "/tmp/aster-test-cache"}, clear=True):
            self.assertEqual(profile_paths(), (Path("/tmp/aster-test-data/aster-webkit"), Path("/tmp/aster-test-cache/aster-webkit")))

    def test_relative_xdg_paths_fall_back_to_home(self):
        with patch.dict(os.environ, {"XDG_DATA_HOME": "relative", "XDG_CACHE_HOME": ""}, clear=True):
            data, cache = profile_paths()
            self.assertEqual(data, Path.home() / ".local/share/aster-webkit")
            self.assertEqual(cache, Path.home() / ".cache/aster-webkit")


if __name__ == "__main__":
    unittest.main()
