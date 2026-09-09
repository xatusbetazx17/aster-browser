# Download and update Aster Preview

Open [Latest Codex preview](https://github.com/xatusbetazx17/aster-browser/releases/tag/codex-preview). Choose your device in the download table. Release downloads do not have the 14-day expiry of Actions artifacts, and do not require a GitHub account. You do not need **Code → Download ZIP** or a source checkout to install Aster.

These are experimental builds of Aster's own engine, with the browsing and reading changes on `codex/aster-webkit-desktop`. Each release identifies its source commit and includes SHA-256 checksums and test reports.

## Windows 10/11, 64-bit Intel or AMD

1. Download **aster-windows-x64-setup.exe** from the release.
2. Close Aster if it is running, then open the setup file and follow its instructions.
3. Open **Aster Preview** from the Start menu.

To update, download and run the newer setup file. Setup recognizes the same application, replaces its files and keeps its bookmarks, notes, settings and saved sessions. It installs for your Windows account and includes Java. The installer is unsigned, so Windows may show an unknown-publisher warning. Windows video/HLS is still a known limitation of this preview.

If you previously used the portable original-engine ZIP, setup uses the same per-user profile. Open the Start-menu application after setup; a shortcut pointing to an old extracted EXE still opens the old version. You can remove that old program folder once you have verified the new app. Older Qt/Chromium or WebKit editions have different profiles and are not automatically converted.

The optional **aster-windows-x64-portable.zip** is for people who prefer a folder they can extract and run. Extract the whole ZIP. The setup EXE is the easiest choice for routine updates.

## Linux with Flatpak, x86_64

Download **aster-linux-x64.flatpak**. Open it with a software manager that supports Flatpak, or run this command from the downloaded file's folder:

```sh
flatpak install --user --or-update ./aster-linux-x64.flatpak
flatpak run io.aster.browser.EnginePreview
```

Use the same command for later downloaded bundles. Flatpak replaces the application and keeps its saved profile. Close Aster first. The initial installation needs Flatpak and an internet connection for the Freedesktop runtime. This bundle uses the same application ID and `preview` branch as earlier original-engine Flatpak builds.

Flatpak supports many desktop Linux distributions and immutable systems without changing the read-only OS. Aster still needs X11 or XWayland, graphics/audio support and compatible hardware. This is not a claim of every Linux distribution or CPU being tested; desktop ARM, musl-native and headless builds are not provided. Physical Steam Deck verification remains outstanding.

The optional **aster-linux-x64.tar.gz** is a native portable bundle built on Ubuntu 22.04. Its profile is separate from the Flatpak profile. For managed replacements, use the Flatpak package. Source-based `installers/install-linux.sh` belongs to the older WebKit experiment.

## Android 8.0 or later

The release table states whether Android has persistent signing configured:

- **aster-android-8-plus.apk** is the update channel. Open it, allow installation from that download source if Android asks, and choose **Install** or **Update**. New releases keep the same private signing identity and increase the version code, preserving app data.
- **aster-android-8-plus-test.apk** is available for fresh test installations while the owner configures signing. In-place updates from other CI runs are not supported for these disposable-key builds. Do not uninstall an existing app containing notes or bookmarks you need.

An APK signed with a different key cannot replace an installed application. Earlier Actions APKs used a different key on each run; configuring release signing cannot retroactively fix those keys. An export/migration path for those older installations remains unfinished.

Minimum Android 8 / API 26 is exercised in the emulator. The APK has no CPU-specific native engine library; newer Android versions and physical ARM devices still need testing.

## What an update does

Updates replace Aster's application files after you download and run the newer package. They do not run silently in the background. Saved downloads outside the app stay in place. Each versioned release remains available for reference; profile compatibility with downgrades is not promised.

The preview is for testing. Full website compatibility, account sign-in and protected streaming are unfinished. See [features and known limitations](experiments/aster-engine/RELEASE_0.3.md).

## One-time Android setup for the repository owner

Regular users do not perform this step. The owner needs a persistent private signing key before Android can provide dependable updates. The GitHub connector used for this work cannot create Actions secrets.

On the owner's computer, install a JDK and GitHub CLI, authenticate with `gh auth login`, and run this from the Codex branch checkout:

```sh
python experiments/aster-engine/packaging/configure_android_signing.py --create --keystore ../aster-private-backup/aster-preview.keystore
```

The helper asks for a password, creates the private key outside the repository, and uses GitHub CLI to encrypt and save the single **ASTER_ANDROID_SIGNING_JSON** Actions secret. It refuses to overwrite an existing signing secret. Keep the keystore and password in a secure backup. Never upload the private key to repository files, release assets, Actions artifacts, caches or this chat.

If you already have the intended release keystore, omit `--create` and supply its path and, if needed, `--alias`. Then run **Actions → Aster original engine preview → Run workflow** on `codex/aster-webkit-desktop`. The next passing package checks publish the signed APK. Publication refuses a later signing-key change or a non-increasing Android version code.

## How new preview releases appear

Changes under `experiments/aster-engine` pushed to the Codex branch trigger package builds and platform checks. Successful package gates publish a versioned prerelease and refresh **Latest Codex preview**. Pull requests cannot publish or access the private Android signing key. The release job alone has repository-content write access.

Windows browsing packages may be published with an explicit failed-media report when core browsing, native window and installer/update tests pass. The media failure remains visible in Actions. Other failed required package tests prevent publication. The README points to the preview channel because GitHub's generic `/releases/latest` link excludes prereleases.
