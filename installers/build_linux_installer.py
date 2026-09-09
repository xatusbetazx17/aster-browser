"""Generate the single standalone install-linux.sh for Aster Browser v16."""
import base64
import io
import os
import tarfile
import zipfile

SCRIPT_HEADER = r"""#!/usr/bin/env bash
set -euo pipefail

# Aster Browser - Unified Linux Installer (Single File)
# Installs Aster Browser, sets up private runtime, icons, and desktop shortcuts.

APP_ID="org.aster.Browser"
APP_NAME="Aster Browser"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APPS_HOME="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
INSTALL_ROOT="$DATA_HOME/aster-browser-portable"
INSTALL_DIR="$INSTALL_ROOT/app"
RUNTIME_DIR="$INSTALL_ROOT/runtime"
STATE_DIR="$INSTALL_ROOT/state"
LAUNCHER="$INSTALL_ROOT/aster-launch.sh"
DESKTOP_FILE="$APPS_HOME/$APP_ID.desktop"
ICON_DEST="$DATA_HOME/icons/hicolor/scalable/apps/$APP_ID.svg"
ASSUME_YES=0
NO_SYSTEM_DEPS=0
EXTRACT_ONLY=""
RUN_AFTER_INSTALL=0
REFRESH_CA=1
RESET_NETWORK_STATE=0
WITH_DRM_CAPSULE=0
SKIP_NSS_DB_REFRESH=1

msg() { printf '[Aster Installer] %s\\n' "$*"; }
die() { printf '[Aster Installer] ERROR: %s\\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
Aster Browser - Linux Installer

Usage:
  bash install-linux.sh [OPTIONS]

Options:
  --install-root DIR     Install into DIR instead of ~/.local/share/aster-browser-portable
  --no-system-deps      Do not invoke distribution package managers
  --extract-only DIR    Extract the bundled app source into DIR and exit
  --run                 Start Aster Browser immediately after installation
  --refresh-ca          Refresh system CA trust (default: enabled)
  --no-refresh-ca       Skip system CA trust refresh
  --reset-network-state Clear Aster WebEngine cache/security state after install
  --with-drm-capsule    Install optional Chromium DRM capsule for streaming
  --uninstall           Remove the portable install and desktop entries
  -y, --yes             Non-interactive mode (auto-accept prompts)
  -h, --help            Show this help message
USAGE
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --install-root)
        [[ $# -ge 2 ]] || die "Missing value for --install-root"
        INSTALL_ROOT="$2"
        INSTALL_DIR="$INSTALL_ROOT/app"
        RUNTIME_DIR="$INSTALL_ROOT/runtime"
        STATE_DIR="$INSTALL_ROOT/state"
        LAUNCHER="$INSTALL_ROOT/aster-launch.sh"
        shift 2
        ;;
      --no-system-deps) NO_SYSTEM_DEPS=1; shift ;;
      --extract-only)
        [[ $# -ge 2 ]] || die "Missing value for --extract-only"
        EXTRACT_ONLY="$2"; shift 2 ;;
      --run) RUN_AFTER_INSTALL=1; shift ;;
      --refresh-ca) REFRESH_CA=1; shift ;;
      --no-refresh-ca) REFRESH_CA=0; shift ;;
      --reset-network-state) RESET_NETWORK_STATE=1; shift ;;
      --with-drm-capsule) WITH_DRM_CAPSULE=1; shift ;;
      --uninstall) uninstall; exit 0 ;;
      -y|--yes) ASSUME_YES=1; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "Unknown option: $1 (use --help for options)" ;;
    esac
  done
}

payload_line() {
  awk '/^__ASTER_PAYLOAD_BELOW__$/ {print NR + 1; exit 0}' "$0"
}

extract_payload() {
  local target="$1"
  local line
  line="$(payload_line)"
  [[ -n "$line" ]] || die "Installer payload was not found."
  mkdir -p "$target"
  tail -n +"$line" "$0" | base64 -d | tar -xz -C "$target"
}

choose_sudo() {
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    printf ''
  elif command -v sudo >/dev/null 2>&1; then
    printf 'sudo'
  else
    return 1
  fi
}

apt_installed() { dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed'; }
rpm_installed() { rpm -q "$1" >/dev/null 2>&1; }
pacman_installed() { pacman -Qq "$1" >/dev/null 2>&1; }
zypper_installed() { rpm -q "$1" >/dev/null 2>&1; }

install_missing_with() {
  local manager="$1"; shift
  local -a packages=("$@") missing=()
  local pkg=""
  for pkg in "${packages[@]}"; do
    case "$manager" in
      apt) apt_installed "$pkg" || missing+=("$pkg") ;;
      dnf|yum) rpm_installed "$pkg" || missing+=("$pkg") ;;
      pacman) pacman_installed "$pkg" || missing+=("$pkg") ;;
      zypper) zypper_installed "$pkg" || missing+=("$pkg") ;;
    esac
  done
  [[ ${#missing[@]} -eq 0 ]] && return 0
  local sudo_cmd=""
  sudo_cmd="$(choose_sudo || true)"
  [[ "${EUID:-$(id -u)}" -eq 0 || -n "$sudo_cmd" ]] || {
    msg "Missing packages: ${missing[*]}"
    msg "Install them manually or rerun with sudo available."
    return 0
  }
  msg "Installing missing system packages: ${missing[*]}"
  case "$manager" in
    apt)
      $sudo_cmd apt-get update
      if [[ $ASSUME_YES -eq 1 ]]; then
        $sudo_cmd apt-get install -y "${missing[@]}"
      else
        $sudo_cmd apt-get install "${missing[@]}"
      fi
      ;;
    dnf)
      if [[ $ASSUME_YES -eq 1 ]]; then
        $sudo_cmd dnf install -y "${missing[@]}"
      else
        $sudo_cmd dnf install "${missing[@]}"
      fi
      ;;
    yum)
      if [[ $ASSUME_YES -eq 1 ]]; then
        $sudo_cmd yum install -y "${missing[@]}"
      else
        $sudo_cmd yum install "${missing[@]}"
      fi
      ;;
    pacman)
      if [[ $ASSUME_YES -eq 1 ]]; then
        $sudo_cmd pacman -S --needed --noconfirm "${missing[@]}"
      else
        $sudo_cmd pacman -S --needed "${missing[@]}"
      fi
      ;;
    zypper)
      if [[ $ASSUME_YES -eq 1 ]]; then
        $sudo_cmd zypper --non-interactive install "${missing[@]}"
      else
        $sudo_cmd zypper install "${missing[@]}"
      fi
      ;;
  esac
}

ensure_system_deps() {
  [[ $NO_SYSTEM_DEPS -eq 1 ]] && { msg "Skipping system dependency installation."; return 0; }
  if command -v apt-get >/dev/null 2>&1; then
    install_missing_with apt python3 python3-venv python3-pip libxcb-cursor0 libxkbcommon-x11-0 libnss3 libxcomposite1 libxdamage1 libxrandr2 libegl1 libgl1 libopengl0 libfontconfig1 libdbus-1-3 libpulse0 ca-certificates openssl p11-kit
  elif command -v dnf >/dev/null 2>&1; then
    install_missing_with dnf python3 python3-pip python3-virtualenv xcb-util-cursor libxkbcommon-x11 nss libXcomposite libXdamage libXrandr mesa-libEGL mesa-libGL libglvnd-opengl fontconfig dbus-libs pulseaudio-libs ca-certificates openssl p11-kit
  elif command -v yum >/dev/null 2>&1; then
    install_missing_with yum python3 python3-pip python3-virtualenv xcb-util-cursor libxkbcommon-x11 nss libXcomposite libXdamage libXrandr mesa-libEGL mesa-libGL fontconfig dbus-libs pulseaudio-libs ca-certificates openssl p11-kit
  elif command -v pacman >/dev/null 2>&1; then
    install_missing_with pacman python python-pip python-virtualenv xcb-util-cursor libxkbcommon-x11 nss libxcomposite libxdamage libxrandr mesa fontconfig dbus libpulse ca-certificates openssl p11-kit qt6-wayland
  elif command -v zypper >/dev/null 2>&1; then
    install_missing_with zypper python3 python3-pip python3-virtualenv xcb-util-cursor libxkbcommon-x11-0 mozilla-nss libXcomposite1 libXdamage1 libXrandr2 Mesa-libEGL1 Mesa-libGL1 fontconfig dbus-1 libpulse0 ca-certificates openssl p11-kit
  else
    msg "No supported package manager found. Continuing with python runtime check."
  fi
}

choose_python() {
  local candidate=""
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      if "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
        printf '%s' "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

install_app_files() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  msg "Extracting Aster Browser application files..."
  extract_payload "$tmp"
  mkdir -p "$INSTALL_ROOT" "$STATE_DIR"
  if [[ -d "$INSTALL_DIR" ]]; then
    local backup="$INSTALL_ROOT/app.backup.$(date +%Y%m%d-%H%M%S)"
    msg "Existing app found. Backing up to $backup"
    mv "$INSTALL_DIR" "$backup"
  fi
  mkdir -p "$INSTALL_DIR"
  cp -a "$tmp"/. "$INSTALL_DIR"/
}

refresh_system_ca_trust() {
  [[ $REFRESH_CA -eq 1 ]] || return 0
  local sudo_cmd=""
  sudo_cmd="$(choose_sudo || true)"
  if [[ "${EUID:-$(id -u)}" -ne 0 && -z "$sudo_cmd" ]]; then
    return 0
  fi
  if [[ -x "$INSTALL_DIR/tools/refresh_ca_trust.sh" ]]; then
    "$INSTALL_DIR/tools/refresh_ca_trust.sh" --auto || true
  elif command -v update-ca-certificates >/dev/null 2>&1; then
    $sudo_cmd update-ca-certificates >/dev/null 2>&1 || true
  elif command -v update-ca-trust >/dev/null 2>&1; then
    $sudo_cmd update-ca-trust extract >/dev/null 2>&1 || true
  fi
}

install_runtime() {
  local python_cmd="$1"
  if [[ ! -x "$RUNTIME_DIR/bin/python" ]]; then
    msg "Creating private Python runtime environment at $RUNTIME_DIR..."
    "$python_cmd" -m venv "$RUNTIME_DIR" || die "Failed to create Python virtual environment. Please install python3-venv."
  fi
  msg "Installing/verifying Python package requirements..."
  "$RUNTIME_DIR/bin/python" -m ensurepip --upgrade >/dev/null 2>&1 || true
  "$RUNTIME_DIR/bin/python" -m pip install --upgrade pip wheel setuptools >/dev/null 2>&1 || true
  if [[ -f "$INSTALL_DIR/requirements-lock.txt" ]]; then
    "$RUNTIME_DIR/bin/python" -m pip install -r "$INSTALL_DIR/requirements-lock.txt"
  elif [[ -f "$INSTALL_DIR/requirements.txt" ]]; then
    "$RUNTIME_DIR/bin/python" -m pip install -r "$INSTALL_DIR/requirements.txt"
  fi
}

install_launcher() {
  mkdir -p "$INSTALL_ROOT"
  cat > "$LAUNCHER" <<EOF2
#!/usr/bin/env bash
set -euo pipefail
export ASTER_PORTABLE=1
export ASTER_PORTABLE_ROOT="$STATE_DIR"
mkdir -p "$STATE_DIR"
export NSS_DEFAULT_DB_TYPE="\${NSS_DEFAULT_DB_TYPE:-sql}"

if [[ -z "\${SSL_CERT_FILE:-}" ]]; then
  for ca_file in /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/ssl/ca-bundle.pem /var/lib/ca-certificates/ca-bundle.pem /etc/ssl/cert.pem; do
    if [[ -f "\$ca_file" ]]; then export SSL_CERT_FILE="\$ca_file" REQUESTS_CA_BUNDLE="\$ca_file" CURL_CA_BUNDLE="\$ca_file"; break; fi
  done
fi
if [[ -z "\${SSL_CERT_DIR:-}" ]]; then
  for ca_dir in /etc/ssl/certs /etc/pki/tls/certs /etc/pki/ca-trust/extracted/pem; do
    if [[ -d "\$ca_dir" ]]; then export SSL_CERT_DIR="\$ca_dir"; break; fi
  done
fi

export QTWEBENGINE_CHROMIUM_FLAGS="\${QTWEBENGINE_CHROMIUM_FLAGS:-} --ozone-platform-hint=auto"
cd "$INSTALL_DIR"
exec "$RUNTIME_DIR/bin/python" "$INSTALL_DIR/run_aster.py" "\$@"
EOF2
  chmod +x "$LAUNCHER"
}

reset_network_state() {
  [[ $RESET_NETWORK_STATE -eq 1 ]] || return 0
  msg "Resetting Aster network state..."
  if [[ -x "$INSTALL_DIR/tools/reset_aster_network_state.sh" ]]; then
    ASTER_INSTALL_ROOT="$INSTALL_ROOT" ASTER_PORTABLE_ROOT="$STATE_DIR" "$INSTALL_DIR/tools/reset_aster_network_state.sh" || true
  fi
}

install_drm_capsule() {
  [[ $WITH_DRM_CAPSULE -eq 1 ]] || return 0
  msg "Configuring Chromium DRM capsule..."
  if [[ -x "$INSTALL_DIR/tools/setup_chromium_drm_capsule.sh" ]]; then
    if [[ $ASSUME_YES -eq 1 ]]; then
      "$INSTALL_DIR/tools/setup_chromium_drm_capsule.sh" -y || msg "DRM capsule configuration finished with warnings."
    else
      "$INSTALL_DIR/tools/setup_chromium_drm_capsule.sh" || msg "DRM capsule configuration finished with warnings."
    fi
  fi
}

install_desktop_entry() {
  mkdir -p "$APPS_HOME" "$(dirname "$ICON_DEST")"
  local icon_source="$INSTALL_DIR/packaging/linux/aster.svg"
  if [[ ! -f "$icon_source" ]]; then
    icon_source="$INSTALL_DIR/aster_browser/assets/icons/aster_logo.svg"
  fi
  if [[ -f "$icon_source" ]]; then
    cp "$icon_source" "$ICON_DEST"
  fi
  cat > "$DESKTOP_FILE" <<EOF2
[Desktop Entry]
Type=Application
Name=Aster Browser
GenericName=Web Browser
Comment=Lightweight, Privacy-First Modern Browser
Exec=$LAUNCHER %U
Icon=$APP_ID
Categories=Network;WebBrowser;
MimeType=text/html;text/xml;application/xhtml+xml;x-scheme-handler/http;x-scheme-handler/https;
Terminal=false
StartupNotify=true
EOF2
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS_HOME" >/dev/null 2>&1 || true
}

uninstall() {
  msg "Removing Aster Browser from $INSTALL_ROOT..."
  rm -rf "$INSTALL_ROOT"
  rm -f "$DESKTOP_FILE" "$ICON_DEST"
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS_HOME" >/dev/null 2>&1 || true
  msg "Aster Browser has been uninstalled successfully. User data in ~/.config/aster-browser was preserved."
}

main() {
  parse_args "$@"
  if [[ -n "$EXTRACT_ONLY" ]]; then
    extract_payload "$EXTRACT_ONLY"
    msg "Extracted application source to $EXTRACT_ONLY"
    exit 0
  fi
  msg "=== Starting Aster Browser Installation ==="
  ensure_system_deps
  local python_cmd=""
  python_cmd="$(choose_python || true)"
  [[ -n "$python_cmd" ]] || die "Python 3.10+ is required and was not found."
  install_app_files
  refresh_system_ca_trust
  install_runtime "$python_cmd"
  reset_network_state
  install_drm_capsule
  install_launcher
  install_desktop_entry
  msg "=== Aster Browser Installation Complete ==="
  msg "Executable Launcher: $LAUNCHER"
  msg "Desktop Entry:       $DESKTOP_FILE"
  msg "Run 'bash $LAUNCHER' to launch, or open 'Aster Browser' from your applications menu."
  if [[ $RUN_AFTER_INSTALL -eq 1 ]]; then
    exec "$LAUNCHER"
  fi
}

main "$@"
exit 0
__ASTER_PAYLOAD_BELOW__
"""

def generate():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    source_zip = os.path.join(root_dir, "Aster-Browser-Windows-Kit-v16.zip")
    output_sh = os.path.join(root_dir, "install-linux.sh")
    
    print(f"Reading files from {source_zip}...")
    prefix = "aster-browser-windows-kit/"
    
    tar_buf = io.BytesIO()
    with tarfile.open(fileobj=tar_buf, mode="w:gz") as tar:
        with zipfile.ZipFile(source_zip, "r") as z:
            for item in z.infolist():
                if item.filename.startswith(prefix):
                    rel_name = item.filename[len(prefix):]
                    if not rel_name or rel_name.endswith("/"):
                        continue
                    if rel_name.startswith("tools/legacy-update-scripts/"):
                        continue
                    data = z.read(item.filename)
                    ti = tarfile.TarInfo(name="./" + rel_name)
                    ti.size = len(data)
                    ti.mtime = int(item.date_time[0]) if len(item.date_time) > 0 else 0
                    ti.mode = 0o755 if rel_name.endswith((".sh", ".py")) else 0o644
                    tar.addfile(ti, io.BytesIO(data))
                    
    compressed_bytes = tar_buf.getvalue()
    b64_payload = base64.b64encode(compressed_bytes).decode("ascii")
    
    # Break into 76-character lines
    lines = [b64_payload[i:i+76] for i in range(0, len(b64_payload), 76)]
    payload_str = "\n".join(lines) + "\n"
    
    # Write with LF newlines for clean Linux execution
    content = SCRIPT_HEADER.replace("\r\n", "\n") + payload_str
    with open(output_sh, "wb") as f:
        f.write(content.encode("utf-8"))
        
    print(f"Generated {output_sh} ({os.path.getsize(output_sh):,} bytes)")

if __name__ == "__main__":
    generate()
