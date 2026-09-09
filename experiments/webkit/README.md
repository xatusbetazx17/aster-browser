# Aster WebKit

For repeatable installation and updates, use the [standalone setup guides](../../docs/setup/README.md). They include Linux installs and rollback, with honest Windows/Android build status. See [the product direction](../../PROJECT_DIRECTION.md): Aster runs as its own application; WebKit supplies the rendering engine.

A separate **Linux desktop prototype** with a Chrome-like layout and a non-Chromium engine. The interface belongs to Aster; HTML/CSS rendering comes from **WebKitGTK**, and JavaScript runs in **JavaScriptCore**. This is an existing alternative engine, not a newly written Aster engine.

The original Aster full browser uses Qt WebEngine, which is built on Chromium. This experiment has its own launcher and imports only GTK 4, libadwaita, and WebKitGTK 6.0. It does not fall back to Qt, Electron, CEF, Blink, or V8. Existing installers still run the original app.

## Try it on desktop Linux

Use Python 3.10 or later and the native packages from your distribution. WebKitGTK's **6.0 API requires WebKit 2.40 or later**. Keep the engine updated through your distribution's normal updates.

### Ubuntu 24.04 or a compatible newer release

```bash
sudo apt update
sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 gir1.2-webkit-6.0
```

### Arch Linux

On an up-to-date Arch installation:

```bash
sudo pacman -S --needed python python-gobject gtk4 libadwaita webkitgtk-6.0
```

### Launch from the repository root

After checking out the branch containing this experiment:

```bash
/usr/bin/python3 experiments/webkit/run_aster_webkit.py --check-dependencies
/usr/bin/python3 experiments/webkit/run_aster_webkit.py
```

You can also supply addresses or a quoted search:

```bash
/usr/bin/python3 experiments/webkit/run_aster_webkit.py https://example.com "network engineering"
```

Use your **system Python**, not the old Aster Qt virtual environment. Installing a Python wheel alone does not install the native browser engine. A missing dependency produces a readable message and an unsuccessful exit; it never switches engines silently.

**Steam Deck:** the GTK/WebKit port is a useful Linux foundation, but this experiment does not yet include a verified SteamOS/Flatpak package. The old Steam Deck installer launches the Qt browser. SteamOS users should wait for a tested Flatpak package for this version; the Arch commands above are for desktop Arch installations.

**Windows, macOS, and Android:** no package is supplied by this prototype. A cross-platform non-Chromium product needs a separate port strategy; this Linux implementation does not make the old Windows or Android builds non-Chromium.

## Implemented behavior

- Horizontal, reorderable tabs, new tab, close tab, and reopen a recently closed page.
- Rounded address/search field with explicit DuckDuckGo search submission. Typing alone sends no search requests.
- Back, forward, reload, stop, page-load progress, page titles, and per-tab zoom.
- Native bookmark menu with atomic local storage and a star to add/remove the current page.
- Find-in-page controls and WebKit's developer inspector.
- Native download save chooser; cancel is supported, existing files are not overwritten, and downloads are not automatically opened. Closing the browser cancels active downloads.
- User-initiated new-window links open as related WebKit tabs.
- Native system theme and a local new-tab page that responds to the light/dark preference.
- A separate persistent WebKit profile, cookies shared between tabs, and WebKit Intelligent Tracking Prevention.
- Word/PDF/text reading, document search and offline read-aloud in the native reader panel.
- Offline companion commands/excerpts, optional local GGUF model inference and push-to-talk Vosk input.
- Native media/mouse-capture permission prompts, fullscreen controls and runtime streaming checks.

See [document and local assistant setup](../../docs/setup/assistant.md) for helper packages and models, and [streaming compatibility](../../docs/setup/streaming.md) for the difference between API availability and a working paid service.

The interface is inspired by familiar browser conventions and keeps Aster's own name and appearance.

## Keyboard shortcuts

| Action | Shortcut |
| --- | --- |
| Focus address bar | Ctrl+L or Alt+D |
| New / close tab | Ctrl+T / Ctrl+W |
| Reopen a closed tab | Ctrl+Shift+T |
| Next / previous tab | Ctrl+Tab / Ctrl+Shift+Tab |
| Select tab / last tab | Ctrl+1 through Ctrl+8 / Ctrl+9 |
| Back / forward | Alt+Left / Alt+Right |
| Reload / bypass cache | Ctrl+R or F5 / Ctrl+Shift+R |
| Stop / dismiss find | Escape |
| Add or remove bookmark | Ctrl+D |
| Find in page | Ctrl+F |
| Zoom in / out / reset | Ctrl++ / Ctrl+- / Ctrl+0 |
| Developer tools | Ctrl+Shift+I or F12 |
| Open document | Ctrl+O |
| Aster companion | Ctrl+J |
| Read page / read aloud | Ctrl+Shift+E / Ctrl+Shift+S |
| Fullscreen / exit fullscreen | F11 / Escape |

## Profile and navigation boundaries

Data is stored in `$XDG_DATA_HOME/aster-webkit` (normally `~/.local/share/aster-webkit`), with caches in `$XDG_CACHE_HOME/aster-webkit`. The prototype does not import passwords, history, or cookies from the existing Aster or Chrome profiles. Bookmarks are saved with owner-only permissions. An unreadable or invalid bookmark file is preserved and bookmark writes are disabled for that launch.

For a separate test profile:

```bash
ASTER_WEBKIT_PROFILE_DIR=/tmp/aster-webkit-test /usr/bin/python3 experiments/webkit/run_aster_webkit.py
```

The browser accepts HTTP/HTTPS web addresses. Local IP addresses, localhost, and explicit development-server ports default to HTTP; public domain names default to HTTPS. Other terms become a search when submitted. Script, data, local-file, and external-app navigation are unsupported. `about:blank` opens the local new-tab page when entered in the address bar.

WebKit's certificate validation remains enabled. The shell does not disable engine sandboxing or enable a remote debugging listener. Camera/microphone, mouse-capture and protected-media requests have native one-request prompts; other permission types are denied. Background-tab requests and prompts made stale by navigation/tab switching are denied. Persistent HTTP-auth credential storage is disabled; this is not a password manager or a private-browsing mode.

## What still needs work

This is an experimental engine path, **not full Chrome or existing Aster feature parity**. It has no Chrome Web Store extension support, Google account sync, password manager, browser-history UI, crash/session restore, private mode, or verified DRM streaming. Location, notifications and other unimplemented permission types remain denied.

The original ad-block rules, containers, tab parking and Lite mode have not been ported. The native companion is an initial local implementation, not complete assistant parity. WebKit ITP is not a replacement for an ad blocker. Media/codec availability depends on distro packaging. Lower memory consumption and performance improvements have not been measured or claimed.

Next development work should validate the GUI on real Linux machines, add a maintained Flatpak build for Steam Deck, and port the existing Aster features one at a time. A from-scratch Aster rendering engine remains a separate, much larger project.

## Validation

Dependency-free tests from the repository root:

```bash
python3 -m unittest discover -s experiments/webkit/tests -p 'test_*.py' -v
python3 -m compileall -q experiments/webkit
```

These cover navigation/search parsing, rejected schemes and malformed input, bookmark persistence, corrupt files, failed-write recovery, and profile selection. They do not validate the GTK interface.

The real-engine smoke test requires the native packages and a Linux graphical session:

```bash
/usr/bin/python3 experiments/webkit/tests/smoke_webkit.py
```

For a headless Ubuntu runner, install `xvfb` and `dbus-x11`, then:

```bash
xvfb-run -a dbus-run-session -- /usr/bin/python3 experiments/webkit/tests/smoke_webkit.py
```

This opens the actual application with an isolated temporary profile and localhost HTTP fixture. It checks JavaScript, navigation, tabs, cookies, bookmarks, the native DOCX reader, offline assistant commands/excerpts, fullscreen UI, unencrypted VP8 playback and eSpeak speech synthesis. It requires `ffmpeg`, `espeak-ng` and the distro GStreamer plugins in addition to GTK/WebKit. It fails if required libraries are absent or a GTK callback raises an exception. It does not visit public websites. The commands above use WebKit's default sandbox settings.

The [GitHub Actions workflow](../../.github/workflows/webkit-prototype.yml) runs both sets of checks and captures a desktop screenshot. The hosted runner rejects WebKit's nested UID namespace, so that one CI smoke-test step sets `WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1` for the trusted localhost fixture. This is not set by the app or launcher. **The CI result does not validate the WebKit sandbox.** A real desktop check with normal sandboxing is still required.

Manual checks still needed: visual layout on a desktop and Steam Deck resolution, links opening new tabs, download save/cancel/error dialogs, persistent login across restarts, TLS error pages, and representative websites/media.

The dependency-free suite contains **27 tests** for navigation/storage, documents, local commands, speech invocation and media reporting. Native GUI checks run in GitHub Actions because the authoring environment cannot install the desktop runtime. See [validation notes](VALIDATION.md) for exact scope and the current pull request's Actions results. No paid-service, Android, native Windows or Steam Deck result is implied.

## References

- [Qt WebEngine's Chromium foundation](https://doc.qt.io/qt-6/qtwebengine-overview.html)
- [WebKitGTK project](https://webkitgtk.org/)
- [GTK 4 / WebKitGTK 6.0 migration guide](https://webkitgtk.org/reference/webkit2gtk/2.39.91/migrating-to-webkitgtk-6.0.html)
- [WebKitGTK Python API](https://api.pygobject.gnome.org/WebKit-6.0/class-WebView.html)

Aster's source remains under the repository's MIT license. GTK, libadwaita, WebKitGTK, and their dependencies retain their own licenses.
