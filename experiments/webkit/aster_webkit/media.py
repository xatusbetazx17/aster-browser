"""Media prerequisites and truthful runtime reports; no CDM download or impersonation."""
from pathlib import Path
from urllib.parse import urlsplit

from .core import is_web_uri

MEDIA_PROBE = Path(__file__).with_name("media_probe.js").read_text(encoding="utf-8")


def origin_label(uri: str) -> str | None:
    if not is_web_uri(uri):
        return None
    parsed = urlsplit(uri)
    # Permission labels never include a path, query, credentials or markup.
    host = parsed.hostname.encode("idna").decode("ascii")
    if ":" in host:
        host = f"[{host}]"
    return f"{parsed.scheme}://{host}" + (f":{parsed.port}" if parsed.port else "")


def configure_media(settings):
    changed = []
    for name in ("enable-media", "enable-mediasource", "enable-encrypted-media", "enable-webrtc",
                 "enable-media-stream", "enable-webgl", "enable-fullscreen"):
        if settings.find_property(name):
            settings.set_property(name, True)
            changed.append(name)
    return changed


def report_text(report: dict) -> str:
    yes_no = lambda value: "Available" if value else "Not available in this page/build"
    codecs = report.get("codecs", {})
    lines = ["Streaming check", "", "Page: " + str(report.get("origin", "unknown")),
             "Secure page: " + yes_no(report.get("secure")),
             "Streaming video (MSE): " + yes_no(report.get("mse")),
             "Cloud connections (WebRTC): " + yes_no(report.get("webrtc")),
             "Controllers (Gamepad API): " + yes_no(report.get("gamepad")),
             "Mouse capture: " + yes_no(report.get("pointerLock")),
             "Fullscreen: " + yes_no(report.get("fullscreen")), ""]
    for name in ("H.264", "AAC", "VP8", "VP9", "Opus"):
        lines.append(f"{name}: {codecs.get(name) or 'Not reported'}")
    lines += ["", "Widevine DRM: " + str(report.get("widevine", "Not checked")),
              "", "An available API or codec is not proof that a paid service will play. "
              "Prime Video and cloud gaming still need a successful session in Aster on your device. "
              "This check does not install DRM components or contact a streaming service."]
    return "\n".join(lines)
