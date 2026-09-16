#!/usr/bin/env bash
# Install or update Aster's optional DRM capsule runtime.
#
# The capsule is CastLabs Electron for Content Security. It installs its own
# Widevine CDM from Google's component service on first launch. This script
# downloads that runtime onto this machine; it does not copy a CDM out of another
# browser, and Aster redistributes no DRM component.
set -euo pipefail

CAPSULE_DIR="${ASTER_CAPSULE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/packaging/electron-drm-capsule}"
ECS_VERSION="${ASTER_ECS_VERSION:-v42.0.0+wvcus}"
INSTALL_DEPS=0
ASSUME_YES=0
VERIFY=0

msg() { printf '[Aster DRM capsule] %s\n' "$*"; }
die() { printf '[Aster DRM capsule] %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-deps) INSTALL_DEPS=1; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    --verify) VERIFY=1; shift ;;
    --version) ECS_VERSION="${2:?--version needs a tag}"; shift 2 ;;
    --capsule-dir) CAPSULE_DIR="${2:?--capsule-dir needs a path}"; shift 2 ;;
    -h|--help)
      cat <<USAGE
Install or update the optional Aster DRM capsule.

  --install-deps   Install nodejs/npm with the system package manager
  -y, --yes        Do not prompt during package-manager installs
  --verify         After installing, check whether Widevine is really available
  --version TAG    CastLabs ECS release tag (default: $ECS_VERSION)
  --capsule-dir D  Capsule directory (default: experiments/webkit/packaging/electron-drm-capsule)

Without --install-deps this script never touches your package manager; it prints
the command to run and stops.
USAGE
      exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

dependency_hint() {
  if command -v apt-get >/dev/null 2>&1; then echo "sudo apt-get install nodejs npm"
  elif command -v dnf >/dev/null 2>&1; then echo "sudo dnf install nodejs npm"
  elif command -v pacman >/dev/null 2>&1; then echo "sudo pacman -S --needed nodejs npm"
  elif command -v zypper >/dev/null 2>&1; then echo "sudo zypper install nodejs npm"
  else echo "install nodejs and npm with your distribution's package manager"
  fi
}

install_dependencies() {
  local sudo_cmd=""
  if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    command -v sudo >/dev/null 2>&1 || die "sudo is not available. Run: $(dependency_hint)"
    sudo_cmd="sudo"
  fi
  local yes_flag=""
  if command -v apt-get >/dev/null 2>&1; then
    [[ $ASSUME_YES -eq 1 ]] && yes_flag="-y"
    $sudo_cmd apt-get update
    $sudo_cmd apt-get install $yes_flag nodejs npm
  elif command -v dnf >/dev/null 2>&1; then
    [[ $ASSUME_YES -eq 1 ]] && yes_flag="-y"
    $sudo_cmd dnf install $yes_flag nodejs npm
  elif command -v pacman >/dev/null 2>&1; then
    [[ $ASSUME_YES -eq 1 ]] && yes_flag="--noconfirm"
    $sudo_cmd pacman -S --needed $yes_flag nodejs npm
  elif command -v zypper >/dev/null 2>&1; then
    [[ $ASSUME_YES -eq 1 ]] && yes_flag="--non-interactive"
    $sudo_cmd zypper $yes_flag install nodejs npm
  else
    die "No supported package manager found. Run: $(dependency_hint)"
  fi
}

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  if [[ $INSTALL_DEPS -eq 1 ]]; then
    install_dependencies
  else
    die "node and npm are required. Run: $(dependency_hint), or rerun with --install-deps"
  fi
fi

[[ -f "$CAPSULE_DIR/main.js" ]] || die "Capsule source not found in $CAPSULE_DIR"
cd "$CAPSULE_DIR"

msg "Installing CastLabs Electron for Content Security $ECS_VERSION into $CAPSULE_DIR"
npm install "https://github.com/castlabs/electron-releases#$ECS_VERSION" --save-dev --no-audit --fund=false
npm run check

if [[ $VERIFY -eq 1 ]]; then
  msg "Checking whether the capsule really exposes Widevine. This downloads the CDM on first run."
  if npm run --silent verify; then
    msg "Widevine key-system access is available in the capsule."
  else
    die "The capsule installed but Widevine is not available in it. Protected services will not play."
  fi
fi

msg "Done. In Aster: companion panel, Play tab, 'Open this page in the DRM capsule'."
