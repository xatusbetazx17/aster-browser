#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${HOME}/.local/share/aster-browser-portable"
STATE_DIR="${INSTALL_ROOT}/state"
LAUNCHER="${INSTALL_ROOT}/aster-launch.sh"
WRAPPER="${INSTALL_ROOT}/aster-internal-streaming-launch.sh"
DESKTOP_FILE="${HOME}/.local/share/applications/org.aster.Browser.desktop"
BACKUP_DESKTOP_FILE="${DESKTOP_FILE}.bak-internal-streaming"
ASSUME_YES=0
INSTALL_RUNTIME=0
FORCE_INTERNAL=0

msg() { printf '[Aster Streaming Fix] %s\n' "$*"; }
die() { printf '[Aster Streaming Fix] %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
Try to keep premium streaming inside Aster by wiring Widevine into Qt WebEngine,
turning off auto capsule redirection, and creating an internal-streaming launcher.

Options:
  --install-runtime  Install Google Chrome Flatpak if Widevine is missing and flatpak is available
  --force-internal   Force Aster into internal_only mode even if Widevine is missing
  --yes              Non-interactive mode where safe
  --help             Show this help
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --install-runtime) INSTALL_RUNTIME=1; shift ;;
      --force-internal) FORCE_INTERNAL=1; shift ;;
      --yes|-y) ASSUME_YES=1; shift ;;
      --help|-h) usage; exit 0 ;;
      *) die "Unknown option: $1" ;;
    esac
  done
}

prompt_yes_no() {
  local prompt="$1"
  local default="$2"
  local answer=""
  if [[ $ASSUME_YES -eq 1 ]]; then
    return 0
  fi
  read -r -p "$prompt [$default]: " answer
  if [[ -z "$answer" ]]; then
    answer="$default"
  fi
  case "${answer,,}" in
    y|yes|1|true|on) return 0 ;;
    *) return 1 ;;
  esac
}

choose_python() {
  local candidate=""
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

find_widevine() {
  local candidates=(
    "${ASTER_WIDEVINE_PATH:-}"
    "${WIDEVINE_PATH:-}"
    "/opt/google/chrome/WidevineCdm/_platform_specific/linux_x64/libwidevinecdm.so"
    "/opt/google/chrome/libwidevinecdm.so"
    "/opt/google/chrome-beta/WidevineCdm/_platform_specific/linux_x64/libwidevinecdm.so"
    "/opt/google/chrome-unstable/WidevineCdm/_platform_specific/linux_x64/libwidevinecdm.so"
    "/usr/lib/chromium/libwidevinecdm.so"
    "/usr/lib/chromium-browser/libwidevinecdm.so"
    "/usr/lib64/chromium/libwidevinecdm.so"
    "${HOME}/.var/app/com.google.Chrome/config/google-chrome/WidevineCdm/_platform_specific/linux_x64/libwidevinecdm.so"
  )
  local item=""
  for item in "${candidates[@]}"; do
    [[ -n "$item" ]] || continue
    if [[ -f "$item" ]]; then
      printf '%s' "$item"
      return 0
    fi
  done
  return 1
}

try_install_runtime() {
  [[ $INSTALL_RUNTIME -eq 1 ]] || return 1
  command -v flatpak >/dev/null 2>&1 || return 1
  if ! prompt_yes_no "Widevine was not found. Install Google Chrome Flatpak as an internal DRM runtime" 'Y'; then
    return 1
  fi
  msg "Installing Google Chrome Flatpak..."
  flatpak install -y flathub com.google.Chrome
  return 0
}

update_aster_config() {
  local python_cmd="$(choose_python || true)"
  local config_file=""
  [[ -n "$python_cmd" ]] || die "Python is required to update Aster config."
  if [[ -d "$STATE_DIR" ]]; then
    config_file="${STATE_DIR}/config/config.json"
  else
    config_file="${INSTALL_ROOT}/state/config/config.json"
  fi
  mkdir -p "$(dirname "$config_file")"
  "$python_cmd" - "$config_file" <<'PY'
import json, sys
from pathlib import Path
config_path = Path(sys.argv[1])
defaults = {
    "homepage": "aster:newtab",
    "search_engine": "https://duckduckgo.com/?q={query}",
    "adblock_enabled": True,
    "strict_adblock": False,
    "security_mode": "balanced",
    "max_live_tabs": 6,
    "soft_memory_budget_mb": 1200,
    "auto_park": True,
    "default_container": "default",
    "hardware_acceleration": True,
    "containers": ["default", "work", "media", "banking"],
    "drm_mode": "internal_only",
    "external_browser_cmd": "",
    "streaming_capsules": False,
    "streaming_capsule_auto_launch": False,
    "streaming_capsule_app_mode": False,
    "streaming_capsule_persistent_profile": True,
    "streaming_capsule_prefer_flatpak": True,
}
payload = dict(defaults)
if config_path.exists():
    try:
        current = json.loads(config_path.read_text(encoding='utf-8'))
        if isinstance(current, dict):
            payload.update(current)
    except Exception:
        pass
payload['drm_mode'] = 'internal_only'
payload['streaming_capsules'] = False
payload['streaming_capsule_auto_launch'] = False
for key, value in defaults.items():
    payload.setdefault(key, value)
config_path.parent.mkdir(parents=True, exist_ok=True)
config_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding='utf-8')
print(config_path)
PY
}

create_wrapper() {
  local widevine_path="$1"
  [[ -f "$LAUNCHER" ]] || return 0
  cat > "$WRAPPER" <<EOF2
#!/usr/bin/env bash
set -euo pipefail
export ASTER_DRM_MODE='internal_only'
export ASTER_STREAMING_CAPSULES='0'
export ASTER_STREAMING_CAPSULE_AUTO_LAUNCH='0'
export ASTER_WIDEVINE_PATH='${widevine_path}'
exec '${LAUNCHER}' "\$@"
EOF2
  chmod 700 "$WRAPPER"
}

update_desktop_file() {
  [[ -f "$WRAPPER" ]] || return 0
  [[ -f "$DESKTOP_FILE" ]] || return 0
  cp "$DESKTOP_FILE" "$BACKUP_DESKTOP_FILE"
  awk -v wrapper="$WRAPPER" '
    BEGIN {changed=0}
    /^Exec=/ && changed==0 {print "Exec=" wrapper " %U"; changed=1; next}
    {print}
  ' "$BACKUP_DESKTOP_FILE" > "$DESKTOP_FILE"
}

main() {
  parse_args "$@"
  local widevine="$(find_widevine || true)"
  if [[ -z "$widevine" ]]; then
    try_install_runtime || true
    widevine="$(find_widevine || true)"
  fi
  if [[ -z "$widevine" && $FORCE_INTERNAL -ne 1 ]]; then
    msg "Widevine was not found."
    msg "I did not force internal_only mode because premium streaming usually still fails without Widevine."
    msg "Run this again with --install-runtime or --force-internal if you still want to try."
    exit 1
  fi
  if [[ -n "$widevine" ]]; then
    msg "Using Widevine at: ${widevine}"
  else
    msg "Forcing internal mode without a detected Widevine path."
  fi
  update_aster_config
  if [[ -n "$widevine" ]]; then
    create_wrapper "$widevine"
    update_desktop_file
  fi
  msg "Done."
  msg "Aster is now configured to try internal streaming instead of launching capsules."
  [[ -f "$WRAPPER" ]] && msg "Use this launcher if needed: ${WRAPPER}"
}

main "$@"
