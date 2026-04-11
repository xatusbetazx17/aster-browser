#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${HOME}/.local/share/aster-browser-portable"
TARGET="${INSTALL_ROOT}/app/aster_browser/theme.py"
BACKUP_DIR="${INSTALL_ROOT}/backups"

msg() { printf '[Aster Theme Hotfix] %s\n' "$*"; }
die() { printf '[Aster Theme Hotfix] %s\n' "$*" >&2; exit 1; }

[[ -f "$TARGET" ]] || die "theme.py not found at $TARGET"
mkdir -p "$BACKUP_DIR"
backup="$BACKUP_DIR/theme.py.$(date +%Y%m%d-%H%M%S).bak"
cp "$TARGET" "$backup"
msg "Backup created at $backup"

TARGET_PATH="$TARGET" python3 - <<'PY2'
from pathlib import Path
import os
path = Path(os.environ['TARGET_PATH'])
text = path.read_text(encoding='utf-8')
marker = '    rendered = _BASE_TEMPLATE\n    for key, value in tokens.items():\n        rendered = rendered.replace("{" + key + "}", str(value))\n    return rendered\n'
if marker in text:
    print('[Aster Theme Hotfix] theme.py is already fixed.')
else:
    old = '    return _BASE_TEMPLATE.format(**tokens)\n'
    new = marker
    if old not in text:
        raise SystemExit('[Aster Theme Hotfix] Could not find the buggy format() line to patch.')
    text = text.replace(old, new, 1)
    path.write_text(text, encoding='utf-8')
    print('[Aster Theme Hotfix] Patched theme.py.')
PY2

python3 -m py_compile "$TARGET"
msg "Compile check passed."
msg "Start Aster with: ~/.local/share/aster-browser-portable/aster-launch.sh"
