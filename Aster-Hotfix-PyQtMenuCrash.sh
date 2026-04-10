#!/usr/bin/env bash
set -euo pipefail

DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
INSTALL_ROOT="$DATA_HOME/aster-browser-portable"
TARGET="$INSTALL_ROOT/app/aster_browser/browser.py"
LAUNCHER="$INSTALL_ROOT/aster-launch.sh"

log() {
  printf '[Aster Hotfix] %s\n' "$*"
}

die() {
  printf '[Aster Hotfix] %s\n' "$*" >&2
  exit 1
}

[[ -f "$TARGET" ]] || die "Could not find installed Aster file: $TARGET"

BACKUP="$TARGET.bak.$(date +%Y%m%d-%H%M%S)"
cp "$TARGET" "$BACKUP"
log "Backup created: $BACKUP"

python3 - "$TARGET" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding='utf-8')

old = '''        tools_menu = self.menuBar().addMenu("&Tools")
        tools_menu.addAction("Extensions", self.open_extension_manager_dialog, QKeySequence("Ctrl+Shift+E"))
        tools_menu.addAction("Add extension from page", self.install_extension_from_current_page, QKeySequence("Ctrl+Shift+A"))
        tools_menu.addAction("Streaming status", lambda: QMessageBox.information(self, "Streaming status", self.streaming_status_text()))
        tools_menu.addAction("Assistant status", lambda: QMessageBox.information(self, "Assistant", "Internal assistant is enabled."))
'''

new = '''        tools_menu = self.menuBar().addMenu("&Tools")

        extensions_action = QAction("Extensions", self)
        extensions_action.setShortcut(QKeySequence("Ctrl+Shift+E"))
        extensions_action.triggered.connect(self.open_extension_manager_dialog)
        tools_menu.addAction(extensions_action)

        add_from_page_action = QAction("Add extension from page", self)
        add_from_page_action.setShortcut(QKeySequence("Ctrl+Shift+A"))
        add_from_page_action.triggered.connect(self.install_extension_from_current_page)
        tools_menu.addAction(add_from_page_action)

        tools_menu.addAction("Streaming status", lambda: QMessageBox.information(self, "Streaming status", self.streaming_status_text()))
        tools_menu.addAction("Assistant status", lambda: QMessageBox.information(self, "Assistant", "Internal assistant is enabled."))
'''

if new in text:
    print('already-fixed')
    raise SystemExit(0)

if old not in text:
    raise SystemExit('target block not found; file layout was different than expected')

path.write_text(text.replace(old, new), encoding='utf-8')
print('patched')
PY

if command -v python3 >/dev/null 2>&1; then
  python3 -m py_compile "$TARGET" || die "Patch applied, but compile check failed. Restore from backup: $BACKUP"
fi

log "Patch applied successfully."
if [[ -x "$LAUNCHER" ]]; then
  log "Start Aster with: $LAUNCHER"
else
  log "Launcher not found. Re-run the installer if needed."
fi
