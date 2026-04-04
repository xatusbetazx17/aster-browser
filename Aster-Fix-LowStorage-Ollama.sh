#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${HOME}/.local/share/aster-browser-portable"
STATE_DIR="${INSTALL_ROOT}/state"
LAUNCHER="${INSTALL_ROOT}/aster-launch.sh"
AI_WRAPPER="${INSTALL_ROOT}/aster-localai-launch.sh"
DEFAULT_BASE_URL="http://127.0.0.1:11434"
DEFAULT_MODEL="qwen3:1.7b"
SMALL_MODEL="gemma3:1b"
EXISTING_MODEL_DIR="/usr/share/ollama/.ollama/models"
ASSUME_YES=0
MOVE_EXISTING=0
NO_PULL=0
MODEL="$DEFAULT_MODEL"
BASE_URL="$DEFAULT_BASE_URL"
MODELS_DIR=""
KV_CACHE_TYPE="q8_0"
ENABLE_FLASH_ATTENTION=1

msg() { printf '[Aster Ollama Fix] %s\n' "$*"; }
die() { printf '[Aster Ollama Fix] %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
Fix Ollama for Aster so it uses less root storage and lower idle RAM.

Options:
  --model NAME        Model to pull and use (default: qwen3:1.7b)
  --small-model       Use gemma3:1b instead of qwen3:1.7b
  --models-dir DIR    Store Ollama models here
  --base-url URL      Ollama base URL for Aster (default: http://127.0.0.1:11434)
  --move-existing     Move existing models out of /usr/share/ollama/.ollama/models if possible
  --no-pull           Do not pull the model after setup
  --yes               Non-interactive mode where safe
  --help              Show this help
USAGE
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

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --model)
        [[ $# -ge 2 ]] || die "Missing value for --model"
        MODEL="$2"; shift 2 ;;
      --small-model)
        MODEL="$SMALL_MODEL"; shift ;;
      --models-dir)
        [[ $# -ge 2 ]] || die "Missing value for --models-dir"
        MODELS_DIR="$2"; shift 2 ;;
      --base-url)
        [[ $# -ge 2 ]] || die "Missing value for --base-url"
        BASE_URL="$2"; shift 2 ;;
      --move-existing)
        MOVE_EXISTING=1; shift ;;
      --no-pull)
        NO_PULL=1; shift ;;
      --yes|-y)
        ASSUME_YES=1; shift ;;
      --help|-h)
        usage; exit 0 ;;
      *)
        die "Unknown option: $1" ;;
    esac
  done
}

detect_sdcard_models_dir() {
  local root="/run/media/${USER}"
  local candidate=""
  if [[ -d "$root" ]]; then
    while IFS= read -r -d '' candidate; do
      if [[ -w "$candidate" ]]; then
        printf '%s/Aster/OllamaModels' "$candidate"
        return 0
      fi
    done < <(find "$root" -mindepth 1 -maxdepth 1 -type d -print0 2>/dev/null)
  fi
  return 1
}

ensure_models_dir() {
  if [[ -z "$MODELS_DIR" ]]; then
    MODELS_DIR="$(detect_sdcard_models_dir || true)"
  fi
  if [[ -z "$MODELS_DIR" ]]; then
    MODELS_DIR="${HOME}/.local/share/aster-browser/ollama-models"
  fi
  mkdir -p "$MODELS_DIR"
}

ensure_ollama_installed() {
  if command -v ollama >/dev/null 2>&1; then
    return 0
  fi
  command -v curl >/dev/null 2>&1 || die "curl is required to install Ollama automatically."
  if ! prompt_yes_no "Ollama is not installed. Install it now using the official Linux installer" 'Y'; then
    die "Ollama is required for local model chat."
  fi
  msg "Installing Ollama..."
  curl -fsSL https://ollama.com/install.sh | sh
}

ollama_bin() { command -v ollama 2>/dev/null || true; }

write_env_file() {
  local env_file="$1"
  mkdir -p "$(dirname "$env_file")"
  cat > "$env_file" <<EOF2
OLLAMA_MODELS=${MODELS_DIR}
OLLAMA_HOST=127.0.0.1:11434
OLLAMA_NO_CLOUD=1
OLLAMA_KEEP_ALIVE=0
OLLAMA_FLASH_ATTENTION=${ENABLE_FLASH_ATTENTION}
OLLAMA_KV_CACHE_TYPE=${KV_CACHE_TYPE}
EOF2
}

configure_system_service() {
  local override_dir="/etc/systemd/system/ollama.service.d"
  local override_file="${override_dir}/aster.conf"
  local tmp_file="$(mktemp)"
  write_env_file "$tmp_file"
  if ! command -v systemctl >/dev/null 2>&1; then
    return 1
  fi
  if ! systemctl list-unit-files ollama.service >/dev/null 2>&1 && [[ ! -f /etc/systemd/system/ollama.service ]] && [[ ! -f /lib/systemd/system/ollama.service ]] && [[ ! -f /usr/lib/systemd/system/ollama.service ]]; then
    return 1
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    return 1
  fi
  msg "Configuring the system Ollama service to use: ${MODELS_DIR}"
  sudo mkdir -p "$override_dir"
  {
    echo '[Service]'
    while IFS= read -r line; do
      printf 'Environment="%s"\n' "$line"
    done < "$tmp_file"
  } | sudo tee "$override_file" >/dev/null
  if id ollama >/dev/null 2>&1; then
    sudo mkdir -p "$MODELS_DIR"
    sudo chown -R ollama:ollama "$MODELS_DIR"
  fi
  sudo systemctl daemon-reload
  sudo systemctl enable ollama >/dev/null 2>&1 || true
  sudo systemctl restart ollama
  rm -f "$tmp_file"
  return 0
}

configure_user_service() {
  local bin="$(ollama_bin)"
  local env_file="${HOME}/.config/aster-browser/aster-ollama.env"
  local service_dir="${HOME}/.config/systemd/user"
  local service_file="${service_dir}/aster-ollama.service"
  [[ -n "$bin" ]] || die "Could not find ollama on PATH after installation."
  write_env_file "$env_file"
  mkdir -p "$service_dir"
  cat > "$service_file" <<EOF2
[Unit]
Description=Aster Ollama Service
After=network.target

[Service]
EnvironmentFile=%h/.config/aster-browser/aster-ollama.env
ExecStart=${bin} serve
Restart=always
RestartSec=2

[Install]
WantedBy=default.target
EOF2
  if command -v systemctl >/dev/null 2>&1; then
    if systemctl --user daemon-reload >/dev/null 2>&1; then
      systemctl --user enable --now aster-ollama.service >/dev/null 2>&1 || true
      return 0
    fi
  fi
  return 1
}

start_ollama_fallback() {
  local bin="$(ollama_bin)"
  local log_file="${INSTALL_ROOT}/logs/ollama-serve.log"
  [[ -n "$bin" ]] || die "Could not find ollama on PATH after installation."
  mkdir -p "$(dirname "$log_file")"
  msg "Starting Ollama in the background without systemd..."
  env OLLAMA_MODELS="$MODELS_DIR" OLLAMA_HOST=127.0.0.1:11434 OLLAMA_NO_CLOUD=1 OLLAMA_KEEP_ALIVE=0 OLLAMA_FLASH_ATTENTION="$ENABLE_FLASH_ATTENTION" OLLAMA_KV_CACHE_TYPE="$KV_CACHE_TYPE" nohup "$bin" serve >"$log_file" 2>&1 &
}

wait_for_ollama() {
  command -v curl >/dev/null 2>&1 || die "curl is required to verify Ollama."
  local attempt=0
  while [[ $attempt -lt 90 ]]; do
    if curl -fsS "${BASE_URL%/}/api/version" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  die "Ollama did not become ready at ${BASE_URL%/}."
}

maybe_move_existing_models() {
  [[ $MOVE_EXISTING -eq 1 ]] || return 0
  [[ -d "$EXISTING_MODEL_DIR" ]] || return 0
  if [[ "$MODELS_DIR" == "$EXISTING_MODEL_DIR" ]]; then
    return 0
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    msg "Skipping model move because sudo is not available."
    return 0
  fi
  msg "Moving existing models from ${EXISTING_MODEL_DIR} to ${MODELS_DIR}"
  sudo mkdir -p "$MODELS_DIR"
  if command -v rsync >/dev/null 2>&1; then
    sudo rsync -a "$EXISTING_MODEL_DIR/" "$MODELS_DIR/"
    sudo rm -rf "$EXISTING_MODEL_DIR"
  else
    sudo cp -a "$EXISTING_MODEL_DIR/." "$MODELS_DIR/"
    sudo rm -rf "$EXISTING_MODEL_DIR"
  fi
  if id ollama >/dev/null 2>&1; then
    sudo chown -R ollama:ollama "$MODELS_DIR"
  fi
}

pull_model() {
  [[ $NO_PULL -eq 1 ]] && return 0
  msg "Pulling model: ${MODEL}"
  ollama pull "$MODEL"
}

update_aster_config() {
  local python_cmd="$(choose_python || true)"
  local portable_root=""
  local config_file=""
  [[ -n "$python_cmd" ]] || die "Python is required to update Aster config."
  if [[ -d "$STATE_DIR" ]]; then
    portable_root="$STATE_DIR"
  else
    portable_root="${INSTALL_ROOT}/state"
  fi
  config_file="${portable_root}/config/config.json"
  mkdir -p "$(dirname "$config_file")"
  "$python_cmd" - "$config_file" "$MODEL" "$BASE_URL" <<'PY'
import json, sys
from pathlib import Path
config_path = Path(sys.argv[1])
model = sys.argv[2]
base_url = sys.argv[3]
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
    "drm_mode": "auto",
    "external_browser_cmd": "",
    "streaming_capsules": True,
    "streaming_capsule_auto_launch": True,
    "streaming_capsule_app_mode": False,
    "streaming_capsule_persistent_profile": True,
    "streaming_capsule_prefer_flatpak": True,
    "allow_ai_actions": True,
    "ai_page_context_chars": 6000,
    "ai": {
        "provider": "ollama",
        "model": model,
        "base_url": base_url,
        "api_key_env": "ASTER_OPENAI_API_KEY",
        "system_prompt": (
            "You are Aster Assistant inside a Linux browser. Be concise, privacy-aware, "
            "and prefer low-resource workflows when possible. When the user asks about the current page, "
            "use the provided page excerpt instead of inventing details."
        ),
    },
}
payload = dict(defaults)
if config_path.exists():
    try:
        current = json.loads(config_path.read_text(encoding='utf-8'))
        if isinstance(current, dict):
            payload.update(current)
    except Exception:
        pass
ai = dict(defaults['ai'])
if isinstance(payload.get('ai'), dict):
    ai.update(payload['ai'])
ai['provider'] = 'ollama'
ai['model'] = model
ai['base_url'] = base_url
payload['ai'] = ai
payload['allow_ai_actions'] = True
for key, value in defaults.items():
    payload.setdefault(key, value)
config_path.parent.mkdir(parents=True, exist_ok=True)
config_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding='utf-8')
print(config_path)
PY
}

create_launcher_wrapper() {
  [[ -f "$LAUNCHER" ]] || return 0
  cat > "$AI_WRAPPER" <<EOF2
#!/usr/bin/env bash
set -euo pipefail
export ASTER_AI_PROVIDER='ollama'
export ASTER_AI_MODEL='${MODEL}'
export ASTER_AI_BASE_URL='${BASE_URL}'
export ASTER_ALLOW_AI_ACTIONS='1'
exec '${LAUNCHER}' "\$@"
EOF2
  chmod 700 "$AI_WRAPPER"
}

main() {
  parse_args "$@"
  ensure_models_dir
  msg "Model storage will be: ${MODELS_DIR}"
  msg "Aster will talk to Ollama at: ${BASE_URL}"
  msg "Model: ${MODEL}"
  ensure_ollama_installed
  maybe_move_existing_models
  if ! configure_system_service; then
    msg "System Ollama service not available or not writable. Falling back to user setup."
    configure_user_service || start_ollama_fallback
  fi
  wait_for_ollama
  pull_model
  update_aster_config
  create_launcher_wrapper
  msg "Done."
  msg "Models directory: ${MODELS_DIR}"
  msg "Ollama endpoint: ${BASE_URL}"
  msg "Model: ${MODEL}"
  [[ -f "$AI_WRAPPER" ]] && msg "Aster local-AI launcher: ${AI_WRAPPER}"
}

main "$@"
