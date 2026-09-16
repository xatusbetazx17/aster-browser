"""The capsule must open a real separate runtime, or say plainly that it cannot.

Two failures would be worse than having no capsule at all: quietly handing the
URL back to Aster (the default-browser loop the xdg-open fallback used to cause)
and putting an unvalidated address into a process argument list.
"""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aster_webkit.capsules import (
    UNAVAILABLE,
    CapsuleManager,
    CapsuleRuntime,
    matching_service,
    needs_capsule,
    service_label,
)

CHROMIUM = CapsuleRuntime(label="Chromium", argv=("chromium",), family="chromium", source="native")
FIREFOX = CapsuleRuntime(label="Firefox", argv=("firefox",), family="firefox", source="native")
ELECTRON = CapsuleRuntime(label="Aster DRM capsule", argv=("electron", "/opt/aster/capsule"),
                          family="electron_ecs", source="packaged")


def manager(runtime, root):
    return CapsuleManager(runtime=runtime, profile_root=Path(root))


class ServiceRuleTests(unittest.TestCase):
    def test_protected_services_are_recognized_by_host(self):
        self.assertEqual(service_label("https://www.primevideo.com/detail/xyz"), "Prime Video")
        self.assertEqual(service_label("https://www.netflix.com/watch/123"), "Netflix")
        self.assertTrue(needs_capsule("https://www.disneyplus.com/"))

    def test_amazon_store_pages_are_not_treated_as_prime_video(self):
        self.assertEqual(service_label("https://www.amazon.com/gp/video/storefront"), "Prime Video")
        self.assertIsNone(matching_service("https://www.amazon.com/gp/product/B09XYZ"))
        self.assertFalse(needs_capsule("https://www.amazon.com/gp/product/B09XYZ"))

    def test_unprotected_video_and_cloud_gaming_stay_in_aster(self):
        for uri in ("https://www.youtube.com/watch?v=1", "https://cloud.boosteroid.com/",
                    "https://www.crunchyroll.com/watch/G6J0", "https://www.xbox.com/play"):
            self.assertFalse(needs_capsule(uri), uri)

    def test_unknown_host_falls_back_to_the_hostname(self):
        self.assertEqual(service_label("https://video.example.com/watch"), "video.example.com")
        self.assertFalse(needs_capsule("https://video.example.com/watch"))


class CommandTests(unittest.TestCase):
    def test_only_http_addresses_reach_a_command_line(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(CHROMIUM, root)
            for uri in ("javascript:alert(1)", "file:///etc/passwd", "about:blank",
                        "https://user:pw@example.com/", "https://exa mple.com/", ""):
                with self.assertRaises(ValueError, msg=uri):
                    capsule.build_command(uri)

    def test_missing_runtime_refuses_instead_of_opening_the_default_browser(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(UNAVAILABLE, root)
            with self.assertRaises(ValueError) as caught:
                capsule.build_command("https://www.netflix.com/watch/1")
            self.assertIn("setup_chromium_drm_capsule.sh", str(caught.exception))

    def test_electron_capsule_receives_url_service_and_profile(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(ELECTRON, root)
            command, profile, service = capsule.build_command("https://www.netflix.com/watch/123")
            self.assertEqual(service, "Netflix")
            self.assertEqual(command[:3], ["electron", "/opt/aster/capsule", "--"])
            self.assertIn("--aster-url=https://www.netflix.com/watch/123", command)
            self.assertIn("--aster-service=Netflix", command)
            self.assertIn(f"--aster-profile={profile}", command)

    def test_chromium_uses_app_window_for_protected_and_a_tab_otherwise(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(CHROMIUM, root)
            command, profile, _ = capsule.build_command("https://www.primevideo.com/detail/x")
            self.assertIn(f"--user-data-dir={profile}", command)
            self.assertIn("--app=https://www.primevideo.com/detail/x", command)

            command, _, _ = capsule.build_command("https://www.youtube.com/watch?v=1")
            self.assertIn("--new-window", command)
            self.assertIn("https://www.youtube.com/watch?v=1", command)
            self.assertFalse(any(part.startswith("--app=") for part in command))

    def test_firefox_runs_its_own_instance_and_profile(self):
        with tempfile.TemporaryDirectory() as root:
            command, profile, service = manager(FIREFOX, root).build_command("https://www.hulu.com/watch/1")
            self.assertEqual(service, "Hulu")
            self.assertEqual(command[:2], ["firefox", "--new-instance"])
            self.assertIn("--profile", command)
            self.assertIn(profile, command)

    def test_override_substitutes_the_url_placeholder_and_keeps_no_profile(self):
        capsule = CapsuleManager(command_override="my-runtime --open {url}")
        command, profile, service = capsule.build_command("https://example.com/video")
        self.assertEqual(command, ["my-runtime", "--open", "https://example.com/video"])
        self.assertIsNone(profile)
        self.assertEqual(service, "example.com")


class ProfileTests(unittest.TestCase):
    def test_each_service_gets_its_own_profile_and_reset_spares_the_others(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(CHROMIUM, root)
            prime = capsule.profile_dir("Prime Video")
            netflix = capsule.profile_dir("Netflix")
            self.assertNotEqual(prime, netflix)
            self.assertTrue(prime.is_dir() and netflix.is_dir())
            self.assertTrue((prime / "capsule.json").is_file())

            self.assertTrue(capsule.reset_profile("Prime Video"))
            self.assertFalse(prime.exists())
            self.assertTrue(netflix.is_dir())
            self.assertFalse(capsule.reset_profile("Prime Video"))

    def test_a_hostile_service_name_cannot_escape_the_capsule_directory(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(CHROMIUM, root)
            directory = capsule.profile_dir("../../etc/shadow")
            self.assertEqual(directory.parent, Path(root))
            self.assertNotIn("..", directory.parts)


class ReportTests(unittest.TestCase):
    def test_launch_reports_the_runtime_and_claims_no_playback(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(CHROMIUM, root)
            with patch("aster_webkit.capsules.subprocess.Popen") as spawn:
                result = capsule.launch("https://www.primevideo.com/detail/x")
            spawn.assert_called_once()
            self.assertTrue(result.ok)
            self.assertEqual(result.runtime, "Chromium")
            self.assertIn("Aster supplies no CDM", result.note)

    def test_a_runtime_that_will_not_start_is_reported_as_a_failure(self):
        with tempfile.TemporaryDirectory() as root:
            capsule = manager(CHROMIUM, root)
            with patch("aster_webkit.capsules.subprocess.Popen", side_effect=OSError("no such file")):
                result = capsule.launch("https://www.netflix.com/watch/1")
            self.assertFalse(result.ok)
            self.assertIn("did not start", result.note)

    def test_status_text_states_the_limits_in_both_directions(self):
        with tempfile.TemporaryDirectory() as root:
            missing = manager(UNAVAILABLE, root).status_text()
            self.assertIn("not installed", missing)
            self.assertIn("setup_chromium_drm_capsule.sh", missing)

            available = manager(CHROMIUM, root).status_text()
            self.assertIn("Chromium", available)
            self.assertIn("not proof that a service will play", available)
            self.assertIn("does not bypass DRM", available)


if __name__ == "__main__":
    unittest.main()
