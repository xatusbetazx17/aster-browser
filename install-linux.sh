#!/usr/bin/env bash
# One-command installation for the current original-engine Codex preview.
# Adapted from new-development's single-entry installer; Flatpak owns application
# replacement and removal, and browser profiles stay outside the package.
set -euo pipefail

aster_app='io.aster.browser.EnginePreview'
aster_ref="app/$aster_app/x86_64/preview"
aster_asset='aster-linux-x64.flatpak'
aster_release='https://github.com/xatusbetazx17/aster-browser/releases/download/codex-preview'
aster_bundle=''
aster_checksums=''
aster_action='install'
aster_run=0
aster_yes=0

aster_fail() { printf 'Aster setup: %s\n' "$*" >&2; exit 1; }
aster_help() {
  cat <<'HELP'
Aster Preview — install or update the original engine for Linux x64

Usage: bash install-linux.sh [OPTIONS]
  --run                  Open Aster after a successful install/update
  -y, --yes              Accept Flatpak's installation/removal prompts
  --check                Show this user's installed preview without downloading
  --uninstall            Remove this user's application; keep its saved data
  --bundle FILE          Use a previously downloaded Flatpak bundle
  --checksums FILE       SHA256SUMS.txt from the same release (required with --bundle)
  -h, --help             Show this help

Run as your normal desktop account. Requires Flatpak, sha256sum and curl or wget
(no downloader needed with --bundle). Flatpak must already be installed.
The same command installs updates. Close Aster first. The installer verifies the
package checksum before asking Flatpak to replace the application. It uses a
per-user installation, including on immutable desktops with Flatpak support.
The initial install may need an internet connection for the shared runtime.
HELP
}

while (($#)); do
  case "$1" in
    --run) aster_run=1 ;;
    -y|--yes) aster_yes=1 ;;
    --check|--uninstall)
      [[ "$aster_action" == install ]] || aster_fail 'Choose only one of --check or --uninstall.'
      aster_action="${1#--}" ;;
    --bundle|--checksums)
      (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || aster_fail "$1 requires a file path."
      if [[ "$1" == --bundle ]]; then aster_bundle="$2"; else aster_checksums="$2"; fi
      shift ;;
    -h|--help) aster_help; exit 0 ;;
    *) aster_fail "Unknown option: $1. Use --help." ;;
  esac
  shift
done

if [[ "$aster_action" != install ]]; then
  [[ "$aster_run" == 0 && -z "$aster_bundle" && -z "$aster_checksums" ]] || aster_fail 'Download and launch options require installation.'
elif [[ -n "$aster_bundle" || -n "$aster_checksums" ]]; then
  [[ -n "$aster_bundle" && -n "$aster_checksums" ]] || aster_fail 'Use --bundle and --checksums together.'
  [[ -f "$aster_bundle" && -r "$aster_bundle" && -f "$aster_checksums" && -r "$aster_checksums" ]] || aster_fail 'The bundle and checksum file must be readable files.'
fi
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || aster_fail 'This release supports Linux x86_64. No application was changed.'
command -v flatpak >/dev/null 2>&1 || aster_fail 'Install Flatpak using your distribution’s software manager, then run setup again.'
aster_options=()
if ((aster_yes)); then aster_options=(-y --noninteractive); fi

if [[ "$aster_action" == check ]]; then
  if ! flatpak info --user "$aster_ref"; then
    printf 'Aster Preview is not installed for this user.\n'
  fi
  exit 0
fi
if [[ "$aster_action" == uninstall ]]; then
  if flatpak info --user "$aster_ref" >/dev/null 2>&1; then
    flatpak uninstall --user "${aster_options[@]}" "$aster_ref"
    printf 'Aster Preview removed. Your saved profile and downloaded files were kept.\n'
  else
    printf 'Aster Preview is not installed for this user.\n'
  fi
  exit 0
fi

command -v sha256sum >/dev/null 2>&1 || aster_fail 'Install sha256sum (coreutils) using your software manager.'
aster_tmp=$(mktemp -d -t aster-install.XXXXXXXX)
trap 'rm -rf -- "$aster_tmp"' EXIT

aster_download() {
  local aster_url="$1" aster_output="$2"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --proto '=https' --proto-redir '=https' \
      --connect-timeout 20 --max-time 600 --retry 2 --output "$aster_output" "$aster_url"
  elif command -v wget >/dev/null 2>&1; then
    wget --https-only --timeout=20 --tries=2 --max-redirect=10 -O "$aster_output" "$aster_url"
  else
    aster_fail 'Install curl or wget, or use --bundle and --checksums for downloaded files.'
  fi
}

if [[ -n "$aster_bundle" ]]; then
  cp -- "$aster_bundle" "$aster_tmp/$aster_asset"
  cp -- "$aster_checksums" "$aster_tmp/SHA256SUMS.txt"
else
  printf 'Downloading the latest published Codex preview…\n'
  aster_download "$aster_release/SHA256SUMS.txt" "$aster_tmp/SHA256SUMS.txt"
  aster_download "$aster_release/$aster_asset" "$aster_tmp/$aster_asset"
fi
aster_expected=$(awk '$2 == "aster-linux-x64.flatpak" || $2 == "*aster-linux-x64.flatpak" { print $1 }' "$aster_tmp/SHA256SUMS.txt")
[[ "$aster_expected" =~ ^[[:xdigit:]]{64}$ ]] || aster_fail 'The manifest must contain exactly one SHA-256 for the Linux Flatpak.'
aster_actual=$(sha256sum -- "$aster_tmp/$aster_asset")
aster_actual="${aster_actual%% *}"
[[ "$aster_actual" == "${aster_expected,,}" ]] || aster_fail 'Checksum mismatch. No installation was attempted. Download the bundle and checksums from the same release and try again.'
printf 'Checksum verified. Installing/updating Aster Preview for this user…\n'
flatpak install --user --or-update "${aster_options[@]}" "$aster_tmp/$aster_asset"
flatpak info --user "$aster_ref" >/dev/null
rm -rf -- "$aster_tmp"
trap - EXIT
printf 'Aster Preview is ready. Open Aster from your application menu.\n'
if ((aster_run)); then
  exec flatpak run --user "$aster_ref"
fi
