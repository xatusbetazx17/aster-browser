#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${HOME}/.local/share/aster-browser-portable"
STATE_DIR="${INSTALL_ROOT}/state"
LAUNCHER="${INSTALL_ROOT}/aster-launch.sh"
WRAPPER="${INSTALL_ROOT}/aster-openai-launch.sh"
ENV_FILE="${STATE_DIR}/aster-openai.env"
DESKTOP_FILE="${HOME}/.local/share/applications/org.aster.Browser.desktop"
BACKUP_DESKTOP_FILE="${DESKTOP_FILE}.bak-openai"

msg() {
  printf '[Aster OpenAI Setup] %s\n' "$*"
}

die() {
  printf '[Aster OpenAI Setup] %s\n' "$*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || die "Expected file not found: $1"
}

prompt_default() {
  local prompt="$1"
  local default="$2"
  local value=""
  read -r -p "$prompt [$default]: " value
  if [[ -z "$value" ]]; then
    value="$default"
  fi
  printf '%s' "$value"
}

main() {
  require_file "$LAUNCHER"
  mkdir -p "$STATE_DIR"

  msg "This will configure your installed Aster Browser to use OpenAI for the AI panel."
  msg "The key will be stored locally in a file readable only by your user account (chmod 600)."
  printf '\n'

  local model base_url key
  model="$(prompt_default 'OpenAI model' 'gpt-5.4-mini')"
  base_url="$(prompt_default 'OpenAI base URL' 'https://api.openai.com/v1')"

  printf 'Paste your OpenAI API key (input hidden): '
  read -r -s key
  printf '\n'

  [[ -n "$key" ]] || die "No API key entered."

  umask 077
  cat > "$ENV_FILE" <<EOF2
export ASTER_AI_PROVIDER='openai'
export ASTER_AI_MODEL='${model//\'/\'\\\'}'
export ASTER_AI_BASE_URL='${base_url//\'/\'\\\'}'
export ASTER_OPENAI_API_KEY='${key//\'/\'\\\'}'
EOF2
  chmod 600 "$ENV_FILE"

  cat > "$WRAPPER" <<EOF2
#!/usr/bin/env bash
set -euo pipefail
source "${ENV_FILE}"
exec "${LAUNCHER}" "\$@"
EOF2
  chmod 700 "$WRAPPER"

  if [[ -f "$DESKTOP_FILE" ]]; then
    cp "$DESKTOP_FILE" "$BACKUP_DESKTOP_FILE"
    awk -v wrapper="$WRAPPER" '
      BEGIN {changed=0}
      /^Exec=/ && changed==0 {print "Exec=" wrapper " %U"; changed=1; next}
      {print}
    ' "$BACKUP_DESKTOP_FILE" > "$DESKTOP_FILE"
    msg "Updated desktop launcher to use OpenAI wrapper."
  else
    msg "Desktop entry not found. You can still run the wrapper directly."
  fi

  msg "Done."
  msg "Wrapper: $WRAPPER"
  msg "Env file: $ENV_FILE"
  msg "Start Aster from the app menu or run: $WRAPPER"
}

main "$@"
