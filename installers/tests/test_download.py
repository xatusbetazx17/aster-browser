"""Download recovery and credential-origin boundaries; no live network requests."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.request import Request

SPEC = importlib.util.spec_from_file_location("aster_setup_download", Path(__file__).resolve().parents[1] / "setup.py")
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


class Response:
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def read(self, _limit): return b"verified later against the Git blob hash"


class DownloadTests(unittest.TestCase):
    def test_connection_reset_retries_without_changing_request(self):
        with patch.object(setup, "build_opener") as create, patch.object(setup.time, "sleep"):
            create.return_value.open.side_effect = [URLError(ConnectionResetError("reset")), Response()]
            self.assertTrue(setup.download(setup.API + "/git/ref/heads/main"))
            calls = create.return_value.open.call_args_list
            self.assertEqual(len(calls), 2)
            self.assertIs(calls[0].args[0], calls[1].args[0])

    def test_rate_limit_and_certificate_errors_are_not_retried(self):
        import ssl
        for error in (HTTPError(setup.API, 403, "rate limit", {}, None), URLError(ssl.SSLCertVerificationError("certificate"))):
            with patch.object(setup, "build_opener") as create, patch.object(setup.time, "sleep") as sleep:
                create.return_value.open.side_effect = error
                with self.assertRaises(setup.SetupError): setup.download(setup.API + "/git/trees/main")
                self.assertEqual(create.return_value.open.call_count, 1)
                sleep.assert_not_called()

    def test_ci_token_is_scoped_to_own_repository_api(self):
        with patch.dict(os.environ, {"ASTER_GITHUB_TOKEN": "synthetic-test-value"}), \
             patch.object(setup, "build_opener") as create:
            create.return_value.open.side_effect = [Response(), Response(), Response()]
            setup.download(setup.API + "/git/trees/main")
            setup.download("https://raw.githubusercontent.com/xatusbetazx17/aster-browser/main/README.md")
            setup.download("https://api.github.com/repos/example/other/contents/README.md")
            calls = create.return_value.open.call_args_list
            self.assertEqual(calls[0].args[0].get_header("Authorization"), "Bearer synthetic-test-value")
            self.assertIsNone(calls[1].args[0].get_header("Authorization"))
            self.assertIsNone(calls[2].args[0].get_header("Authorization"))

    def test_redirects_cannot_downgrade_https_or_forward_auth_to_another_host(self):
        request = Request(setup.API + "/git/trees/main", headers={"Authorization": "Bearer synthetic-test-value"})
        handler = setup.HTTPSRedirect()
        with self.assertRaises(setup.SetupError):
            handler.redirect_request(request, None, 302, "Found", {}, "http://example.invalid/source")
        redirected = handler.redirect_request(request, None, 302, "Found", {}, "https://example.invalid/source")
        self.assertIsNone(redirected.get_header("Authorization"))


if __name__ == "__main__": unittest.main()
