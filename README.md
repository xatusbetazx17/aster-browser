# Aster Browser

Aster Browser is an **experimental Linux-first web browser project** focused on three things that many Linux users care about: **lower memory pressure**, **native control**, and **AI-assisted workflows**.

It combines a full website mode powered by **Qt WebEngine** with a custom low-memory companion mode called **Aster Lite**, plus a tab parking system, native ad/tracker blocking, container profiles, and portable install options for desktop Linux and Steam Deck.

> **Status:** Alpha / test build. Aster is real and testable, but it is not yet a drop-in replacement for Firefox, Brave, or Chromium on every site.

## Why Aster exists

Most browsers are strong general-purpose browsers, but Linux users often still want a browser that feels more tailored to their machines and workflows:

- lower RAM pressure when many tabs are open
- portable installs that do not require a full distro package
- stronger user control over profiles, containers, and settings
- built-in ad and tracker blocking
- optional AI tools without forcing cloud-only use
- a path toward a more independent engine over time

Aster is built around that idea.

## Core features

- **Tabbed browser shell** for Linux desktop use
- **Full Web Mode** through Qt WebEngine for modern websites
- **Aster Lite** custom low-memory mode for lightweight reading and recovery
- **Tab parking** to reduce memory pressure from inactive tabs
- **Native ad/tracker blocking** through request interception
- **Container profiles**: `default`, `work`, `media`, `banking`
- **AI side panel** with slash commands like `/open`, `/search`, `/park`, `/lite`, `/stats`
- **Plugin system** with a sample `focus_mode` plugin
- **Portable launcher support** for Steam Deck and other Linux systems
- **Flatpak packaging skeleton**

## Honest limitations

Aster is open about what is still in progress:

- Full modern web compatibility still comes from **Qt WebEngine**.
- **Aster Lite** is a real custom mode, but it is not yet a complete browser engine for arbitrary modern websites.
- DRM streaming compatibility depends on host codecs, GPU support, and distribution packaging.
- This repo is best treated as an **alpha project** for testing, contribution, and iteration.

## Architecture at a glance

Aster currently has three browsing states:

1. **Full Web Mode**
   - powered by Qt WebEngine
   - used for complex modern websites, streaming pages, and general browsing

2. **Aster Lite**
   - custom text-first lightweight mode
   - intended for low-memory fallback, reading, recovery, and reduced-resource browsing

3. **Parked Tabs**
   - inactive tabs can be unloaded and restored later
   - keeps the browser responsive instead of keeping every tab fully alive all the time

That combination is what makes Aster feel different from a standard wrapper around an existing browser core.

## Quick start

### Standard Linux install

```bash
python -m pip install -r requirements.txt
python run_aster.py
```

### Arch Linux

```bash
sudo pacman -S --needed python python-pip python-pyqt6 python-pyqt6-webengine qt6-wayland
python run_aster.py
```

### Steam Deck / portable install

Use the bundled launcher script:

```bash
chmod +x tools/Aster-Browser-SteamDeck.sh
./tools/Aster-Browser-SteamDeck.sh
```

That script installs Aster into your home folder, creates a private Python environment, and adds an app-menu entry.

## AI setup

Aster supports local or cloud AI providers.

For local-only testing, use an OpenAI-compatible local endpoint such as Ollama.

For OpenAI API testing, use the helper script:

```bash
chmod +x tools/Aster-Enable-OpenAI.sh
./tools/Aster-Enable-OpenAI.sh
```

It stores your key locally in your user profile and updates the app launcher to start with those settings.

See [`docs/AI-SETUP.md`](docs/AI-SETUP.md) for the full guide.

## How to install on Linux

```bash
chmod +x Aster-Browser-Merged-Linux.sh
./Aster-Browser-Merged-Linux.sh -y
```
## To install and launch immediately:

```bash
./Aster-Browser-Merged-Linux.sh --run
```
## To uninstall:

```bash
./Aster-Browser-Merged-Linux.sh --uninstall
```

## Flatpak

Inside the Flatpak ZIP, run:

```bash
cd aster-browser-flatpak-kit
./packaging/flatpak/build-flatpak.sh
```
## Windows
```
powershell -ExecutionPolicy Bypass -File .\packaging\windows\install_aster_windows.ps1 -UseWinget
```
## To build a Windows executable:
```
powershell -ExecutionPolicy Bypass -File .\packaging\windows\build_windows_exe.ps1
```
## Then use:

```
packaging/windows/AsterBrowser.nsi
```

## with NSIS to make:

```
Aster-Browser-Setup.exe
```
## Useful environment variables

```bash
export ASTER_PORTABLE=1
export ASTER_OPENAI_API_KEY="your_key_here"
export ASTER_AI_PROVIDER="openai"
export ASTER_AI_MODEL="gpt-5.4-mini"
export ASTER_AI_BASE_URL="https://api.openai.com/v1"
export ASTER_MAX_LIVE_TABS="4"
export ASTER_SOFT_MEMORY_BUDGET_MB="900"
export QTWEBENGINE_CHROMIUM_FLAGS="--enable-gpu-rasterization --enable-zero-copy --ignore-gpu-blocklist"
```

## Keyboard shortcuts

- `Ctrl+L` focus address bar
- `Ctrl+T` new tab
- `Ctrl+W` close current tab
- `Ctrl+Shift+L` open current page in Lite mode
- `Ctrl+Shift+P` park background tabs
- `Ctrl+,` open settings

## AI side panel commands

- `/help`
- `/open https://example.com`
- `/search linux network engineer`
- `/park`
- `/lite`
- `/container media`
- `/stats`
- `/wg`

## Project layout

```text
aster_browser/        Main application code
plugins/              Bundled example plugins
rules/                Built-in block rules
tests/                Non-GUI tests
packaging/            Flatpak and desktop files
docs/                 Install and setup docs
tools/                Portable install and helper scripts
validation/           Validation notes from generated build artifacts
```

## Run tests

```bash
python -m unittest discover -s tests -v
```

## Documentation

- [`docs/INSTALL-ARCH.md`](docs/INSTALL-ARCH.md)
- [`docs/INSTALL-STEAMDECK.md`](docs/INSTALL-STEAMDECK.md)
- [`docs/AI-SETUP.md`](docs/AI-SETUP.md)
- [`docs/ROADMAP.md`](docs/ROADMAP.md)
- [`docs/LICENSING-NOTES.md`](docs/LICENSING-NOTES.md)
- [`docs/GITHUB-SETUP.md`](docs/GITHUB-SETUP.md)

## Contributing

Contributions are welcome. Bug reports, UI improvements, memory tuning, plugin ideas, packaging help, and engine experiments are especially useful right now.

Start here:

- [`CONTRIBUTING.md`](CONTRIBUTING.md)
- [`SECURITY.md`](SECURITY.md)
- issue templates in [`.github/ISSUE_TEMPLATE`](.github/ISSUE_TEMPLATE)

## Roadmap summary

Near term:

- improve GUI polish
- improve parked-tab restore behavior
- make memory rules more adaptive
- add a proper settings screen for AI and profile options
- improve Steam Deck packaging

Long term:

- deepen the independent engine path beyond Aster Lite
- improve media handling and Linux packaging quality
- sandbox plugins more aggressively
- explore stronger site isolation and process splitting

## License

This project is licensed under the **MIT License**. That means people can use it, copy it, modify it, publish it, and share it.

See [`LICENSE`](LICENSE).

> Important: this repository's source code is MIT-licensed, but some dependencies and optional runtime components have their own licenses and distribution rules. See [`docs/LICENSING-NOTES.md`](docs/LICENSING-NOTES.md).
