# Aster Companion for Firefox

For repeatable installation and updates, use the new [platform setup guides](../../docs/setup/README.md). They include managed desktop installs, rollback, and the remaining Android signing requirements.

An **experimental Firefox add-on**, not a standalone Aster executable or a complete v15 port. It adds Aster-style workflows to official Firefox, whose **Gecko and SpiderMonkey engines are not Chromium**. Firefox retains its own browser interface, updates, permissions, password manager, downloads and extension system.

This is the broad-platform companion to the [Linux WebKit prototype](../webkit/README.md). It does not change the original Qt installers. It does not embed Firefox inside GTK or turn WebKitGTK into a Windows/Android engine.

## Features in this increment

- Responsive Aster workspace with search, quick links, light/dark/device themes and larger touch controls.
- Find, switch, pin, mute and explicitly park web tabs. Pinned/audible tabs are protected from parking.
- Persistent parked addresses. Parking replaces the website with a local placeholder; resuming is explicit. It does **not** preserve unsent forms, the page DOM or scroll position. Back can also revisit the original page because Firefox keeps tab navigation history.
- Named workspace snapshots, up to 50 pages each. Restore opens parked placeholders without contacting every saved website. Existing open tabs are not closed when saving a workspace.
- Optional ad/tracker blocking, balanced/strict/custom modes, host exceptions and known streaming-site protection. It is a deliberately small list, **not** a replacement for a maintained full filter-list engine such as uBlock Origin.
- Text reading snapshots, extractive local highlights and read-aloud with an installed **local** device voice. No cloud model receives page text. This is not the original generative AI assistant, a full reader engine or a native TTS port.
- Local `/open`, `/search`, `/stats`, `/help` commands.
- Firefox cookie containers where the platform exposes them. Cookie permission is requested separately; missing containers never silently restore into the default identity.
- JSON export/import of saved pages and workspaces. Import merges records and preserves current settings. Invalid data is rejected before writing. Private tabs are excluded from persistence.
- Prime Video / Netflix / YouTube links and practical playback help. Playback uses ordinary Firefox tabs, not a custom DRM implementation.

## Platform and release status

| Platform | Available path | Remaining verification / limits |
| --- | --- | --- |
| Windows supported by current Firefox | Same add-on in official Firefox; optional Python profile launcher | Real Firefox CI is included. No standalone Aster `.exe` is supplied. |
| Modern desktop Linux with current Firefox | Distro Firefox, Mozilla build, or official Firefox Flatpak | Real Firefox CI is included. Not a claim of every distro, architecture, display server or driver working. |
| Steam Deck / SteamOS | Firefox Flatpak in Desktop Mode; large controls in Aster settings | No Steam Deck hardware or Gaming Mode/controller test yet. Do not run the old Arch installer against SteamOS for this edition. |
| Android with Firefox 142+ | Same add-on; responsive workspace opened from Firefox's Extensions menu | Declared target, **not device-tested**. API availability varies; containers, pinning and speech may be unavailable. No standalone APK supplied. |
| macOS | Firefox add-on may work; optional launcher locates `/Applications/Firefox.app` | Untested, not a supported release claim. |

Minimum declared Firefox version is **142**. Use a current supported Firefox release, not an old build just because it meets that minimum. A Windows/Linux viewport screenshot is not proof of Android or Deck compatibility.

## Try the development build on desktop

1. Use the branch containing this directory, then open `about:debugging#/runtime/this-firefox` in official Firefox.
2. Choose **Load Temporary Add-on**, and select `extension/manifest.json`.
3. Pin the Aster toolbar button if desired. Click it, then **Open workspace**. `Alt+Shift+A` also opens the workspace where extension shortcuts are supported.
4. Open a web article and use the toolbar's **Read & summarize page**. Active-tab access is granted by that explicit toolbar interaction. Firefox restricts injection on some pages; those failures appear as errors.
5. For blocking, choose **Settings → Enable blocking access**, approve Firefox's prompt, select a mode, and save. It starts **off** and requests website access separately. Site exceptions and streaming protection take precedence over block rules.

Temporary installation ends when Firefox restarts; its test data may be removed. Export a backup before removing or reloading a temporary installation. Test restart persistence with a signed installation before release.

### Normal desktop / Android installation requires signing

Build the unsigned test package with Python 3.10+:

```bash
python experiments/firefox/tools/build.py
```

Output: `experiments/firefox/dist/aster-companion-0.2.0-unsigned.xpi`, plus SHA-256. The build is deterministic and uses only Python's standard library.

**This unsigned XPI is not an installable release for normal Firefox.** A maintainer must submit the package to Mozilla for signing and declare/test Android compatibility. No signing credentials or signed release are included. Do not disable Firefox signature verification or other security controls.

After signing, desktop users can use **Add-ons → gear menu → Install Add-on From File**. For Android, follow Mozilla's [signed add-on file installation instructions](https://extensionworkshop.com/documentation/publish/install-self-distributed/). For an AMO listing, Android compatibility must also be declared in the listing. Signing does not establish hardware or DRM compatibility.

## Linux and Steam Deck

Mozilla documents [Firefox installation on Linux](https://support.mozilla.org/en-US/kb/install-firefox-linux), including:

```bash
flatpak install flathub org.mozilla.firefox
flatpak run org.mozilla.firefox
```

Use Desktop Mode / Discover on Steam Deck to install the official Firefox Flatpak, then load the development add-on or a future signed build. Aster's large controls are in its settings; controller mappings and Gaming Mode remain manual test items.

An optional launcher opens an isolated Firefox profile without installing anything or changing your default browser:

```bash
python experiments/firefox/launch_aster.py --check
python experiments/firefox/launch_aster.py https://www.primevideo.com/
python experiments/firefox/launch_aster.py --flatpak https://www.primevideo.com/
```

On Windows use `py` in place of `python`. The launcher requires an existing official Firefox installation and does **not** install the companion. Load/install it in the opened profile separately. The launcher waits for the browser process. Firefox handles engine updates; update the companion separately.

## Protected video: what this actually enables

Amazon [lists Firefox among supported computer browsers](https://www.primevideo.com/help?nodeId=GUX9FYHU5D8LC9EJ). Official Firefox desktop [supports Widevine DRM](https://support.mozilla.org/en-US/kb/enable-drm). Using that existing, supported browser path avoids pretending the WebKit prototype can gain premium streaming through a switch.

1. Update Firefox. In desktop Firefox settings, enable **Play DRM-controlled content** if needed and let Firefox manage Widevine.
2. Open the streaming site, sign in yourself and try a title included with your subscription.
3. If it fails, turn Aster blocking off, reload, check Firefox DRM settings and the service's supported platforms. Do not spoof the user agent, copy a CDM from Chrome or disable TLS/sandboxing.
4. On Android a service may direct you to its official app. Mobile web playback, 4K, HDCP, downloads, casting, codecs, regional availability and every subscription service are **not guaranteed**.

No subscription video has been validated by the automated tests. A shortcut, successful sign-in, HTML5 playback and EME detection each fall short of proving protected playback. The original v15 source also explicitly disclaims universal premium streaming support.

## Feature parity audit against original v15 source

| Original capability | Companion implementation / remaining work |
| --- | --- |
| Tabs, navigation, bookmarks, history, downloads, passwords | Firefox provides these natively; Aster adds tab tools. Original Qt profile data is not migrated. |
| Persistent parking and memory governor | Explicit parking + persistent addresses implemented; automatic memory governor and camel animation not ported. No measured RAM claims. |
| Personalized adblock | Host-rule subset and modes implemented; URL-substring/cosmetic rules and maintained subscription lists not ported. |
| Aster Lite | Local text snapshot implemented; no custom network/HTML renderer. Original page remains open until explicitly parked or closed. |
| Containers | Firefox container adapter implemented; Android/device behavior needs validation. No Qt cookie migration. |
| Internal AI / local model / document index | Small local command desk and extractive highlights only. Model inference, local document search and original AI integrations remain unported. |
| TTS | Device-local Web Speech voices where supported; original voice integration not ported. |
| Extension manager / Python plugins | Firefox's native add-on manager; original Python plugin ABI and Chromium extensions are not compatible. |
| Language and customization | Theme and touch settings implemented; workspace UI is English. Firefox retains its own localization. |
| Streaming capsules | Ordinary official Firefox tabs; no Chromium capsules and no promise of universal DRM playback. |
| Cross-platform standalone installers | Add-on source/test package + optional Firefox launcher; native Windows/Android Aster packages remain future work. |

## Validation

```bash
node --test experiments/firefox/tests/core.test.cjs experiments/firefox/tests/background.test.cjs
npx --yes --package=web-ext@10.6.0 web-ext lint --source-dir experiments/firefox/extension --warnings-as-errors
python -m pip install selenium==4.35.0
python experiments/firefox/tests/smoke_firefox.py
```

The [workflow](../../.github/workflows/firefox-companion.yml) runs on Linux and Windows, temporarily installs the actual unsigned add-on into a fresh Firefox profile, tests storage and UI, and uploads the unsigned XPI and viewport screenshots. Selenium Manager obtains a compatible driver. Tests use a localhost article and no service accounts. The isolated test driver uses Mozilla’s documented `--allow-system-access` flag to automate extension documents; the launcher does not enable browser automation.

Covered: navigation/rule/backup unit checks, real Firefox workspace UI, page parking/resuming, storage across dashboard reload, lazy workspace restore, corrupt import rejection, article extraction excluding forms, text escaping and responsive overflow checks. Not covered: signed-install restart persistence, Android runtime, Deck hardware, permission prompts, live blocking interception, container isolation, speech output, downloads or paid DRM. These are release gates, not implied successes.

## Privacy and permissions

No telemetry, cloud inference, external scripts, automatic browsing-history indexing or remote filter downloads. Tab metadata is used to show the workspace. Saved addresses and settings stay in Firefox extension-local storage; backups are user-initiated downloads. Reader text is held transiently in the background and passed once to the reading tab, then removed from background memory. A background shutdown or ten-minute expiry can invalidate an unread snapshot. The open reader retains its copy until closed.

Optional website access lets blocking inspect HTTP(S) requests and enables page injection on permitted sites. The add-on itself does not read cookies; the optional cookie permission is needed for Firefox container-tab operations. Installing the required `contextualIdentities` permission may enable Firefox's container feature. Revoking website access stops blocking; remove the add-on to remove all its permissions. Browser/service telemetry is governed separately by Firefox and the sites you visit.

Source: MIT, under the repository license. Firefox, Gecko, Widevine and any device voices retain their own licenses. Nothing here grants rights to redistribute those components.
