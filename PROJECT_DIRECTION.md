# Aster is a standalone browser

Aster must launch as its own application, with its own interface, tabs, settings, profiles and features. Installing Firefox, Chrome or Chromium and adding an Aster extension does not fulfill that goal. The Firefox companion was an experimental detour and is retired from the active product and setup path.

## Current engine and independence

The active Linux application uses GTK4/libadwaita for its native interface and **WebKitGTK / JavaScriptCore** for web rendering and JavaScript. It starts directly as Aster and does not launch or require another installed browser. WebKit is still a third-party engine; this is **not a claim that Aster already has an original rendering engine**.

A custom interface and browser features can be developed within this standalone app while using an independent rendering engine. Building Aster's own complete HTML/CSS/JavaScript engine from scratch is a separate, much larger implementation. No existing custom Lite renderer currently supplies full modern-web compatibility or premium DRM playback.

## Required direction

- Bring the original parking, blocking, containers, reading, assistant and customization features into the standalone Aster application.
- Keep a recognizable Aster interface, profile and configuration system, rather than moving the product into another browser's add-on system.
- Add native Windows and Android applications and maintained Linux/Steam Deck packages. A desktop layout screenshot or a setup status file is not evidence that those ports exist.
- Install and update actual Aster application code while preserving existing user data.
- Pursue legitimate codec/DRM integration and service testing. Do not replace Aster with an external browser to claim streaming support.

## Deliverable status

| Deliverable | Current status |
| --- | --- |
| Standalone Linux Aster with WebKit | Implemented prototype: tabs, navigation, bookmarks, find, zoom, downloads |
| Managed Linux code installation and updates | Implemented, with file verification and rollback |
| Word/PDF/text reading and read-aloud | Native reading panel; DOCX body/tables, PDF/DOC via local tools, English/Spanish speech |
| Local companion | Offline commands/excerpts; optional local GGUF inference and Vosk voice input; not Siri parity |
| Media and cloud-gaming prerequisites | WebRTC/media settings, permission prompts, fullscreen, runtime capability reporting; no service-level guarantee |
| Original v15 features inside the standalone app | Incomplete; parking, custom adblock, containers, Lite renderer and plugins still need porting |
| Standalone Windows app | Original-engine text preview and native EXE packaging added; full browser port incomplete |
| Standalone Android app | Original-engine Canvas app and APK packaging added; full browser port incomplete |
| SteamOS/Steam Deck package | Not yet validated or shipped |
| Prime Video and other protected streams inside Aster | Not verified; licensing, codecs and service approval remain unresolved |
| Aster rendering engine written from scratch | Initial basic HTML/text parser, layout and link renderer implemented; modern web platform absent |

Legacy Qt installers and the retired companion source remain available as historical development work. They are not substitutes for the standalone product described here.

See [reading/assistant setup](docs/setup/assistant.md), [the original-engine preview](experiments/aster-engine/README.md)
and [the specific streaming, engine and native-port blockers](docs/setup/streaming.md).
The existing WebKit prototype does not meet strict engine independence. The new,
separate original engine meets that dependency constraint but implements only a
small HTML/text subset, not the requested full browser or streaming capabilities.
