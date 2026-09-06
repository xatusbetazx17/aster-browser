#!/usr/bin/env bash
# Run again to update. Installs only the selected Aster experiment.
set -euo pipefail

if [[ "${1:-}" == "--help" ]]; then
  cat <<'HELP'
Aster Linux install / update
  bash install-linux.sh [--edition webkit] [--install-dir PATH]
                        [--skip-dependencies] [--check] [--rollback]
Installs standalone Aster on supported desktop Linux.
Python 3.10+ and curl or wget are required to bootstrap setup.
SteamOS and immutable-Linux packaging is still pending.
HELP
  exit 0
fi

if [[ "$(id -u)" == 0 ]]; then
  echo 'Run this script as your normal desktop user. It uses sudo only for system packages.' >&2
  exit 1
fi

task_python=/usr/bin/python3
if [[ ! -x "$task_python" ]]; then
  task_python=$(command -v python3 || true)
fi
if [[ -z "$task_python" ]] || ! "$task_python" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'; then
  echo 'Install Python 3.10+ through your distribution, then run this file again.' >&2
  exit 1
fi

# If this file came with a repository checkout, use its matching setup source.
# A standalone download instead fetches the current setup helper over HTTPS.
task_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ -f "$task_directory/setup.py" ]]; then
  exec "$task_python" "$task_directory/setup.py" "$@"
fi
task_temporary=$(mktemp -d -t aster-setup.XXXXXXXX)
trap 'rm -rf -- "$task_temporary"' EXIT
task_url='https://raw.githubusercontent.com/xatusbetazx17/aster-browser/codex/aster-webkit-desktop/installers/setup.py'
if command -v curl >/dev/null 2>&1; then
  curl --fail --location --proto '=https' --proto-redir '=https' --max-time 60 "$task_url" --output "$task_temporary/setup.py"
elif command -v wget >/dev/null 2>&1; then
  wget --https-only --timeout=60 "$task_url" -O "$task_temporary/setup.py"
else
  echo 'Install curl or wget through your distribution, then run this file again.' >&2
  exit 1
fi
"$task_python" "$task_temporary/setup.py" "$@"
