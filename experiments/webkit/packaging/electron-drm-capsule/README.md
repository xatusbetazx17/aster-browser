# Aster DRM capsule

Aster's own rendering path cannot play protected video. The distribution
WebKitGTK builds this prototype runs on compile encrypted media out, so no Aster
tab can hold a Widevine session regardless of which setting is enabled. This
capsule is the honest workaround: **one protected service, opened in a separate
runtime that carries its own licensed CDM, in its own profile directory.**

The runtime is [CastLabs Electron for Content Security](https://github.com/castlabs/electron-releases).
It installs its own Widevine CDM from Google's component service on first launch.

## What this is not

- It does **not** bypass DRM, paywalls, age checks or region checks.
- It does **not** bundle, copy or redistribute a CDM. Nothing licensed is in this
  repository; `node_modules/` is downloaded onto your machine and is git-ignored.
- It does **not** copy a CDM out of Chrome, spoof a user agent or disable the sandbox.
- A capsule that launches is **not** proof a service will play. Services decide
  that themselves, and may require production VMP signing, supported codecs, or a
  runtime they have approved.

## Install

From the repository root:

```bash
bash experiments/webkit/tools/setup_chromium_drm_capsule.sh
```

Add `--install-deps` to let it install `nodejs`/`npm`, and `--verify` to check
whether Widevine really ended up available:

```bash
bash experiments/webkit/tools/setup_chromium_drm_capsule.sh --install-deps --verify
```

## Verify separately

```bash
cd experiments/webkit/packaging/electron-drm-capsule
npm run check    # is the runtime installed?
npm run verify   # does it actually expose a Widevine key system?
```

`npm run verify` loads a local page, requests `com.widevine.alpha` key-system
access, creates media keys, prints the result as JSON and exits non-zero when
Widevine is unavailable. This is the check that distinguishes "Electron
installed" from "DRM works".

## Use

In Aster: **companion panel → Play → Open this page in the DRM capsule**, or the
Aster menu entry of the same name.

Each service gets its own profile under Aster's data directory, so resetting one
service's capsule leaves the others signed in.

## Linux limitations

CastLabs documents full support on Windows and macOS and partial support on
Linux, where persistent licences are unavailable because VMP is not supported on
the platform. Expect L3 (SD) playback where a service allows it at all.

## Manual launch

```bash
npx electron . -- --aster-url=https://example.com --aster-service=Example \
  --aster-profile=/path/to/profile
```

Only `http` and `https` addresses are accepted.
