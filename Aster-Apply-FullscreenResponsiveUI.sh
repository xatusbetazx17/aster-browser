#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${HOME}/.local/share/aster-browser-portable"
APP_DIR="${INSTALL_ROOT}/app/aster_browser"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${INSTALL_ROOT}/state/backups/fullscreen-responsive-${STAMP}"

msg() {
  printf '[Aster UI Responsive Fix] %s
' "$*"
}

die() {
  printf '[Aster UI Responsive Fix] %s
' "$*" >&2
  exit 1
}

[[ -d "$APP_DIR" ]] || die "Aster install not found at $APP_DIR"
mkdir -p "$BACKUP_DIR"

for file in browser.py widgets.py; do
  [[ -f "$APP_DIR/$file" ]] || die "Missing expected file: $APP_DIR/$file"
  cp "$APP_DIR/$file" "$BACKUP_DIR/$file"
done

cat > "$APP_DIR/widgets.py" <<'PYWIDGETS'
from __future__ import annotations

import copy
import html
from typing import Callable

from PyQt6.QtCore import QObject, QPoint, QThread, QSize, Qt, pyqtSignal
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDockWidget,
    QFormLayout,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QScrollArea,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMenu,
    QPushButton,
    QSizePolicy,
    QSpinBox,
    QTabBar,
    QTextBrowser,
    QTextEdit,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from .ai import AIBroker, AIContext, CommandRouter, IntentRouter
from .config import AIConfig, BrowserConfig
from .icons import svg_icon
from .lite import LitePage, render_lite_page


class AsterTitleBar(QWidget):
    def __init__(self, window: QWidget, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._window = window
        self._drag_offset: QPoint | None = None
        self.setObjectName("AsterTitleBar")

        layout = QHBoxLayout(self)
        layout.setContentsMargins(14, 10, 14, 8)
        layout.setSpacing(8)

        self.logo_button = QToolButton(self)
        self.logo_button.setObjectName("TitleLogoButton")
        self.logo_button.setIcon(svg_icon("aster_logo"))
        self.logo_button.setIconSize(QSize(22, 22))
        self.logo_button.setAutoRaise(True)
        self.logo_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.logo_button.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.logo_button.setFixedSize(30, 30)

        title_box = QVBoxLayout()
        title_box.setContentsMargins(0, 0, 0, 0)
        title_box.setSpacing(0)
        self.title_label = QLabel("Aster Browser")
        self.title_label.setObjectName("AsterTitleLabel")
        self.context_label = QLabel("Low-RAM browser + internal assistant")
        self.context_label.setObjectName("AsterContextLabel")
        self.context_label.setMinimumWidth(220)
        title_box.addWidget(self.title_label)
        title_box.addWidget(self.context_label)

        self.file_menu = QMenu("File", self)
        self.view_menu = QMenu("View", self)
        self.tools_menu = QMenu("Tools", self)
        self.compact_menu = QMenu("Menu", self)

        self.file_button = self._make_menu_button("File", self.file_menu)
        self.view_button = self._make_menu_button("View", self.view_menu)
        self.tools_button = self._make_menu_button("Tools", self.tools_menu)
        self.compact_button = self._make_menu_button("Menu", self.compact_menu)
        self.compact_button.setObjectName("HeaderCompactButton")
        self.compact_button.setIcon(svg_icon("menu_more"))
        self.compact_button.setIconSize(QSize(14, 14))
        self.compact_button.hide()

        self.min_button = self._make_window_button("minimize", "Minimize window")
        self.max_button = self._make_window_button("maximize", "Maximize window")
        self.close_button = self._make_window_button("close", "Close window", close=True)

        self.min_button.clicked.connect(self._window.showMinimized)
        self.max_button.clicked.connect(self.toggle_maximize)
        self.close_button.clicked.connect(self._window.close)

        layout.addWidget(self.logo_button)
        layout.addLayout(title_box)
        layout.addSpacing(10)
        layout.addWidget(self.file_button)
        layout.addWidget(self.view_button)
        layout.addWidget(self.tools_button)
        layout.addWidget(self.compact_button)
        layout.addStretch(1)
        layout.addWidget(self.min_button)
        layout.addWidget(self.max_button)
        layout.addWidget(self.close_button)
        self.sync_window_state()

    def _make_menu_button(self, text: str, menu: QMenu) -> QToolButton:
        button = QToolButton(self)
        button.setText(text)
        button.setMenu(menu)
        button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        button.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        button.setObjectName("HeaderMenuButton")
        return button

    def _make_window_button(self, icon_name: str, tooltip: str, close: bool = False) -> QToolButton:
        button = QToolButton(self)
        button.setIcon(svg_icon(icon_name))
        button.setIconSize(QSize(14, 14))
        button.setFixedSize(34, 34)
        button.setAutoRaise(True)
        button.setToolTip(tooltip)
        button.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        button.setObjectName("WindowCloseButton" if close else "WindowButton")
        return button

    def set_context_text(self, text: str) -> None:
        clean = (text or "").strip()
        if not clean:
            clean = "Low-RAM browser + internal assistant"
        if len(clean) > 72:
            clean = clean[:69].rstrip() + "…"
        self.context_label.setText(clean)

    def apply_compact_mode(self, compact: bool) -> None:
        for button in (self.file_button, self.view_button, self.tools_button):
            button.setVisible(not compact)
        self.compact_button.setVisible(compact)
        self.context_label.setVisible(not compact)
        if compact:
            self._rebuild_compact_menu()

    def _rebuild_compact_menu(self) -> None:
        self.compact_menu.clear()
        file_menu = self.compact_menu.addMenu("File")
        file_menu.addActions(self.file_menu.actions())
        view_menu = self.compact_menu.addMenu("View")
        view_menu.addActions(self.view_menu.actions())
        tools_menu = self.compact_menu.addMenu("Tools")
        tools_menu.addActions(self.tools_menu.actions())

    def apply_visual_scale(self, scale: float) -> None:
        scale = max(0.92, min(scale, 1.28))
        logo_px = max(20, int(round(22 * scale)))
        logo_box = max(28, int(round(30 * scale)))
        window_px = max(13, int(round(14 * scale)))
        window_box = max(32, int(round(34 * scale)))
        compact_px = max(13, int(round(14 * scale)))
        self.logo_button.setIconSize(QSize(logo_px, logo_px))
        self.logo_button.setFixedSize(logo_box, logo_box)
        self.compact_button.setIconSize(QSize(compact_px, compact_px))
        for button in (self.min_button, self.max_button, self.close_button):
            button.setIconSize(QSize(window_px, window_px))
            button.setFixedSize(window_box, window_box)
        menu_font = QFont(self.font())
        menu_font.setPointSizeF(max(10.5, 11.0 * scale))
        menu_font.setWeight(QFont.Weight.DemiBold)
        for button in (self.file_button, self.view_button, self.tools_button, self.compact_button):
            button.setFont(menu_font)
            button.setMinimumHeight(max(30, int(round(34 * scale))))
        title_font = QFont(self.title_label.font())
        title_font.setPointSizeF(max(13.0, 15.0 * scale))
        title_font.setWeight(QFont.Weight.Bold)
        self.title_label.setFont(title_font)
        context_font = QFont(self.context_label.font())
        context_font.setPointSizeF(max(10.0, 11.0 * scale))
        self.context_label.setFont(context_font)

    def sync_window_state(self) -> None:
        if self._window.isMaximized():
            self.max_button.setIcon(svg_icon("restore"))
            self.max_button.setToolTip("Restore window")
        else:
            self.max_button.setIcon(svg_icon("maximize"))
            self.max_button.setToolTip("Maximize window")

    def toggle_maximize(self) -> None:
        if self._window.isMaximized():
            self._window.showNormal()
        else:
            self._window.showMaximized()
        self.sync_window_state()

    def _can_drag_from(self, pos) -> bool:
        child = self.childAt(pos)
        return child is None or isinstance(child, QLabel)

    def mousePressEvent(self, event) -> None:  # type: ignore[override]
        if event.button() == Qt.MouseButton.LeftButton and self._can_drag_from(event.position().toPoint()):
            self._drag_offset = event.globalPosition().toPoint() - self._window.frameGeometry().topLeft()
            event.accept()
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:  # type: ignore[override]
        if self._drag_offset is not None and event.buttons() & Qt.MouseButton.LeftButton:
            if self._window.isMaximized():
                self._window.showNormal()
                self.sync_window_state()
                self._drag_offset = QPoint(self.width() // 2, 18)
            self._window.move(event.globalPosition().toPoint() - self._drag_offset)
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # type: ignore[override]
        self._drag_offset = None
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event) -> None:  # type: ignore[override]
        if event.button() == Qt.MouseButton.LeftButton and self._can_drag_from(event.position().toPoint()):
            self.toggle_maximize()
            event.accept()
            return
        super().mouseDoubleClickEvent(event)


class AsterTabBar(QTabBar):
    quick_actions_requested = pyqtSignal(int, QPoint)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._press_index = -1
        self._press_was_current = False
        self._press_pos = QPoint()
        self._dragging = False
        self._active_tab_left_click_menu_enabled = True
        self.setDrawBase(False)
        self.setExpanding(False)
        self.setUsesScrollButtons(True)
        self.setMovable(True)

    def set_active_tab_left_click_menu_enabled(self, enabled: bool) -> None:
        self._active_tab_left_click_menu_enabled = bool(enabled)

    def mousePressEvent(self, event) -> None:  # type: ignore[override]
        self._press_pos = event.position().toPoint()
        self._press_index = self.tabAt(self._press_pos)
        self._press_was_current = self._press_index >= 0 and self._press_index == self.currentIndex()
        self._dragging = False
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:  # type: ignore[override]
        if self._press_index >= 0 and (event.position().toPoint() - self._press_pos).manhattanLength() > QApplication.startDragDistance():
            self._dragging = True
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # type: ignore[override]
        release_pos = event.position().toPoint()
        release_index = self.tabAt(release_pos)
        should_open = (
            self._active_tab_left_click_menu_enabled
            and event.button() == Qt.MouseButton.LeftButton
            and self._press_was_current
            and not self._dragging
            and self._press_index >= 0
            and release_index == self._press_index
        )
        super().mouseReleaseEvent(event)
        if should_open:
            anchor = self.mapToGlobal(self.tabRect(self._press_index).bottomLeft())
            self.quick_actions_requested.emit(self._press_index, anchor)
            event.accept()

    def contextMenuEvent(self, event) -> None:  # type: ignore[override]
        index = self.tabAt(event.pos())
        if index >= 0:
            self.quick_actions_requested.emit(index, self.mapToGlobal(event.pos()))
            event.accept()
            return
        super().contextMenuEvent(event)


class MobileViewWidget(QWidget):
    open_desktop_requested = pyqtSignal()
    refresh_requested = pyqtSignal()

    def __init__(self, view: QWidget, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._view = view
        self.setObjectName("AsterMobileView")
        self._build_ui()

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 18, 18, 18)
        layout.setSpacing(12)

        controls = QHBoxLayout()
        badge = QLabel("Mobile view")
        badge.setObjectName("AsterMobileBadge")
        self.device_combo = QComboBox(self)
        self.device_combo.addItem("Pixel 7 · 412 px", 412)
        self.device_combo.addItem("iPhone 14 Pro · 393 px", 393)
        self.device_combo.addItem("Galaxy S23 · 360 px", 360)
        self.device_combo.addItem("Small tablet · 768 px", 768)
        self.device_combo.currentIndexChanged.connect(self._apply_device_width)
        reload_button = QPushButton("Reload")
        reload_button.clicked.connect(lambda _checked=False: self.refresh_requested.emit())
        desktop_button = QPushButton("Desktop view")
        desktop_button.clicked.connect(lambda _checked=False: self.open_desktop_requested.emit())
        controls.addWidget(badge)
        controls.addWidget(self.device_combo)
        controls.addStretch(1)
        controls.addWidget(reload_button)
        controls.addWidget(desktop_button)

        center_row = QHBoxLayout()
        center_row.addStretch(1)
        self.mobile_shell = QFrame(self)
        self.mobile_shell.setObjectName("AsterMobileShell")
        shell_layout = QVBoxLayout(self.mobile_shell)
        shell_layout.setContentsMargins(12, 12, 12, 12)
        shell_layout.setSpacing(8)
        self.mobile_header = QLabel("Responsive preview · mobile UA")
        self.mobile_header.setObjectName("AsterMobileHeader")
        shell_layout.addWidget(self.mobile_header)
        self.phone_frame = QFrame(self.mobile_shell)
        self.phone_frame.setObjectName("AsterMobileFrame")
        phone_layout = QVBoxLayout(self.phone_frame)
        phone_layout.setContentsMargins(0, 0, 0, 0)
        phone_layout.setSpacing(0)
        phone_layout.addWidget(self._view)
        shell_layout.addWidget(self.phone_frame, alignment=Qt.AlignmentFlag.AlignHCenter)
        center_row.addWidget(self.mobile_shell, 0, Qt.AlignmentFlag.AlignTop)
        center_row.addStretch(1)

        layout.addLayout(controls)
        layout.addLayout(center_row, 1)
        self._apply_device_width()

    def web_view(self):
        return self._view

    def _apply_device_width(self) -> None:
        width = int(self.device_combo.currentData() or 412)
        width = max(320, min(width, 900))
        self.phone_frame.setFixedWidth(width)
        self.mobile_header.setText(f"Responsive preview · {width}px viewport · mobile UA")


class QuickLinkTile(QFrame):
    clicked = pyqtSignal()

    def __init__(self, title: str, subtitle: str, icon_name: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.icon_name = icon_name
        self.setObjectName("AsterQuickLinkButton")
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self._layout = QVBoxLayout(self)
        self._layout.setContentsMargins(18, 18, 18, 18)
        self._layout.setSpacing(8)
        self.icon_label = QLabel(self)
        self.icon_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.title_label = QLabel(title, self)
        self.title_label.setObjectName("AsterQuickLinkTitle")
        self.title_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.subtitle_label = QLabel(subtitle, self)
        self.subtitle_label.setObjectName("AsterQuickLinkSubtitle")
        self.subtitle_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.subtitle_label.setWordWrap(True)
        self._layout.addWidget(self.icon_label, 0, Qt.AlignmentFlag.AlignHCenter)
        self._layout.addWidget(self.title_label)
        self._layout.addWidget(self.subtitle_label)
        self.apply_density(1.0)

    def apply_density(self, scale: float) -> None:
        scale = max(0.88, min(scale, 1.28))
        icon_px = max(22, int(round(26 * scale)))
        self.icon_label.setPixmap(svg_icon(self.icon_name).pixmap(icon_px, icon_px))
        title_font = QFont(self.title_label.font())
        title_font.setPointSizeF(max(11.0, 12.0 * scale))
        title_font.setWeight(QFont.Weight.Bold)
        self.title_label.setFont(title_font)
        subtitle_font = QFont(self.subtitle_label.font())
        subtitle_font.setPointSizeF(max(8.5, 9.0 * scale))
        self.subtitle_label.setFont(subtitle_font)
        margin = max(14, int(round(18 * scale)))
        self._layout.setContentsMargins(margin, margin, margin, margin)
        self._layout.setSpacing(max(6, int(round(8 * scale))))
        self.setMinimumSize(max(110, int(round(146 * scale))), max(88, int(round(112 * scale))))

    def mouseReleaseEvent(self, event) -> None:  # type: ignore[override]
        if event.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit()
            event.accept()
            return
        super().mouseReleaseEvent(event)


class NewTabWidget(QWidget):
    open_requested = pyqtSignal(str)

    def __init__(self, stats_provider: Callable[[], str], parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.stats_provider = stats_provider
        self.tiles: list[QuickLinkTile] = []
        self._build_ui()
        self.refresh_stats()

    def _build_ui(self) -> None:
        self.outer_layout = QVBoxLayout(self)
        self.outer_layout.setContentsMargins(28, 24, 28, 24)
        self.outer_layout.addStretch(1)

        self.row_layout = QHBoxLayout()
        self.row_layout.addStretch(1)

        self.shell = QFrame(self)
        self.shell.setObjectName("AsterPageShell")
        self.shell.setMaximumWidth(940)
        self.shell.setMinimumWidth(520)
        self.shell.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Maximum)
        shell_layout = QVBoxLayout(self.shell)
        shell_layout.setContentsMargins(30, 28, 30, 24)
        shell_layout.setSpacing(18)

        self.card = QFrame(self.shell)
        self.card.setObjectName("AsterCard")
        card_layout = QVBoxLayout(self.card)
        card_layout.setContentsMargins(30, 28, 30, 24)
        card_layout.setSpacing(16)

        self.logo = QLabel(self.card)
        self.logo.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.title_label = QLabel("Aster Browser", self.card)
        self.title_label.setObjectName("AsterCardTitle")
        self.title_label.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.subtitle_label = QLabel(
            "Low-RAM browsing with full web mode, Lite mode, parked tabs, internal AI, container profiles, streaming compatibility, and extension tools.",
            self.card,
        )
        self.subtitle_label.setObjectName("AsterCardSubtitle")
        self.subtitle_label.setWordWrap(True)
        self.subtitle_label.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.entry = QLineEdit(self.card)
        self.entry.setPlaceholderText("Enter a URL or search query")
        self.entry.returnPressed.connect(self._emit_open)
        self.open_button = QPushButton("Open", self.card)
        self.open_button.clicked.connect(self._emit_open)
        search_row = QHBoxLayout()
        search_row.setSpacing(10)
        search_row.addWidget(self.entry, 1)
        search_row.addWidget(self.open_button)

        links = [
            ("DuckDuckGo", "Privacy search", "search", "https://duckduckgo.com"),
            ("YouTube", "Video", "play_circle", "https://www.youtube.com"),
            ("Prime Video", "Streaming", "tv", "https://www.primevideo.com"),
            ("GitHub", "Code", "code_braces", "https://github.com"),
            ("Wikipedia", "Reference", "book_open", "https://wikipedia.org"),
            ("Speedtest", "Network", "speedometer", "https://www.speedtest.net"),
        ]
        self.links_grid = QGridLayout()
        self.links_grid.setHorizontalSpacing(12)
        self.links_grid.setVerticalSpacing(12)
        for index, (label, subtitle_text, icon_name, target) in enumerate(links):
            tile = QuickLinkTile(label, subtitle_text, icon_name, self.card)
            tile.clicked.connect(lambda value=target: self.open_requested.emit(value))
            self.tiles.append(tile)
            self.links_grid.addWidget(tile, index // 3, index % 3)

        self.stats_strip = QFrame(self.card)
        self.stats_strip.setObjectName("AsterStatsStrip")
        stats_layout = QHBoxLayout(self.stats_strip)
        stats_layout.setContentsMargins(14, 12, 14, 12)
        stats_layout.setSpacing(12)
        self.stats_label = QLabel(self.stats_strip)
        self.stats_label.setWordWrap(True)
        self.refresh_button = QPushButton("Refresh stats", self.stats_strip)
        self.refresh_button.setObjectName("SecondaryButton")
        self.refresh_button.clicked.connect(self.refresh_stats)
        stats_layout.addWidget(self.stats_label, 1)
        stats_layout.addWidget(self.refresh_button, 0, Qt.AlignmentFlag.AlignRight)

        card_layout.addWidget(self.logo)
        card_layout.addWidget(self.title_label)
        card_layout.addWidget(self.subtitle_label)
        card_layout.addLayout(search_row)
        card_layout.addLayout(self.links_grid)
        card_layout.addWidget(self.stats_strip)

        shell_layout.addWidget(self.card)
        self.row_layout.addWidget(self.shell, 0, Qt.AlignmentFlag.AlignTop)
        self.row_layout.addStretch(1)

        self.outer_layout.addLayout(self.row_layout)
        self.outer_layout.addStretch(1)
        self.apply_density_for_width(max(self.width(), 960))

    def apply_density_for_width(self, width: int) -> None:
        width = max(420, int(width or 0))
        if width < 700:
            scale = 0.92
        elif width < 900:
            scale = 1.00
        elif width < 1180:
            scale = 1.08
        else:
            scale = 1.16
        target_width = min(920, max(520, int(width * 0.72)))
        self.shell.setMaximumWidth(target_width)
        self.shell.setMinimumWidth(min(target_width, max(500, int(target_width * 0.84))))
        self.logo.setPixmap(svg_icon("aster_logo").pixmap(int(round(56 * scale)), int(round(56 * scale))))
        title_font = QFont(self.title_label.font())
        title_font.setPointSizeF(max(19.0, 24.0 * scale))
        title_font.setWeight(QFont.Weight.Black)
        self.title_label.setFont(title_font)
        subtitle_font = QFont(self.subtitle_label.font())
        subtitle_font.setPointSizeF(max(11.0, 12.0 * scale))
        self.subtitle_label.setFont(subtitle_font)
        entry_font = QFont(self.entry.font())
        entry_font.setPointSizeF(max(11.0, 11.5 * scale))
        self.entry.setFont(entry_font)
        button_font = QFont(self.open_button.font())
        button_font.setPointSizeF(max(10.5, 11.0 * scale))
        self.open_button.setFont(button_font)
        self.refresh_button.setFont(button_font)
        self.links_grid.setHorizontalSpacing(max(10, int(round(12 * scale))))
        self.links_grid.setVerticalSpacing(max(10, int(round(12 * scale))))
        for tile in self.tiles:
            tile.apply_density(scale)
        stats_font = QFont(self.stats_label.font())
        stats_font.setPointSizeF(max(10.0, 10.5 * scale))
        self.stats_label.setFont(stats_font)

    def resizeEvent(self, event) -> None:  # type: ignore[override]
        super().resizeEvent(event)
        self.apply_density_for_width(event.size().width())

    def _emit_open(self) -> None:
        text = self.entry.text().strip()
        if text:
            self.open_requested.emit(text)

    def refresh_stats(self) -> None:
        self.stats_label.setText(self.stats_provider())

    def showEvent(self, event) -> None:  # type: ignore[override]
        super().showEvent(event)
        self.refresh_stats()
        self.apply_density_for_width(self.width() or 960)


class ErrorPageWidget(QWidget):
    retry_requested = pyqtSignal()
    home_requested = pyqtSignal()
    external_requested = pyqtSignal()

    def __init__(self, title: str, subtitle: str, url: str, details: list[str], parent: QWidget | None = None) -> None:
        super().__init__(parent)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(32, 28, 32, 28)
        outer.addStretch(1)
        row = QHBoxLayout()
        row.addStretch(1)
        shell = QFrame(self)
        shell.setObjectName("AsterPageShell")
        shell.setMaximumWidth(820)
        shell_layout = QVBoxLayout(shell)
        shell_layout.setContentsMargins(24, 24, 24, 24)
        card = QFrame(shell)
        card.setObjectName("AsterErrorCard")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(28, 28, 28, 28)
        layout.setSpacing(14)
        icon_wrap = QFrame(card)
        icon_wrap.setObjectName("AsterErrorIconWrap")
        icon_layout = QVBoxLayout(icon_wrap)
        icon_layout.setContentsMargins(16, 16, 16, 16)
        icon = QLabel(icon_wrap)
        icon.setPixmap(svg_icon("warning_triangle").pixmap(30, 30))
        icon.setAlignment(Qt.AlignmentFlag.AlignCenter)
        icon_layout.addWidget(icon)
        title_label = QLabel(title, card)
        title_label.setObjectName("AsterErrorTitle")
        title_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        subtitle_label = QLabel(subtitle, card)
        subtitle_label.setObjectName("AsterErrorSubtitle")
        subtitle_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        subtitle_label.setWordWrap(True)
        url_label = QLabel(url, card)
        url_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        url_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        details_label = QLabel("\n".join(f"• {item}" for item in details if item), card)
        details_label.setWordWrap(True)
        details_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        button_row = QHBoxLayout()
        retry = QPushButton("Retry", card)
        retry.clicked.connect(lambda _checked=False: self.retry_requested.emit())
        home = QPushButton("Go home", card)
        home.setObjectName("SecondaryButton")
        home.clicked.connect(lambda _checked=False: self.home_requested.emit())
        external = QPushButton("Open externally", card)
        external.setObjectName("GhostButton")
        external.clicked.connect(lambda _checked=False: self.external_requested.emit())
        button_row.addStretch(1)
        button_row.addWidget(retry)
        button_row.addWidget(home)
        button_row.addWidget(external)
        button_row.addStretch(1)
        layout.addWidget(icon_wrap, 0, Qt.AlignmentFlag.AlignHCenter)
        layout.addWidget(title_label)
        layout.addWidget(subtitle_label)
        layout.addWidget(url_label)
        layout.addWidget(details_label)
        layout.addLayout(button_row)
        shell_layout.addWidget(card)
        row.addWidget(shell, 1)
        row.addStretch(1)
        outer.addLayout(row)
        outer.addStretch(1)


class CompatibilityRedirectWidget(QWidget):
    try_inside_requested = pyqtSignal()
    open_external_requested = pyqtSignal()

    def __init__(self, title: str, url: str, reason: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 28, 28, 28)
        title_label = QLabel(title)
        title_label.setStyleSheet("font-size: 22px; font-weight: 600;")
        url_label = QLabel(url)
        url_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        reason_label = QLabel(reason)
        reason_label.setWordWrap(True)
        open_external = QPushButton("Open in compatibility browser")
        open_external.clicked.connect(lambda _checked=False: self.open_external_requested.emit())
        try_inside = QPushButton("Try inside Aster anyway")
        try_inside.clicked.connect(lambda _checked=False: self.try_inside_requested.emit())
        buttons = QHBoxLayout()
        buttons.addWidget(open_external)
        buttons.addWidget(try_inside)
        layout.addWidget(title_label)
        layout.addWidget(url_label)
        layout.addSpacing(10)
        layout.addWidget(reason_label)
        layout.addSpacing(14)
        layout.addLayout(buttons)
        layout.addStretch(1)


class StreamingCapsuleWidget(QWidget):
    launch_requested = pyqtSignal()
    try_inside_requested = pyqtSignal()
    reset_profile_requested = pyqtSignal()
    open_raw_requested = pyqtSignal()

    def __init__(self, service: str, url: str, reason: str, runtime_hint: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._build_ui(service, url, reason, runtime_hint)

    def _build_ui(self, service: str, url: str, reason: str, runtime_hint: str) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 28, 28, 28)

        title_label = QLabel(f"{service} Capsule")
        title_label.setStyleSheet("font-size: 24px; font-weight: 700;")

        badge_label = QLabel("Protected playback path")
        badge_label.setStyleSheet("font-size: 13px; font-weight: 600;")

        url_label = QLabel(url)
        url_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        url_label.setWordWrap(True)

        intro_label = QLabel(
            "Aster stays your main browser for tabs, ad blocking, profiles, AI, and low-memory control. "
            "This page opens through a separate streaming capsule so protected playback can use a more compatible runtime when needed."
        )
        intro_label.setWordWrap(True)

        reason_label = QLabel(reason)
        reason_label.setWordWrap(True)

        self.runtime_label = QLabel(f"Capsule runtime: {runtime_hint}")
        self.runtime_label.setWordWrap(True)

        self.profile_label = QLabel("Capsule profile: auto")
        self.profile_label.setWordWrap(True)

        self.status_label = QLabel("Launch status: waiting")
        self.status_label.setWordWrap(True)
        self.status_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)

        launch_button = QPushButton("Launch or relaunch capsule")
        launch_button.clicked.connect(lambda _checked=False: self.launch_requested.emit())

        reset_button = QPushButton("Reset capsule profile")
        reset_button.clicked.connect(lambda _checked=False: self.reset_profile_requested.emit())

        raw_button = QPushButton("Open raw external browser")
        raw_button.clicked.connect(lambda _checked=False: self.open_raw_requested.emit())

        try_inside = QPushButton("Try inside Aster anyway")
        try_inside.clicked.connect(lambda _checked=False: self.try_inside_requested.emit())

        buttons = QHBoxLayout()
        buttons.addWidget(launch_button)
        buttons.addWidget(reset_button)
        buttons.addWidget(raw_button)
        buttons.addWidget(try_inside)

        layout.addWidget(title_label)
        layout.addWidget(badge_label)
        layout.addSpacing(8)
        layout.addWidget(url_label)
        layout.addSpacing(12)
        layout.addWidget(intro_label)
        layout.addSpacing(8)
        layout.addWidget(reason_label)
        layout.addSpacing(8)
        layout.addWidget(self.runtime_label)
        layout.addWidget(self.profile_label)
        layout.addWidget(self.status_label)
        layout.addSpacing(16)
        layout.addLayout(buttons)
        layout.addStretch(1)

    def set_runtime_hint(self, text: str) -> None:
        self.runtime_label.setText(f"Capsule runtime: {text}")

    def set_profile_hint(self, text: str) -> None:
        self.profile_label.setText(f"Capsule profile: {text}")

    def set_status_text(self, text: str) -> None:
        self.status_label.setText(f"Launch status: {text}")


class ParkedTabWidget(QWidget):
    restore_requested = pyqtSignal()
    lite_requested = pyqtSignal()
    close_requested = pyqtSignal()

    def __init__(self, title: str, url: str, container: str, reason: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(28, 28, 28, 28)
        title_label = QLabel(title or "Parked Tab")
        title_label.setStyleSheet("font-size: 22px; font-weight: 600;")
        url_label = QLabel(url)
        url_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        info = QLabel(f"Container: {container}\nReason: {reason or 'manual or memory pressure'}")
        info.setWordWrap(True)
        restore = QPushButton("Restore full tab")
        restore.clicked.connect(lambda _checked=False: self.restore_requested.emit())
        lite = QPushButton("Open in Lite mode")
        lite.clicked.connect(lambda _checked=False: self.lite_requested.emit())
        close_button = QPushButton("Close tab")
        close_button.clicked.connect(lambda _checked=False: self.close_requested.emit())
        buttons = QHBoxLayout()
        buttons.addWidget(restore)
        buttons.addWidget(lite)
        buttons.addWidget(close_button)
        layout.addWidget(title_label)
        layout.addWidget(url_label)
        layout.addSpacing(8)
        layout.addWidget(info)
        layout.addSpacing(12)
        layout.addLayout(buttons)
        layout.addStretch(1)


class LiteWorker(QObject):
    loaded = pyqtSignal(object)
    failed = pyqtSignal(str)
    finished = pyqtSignal()

    def __init__(self, url: str) -> None:
        super().__init__()
        self.url = url

    def run(self) -> None:
        try:
            page = render_lite_page(self.url)
        except Exception as exc:
            self.failed.emit(str(exc))
        else:
            self.loaded.emit(page)
        finally:
            self.finished.emit()


class LitePageWidget(QWidget):
    open_full_requested = pyqtSignal(str)
    open_link_requested = pyqtSignal(str)
    title_changed = pyqtSignal(str)
    status_message = pyqtSignal(str)

    def __init__(self, url: str, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.url = url
        self.page: LitePage | None = None
        self.thread: QThread | None = None
        self.worker: LiteWorker | None = None
        self._build_ui()
        self.load_url(url)

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        controls = QHBoxLayout()
        self.url_label = QLabel(self.url)
        self.url_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        reload_button = QPushButton("Reload Lite")
        reload_button.clicked.connect(lambda _checked=False: self.load_url(self.url))
        full_button = QPushButton("Open full site")
        full_button.clicked.connect(lambda _checked=False: self.open_full_requested.emit(self.url))
        controls.addWidget(self.url_label, 1)
        controls.addWidget(reload_button)
        controls.addWidget(full_button)
        self.viewer = QTextBrowser()
        self.viewer.setOpenLinks(False)
        self.viewer.anchorClicked.connect(lambda qurl: self.open_link_requested.emit(qurl.toString()))
        layout.addLayout(controls)
        layout.addWidget(self.viewer, 1)

    def load_url(self, url: str) -> None:
        self.url = url
        self.url_label.setText(url)
        self.viewer.setHtml("<h2>Loading Lite page…</h2>")
        self.thread = QThread(self)
        self.worker = LiteWorker(url)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.loaded.connect(self._on_loaded)
        self.worker.failed.connect(self._on_failed)
        self.worker.finished.connect(self.thread.quit)
        self.worker.finished.connect(self.worker.deleteLater)
        self.thread.finished.connect(self.thread.deleteLater)
        self.thread.start()

    def _on_loaded(self, page: LitePage) -> None:
        self.page = page
        self.title_changed.emit(page.title)
        self.status_message.emit(f"Lite page loaded: {page.title}")
        body = f"""
        <html>
          <body style="font-family: sans-serif; line-height: 1.45; max-width: 860px; margin: 12px auto;">
            {page.html}
          </body>
        </html>
        """
        self.viewer.setHtml(body)

    def _on_failed(self, message: str) -> None:
        self.viewer.setHtml(f"<h2>Lite mode error</h2><p>{html.escape(message)}</p>")
        self.status_message.emit(f"Lite mode failed: {message}")


class AIWorker(QObject):
    result_ready = pyqtSignal(str)
    failed = pyqtSignal(str)
    finished = pyqtSignal()

    def __init__(self, broker: AIBroker, prompt: str, context: AIContext) -> None:
        super().__init__()
        self.broker = broker
        self.prompt = prompt
        self.context = context

    def run(self) -> None:
        try:
            result = self.broker.ask(self.prompt, self.context)
        except Exception as exc:
            self.failed.emit(str(exc))
        else:
            self.result_ready.emit(result)
        finally:
            self.finished.emit()


class AIDockWidget(QDockWidget):
    def __init__(
        self,
        broker_provider: Callable[[], AIBroker],
        command_router: CommandRouter,
        intent_router: IntentRouter,
        context_provider: Callable[[], AIContext],
        allow_actions_default: bool = False,
        parent: QWidget | None = None,
    ) -> None:
        super().__init__("AI", parent)
        self.broker_provider = broker_provider
        self.command_router = command_router
        self.intent_router = intent_router
        self.context_provider = context_provider
        self.thread: QThread | None = None
        self.worker: AIWorker | None = None
        self._build_ui(allow_actions_default)

    def _build_ui(self, allow_actions_default: bool) -> None:
        host = QWidget()
        layout = QVBoxLayout(host)
        self.transcript = QTextEdit()
        self.transcript.setReadOnly(True)
        self.transcript.setPlaceholderText(
            "Ask the built-in local assistant to summarize this page, compare tabs, list open tabs, search your documents/history/bookmarks, manage extensions with /extensions, use /help or /capsules, or enable AI actions to say things like 'open prime video in this tab and park the other tabs'."
        )
        self.entry = QLineEdit()
        self.entry.setPlaceholderText("Ask Aster, use /help, /tabs, /extensions, /bookmarks, or say 'bookmark this page'")
        self.entry.returnPressed.connect(self.submit)
        self.allow_actions = QCheckBox("Allow AI actions (tabs/bookmarks/open/search/find/docs/downloads/navigation)")
        self.allow_actions.setChecked(allow_actions_default)
        send_button = QPushButton("Send")
        send_button.clicked.connect(self.submit)
        help_button = QPushButton("Commands")
        help_button.clicked.connect(lambda _checked=False: self.append_assistant(self.command_router.execute("/help") or "No commands available."))
        clear_button = QPushButton("Clear")
        clear_button.clicked.connect(self.transcript.clear)
        row = QHBoxLayout()
        row.addWidget(self.entry, 1)
        row.addWidget(send_button)
        row.addWidget(help_button)
        row.addWidget(clear_button)
        layout.addWidget(self.transcript, 1)
        layout.addWidget(self.allow_actions)
        layout.addLayout(row)
        self.setWidget(host)

    def set_allow_actions_default(self, value: bool) -> None:
        self.allow_actions.setChecked(value)

    def append_user(self, text: str) -> None:
        self.transcript.append(f"<p><b>You:</b> {html.escape(text)}</p>")

    def append_assistant(self, text: str) -> None:
        self.transcript.append(f"<p><b>Aster:</b> {html.escape(text)}</p>")

    def submit(self) -> None:
        text = self.entry.text().strip()
        if not text:
            return
        self.append_user(text)
        self.entry.clear()
        command_result = self.command_router.execute(text)
        if command_result is not None:
            self.append_assistant(command_result)
            return
        if self.allow_actions.isChecked():
            intent_result = self.intent_router.execute(text)
            if intent_result is not None:
                self.append_assistant(intent_result)
                return
        try:
            broker = self.broker_provider()
            context = self.context_provider()
        except Exception as exc:
            self.append_assistant(f"Broker setup failed: {exc}")
            return
        self.thread = QThread(self)
        self.worker = AIWorker(broker, text, context)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.result_ready.connect(self.append_assistant)
        self.worker.failed.connect(lambda msg: self.append_assistant(f"AI error: {msg}"))
        self.worker.finished.connect(self.thread.quit)
        self.worker.finished.connect(self.worker.deleteLater)
        self.thread.finished.connect(self.thread.deleteLater)
        self.thread.start()


class ExtensionManagerDialog(QDialog):
    def __init__(
        self,
        list_provider: Callable[[], list[object]],
        install_file_callback: Callable[[], str],
        install_folder_callback: Callable[[], str],
        toggle_callback: Callable[[str], str],
        remove_callback: Callable[[str], str],
        reload_callback: Callable[[], str],
        parent: QWidget | None = None,
    ) -> None:
        super().__init__(parent)
        self.setWindowTitle("Aster Extensions")
        self.resize(820, 520)
        self.list_provider = list_provider
        self.install_file_callback = install_file_callback
        self.install_folder_callback = install_folder_callback
        self.toggle_callback = toggle_callback
        self.remove_callback = remove_callback
        self.reload_callback = reload_callback
        self._build_ui()
        self.refresh_list()

    def _build_ui(self) -> None:
        layout = QVBoxLayout(self)
        intro = QLabel(
            "Import unpacked extensions, .zip, .xpi, or .crx packages. Supported extension pages can also show an Add to Aster button for direct package downloads. Aster defaults to a safe extension backend for stability; you can switch to native_when_available in Settings when you want Qt WebEngine to try loading Manifest V3 extensions directly."
        )
        intro.setWordWrap(True)
        self.list_widget = QListWidget()
        self.list_widget.itemSelectionChanged.connect(self._update_buttons)
        self.status_label = QLabel("Select an extension to enable, disable, or remove it.")
        self.status_label.setWordWrap(True)

        button_row = QHBoxLayout()
        self.install_file_button = QPushButton("Install file")
        self.install_file_button.clicked.connect(self._install_file)
        self.install_folder_button = QPushButton("Install folder")
        self.install_folder_button.clicked.connect(self._install_folder)
        self.toggle_button = QPushButton("Enable / disable")
        self.toggle_button.clicked.connect(self._toggle_selected)
        self.remove_button = QPushButton("Remove")
        self.remove_button.clicked.connect(self._remove_selected)
        self.reload_button = QPushButton("Reload runtime")
        self.reload_button.clicked.connect(self._reload_runtime)
        for button in [self.install_file_button, self.install_folder_button, self.toggle_button, self.remove_button, self.reload_button]:
            button_row.addWidget(button)

        close_buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Close)
        close_buttons.rejected.connect(self.reject)
        close_buttons.accepted.connect(self.accept)

        layout.addWidget(intro)
        layout.addWidget(self.list_widget, 1)
        layout.addWidget(self.status_label)
        layout.addLayout(button_row)
        layout.addWidget(close_buttons)
        self._update_buttons()

    def refresh_list(self) -> None:
        self.list_widget.clear()
        records = self.list_provider()
        if not records:
            self.status_label.setText(
                "No extensions installed yet. Use Install file for .zip/.xpi/.crx packages or Install folder for an unpacked extension directory."
            )
        for record in records:
            name = getattr(record, "name", "Extension")
            version = getattr(record, "version", "")
            enabled = bool(getattr(record, "enabled", False))
            runtime_kind = getattr(record, "runtime_kind", "unknown")
            source_kind = getattr(record, "source_kind", "unknown")
            status = getattr(record, "status", "ready")
            note = getattr(record, "note", "")
            ext_id = getattr(record, "ext_id", name)
            header = f"{name} {version} [{'enabled' if enabled else 'disabled'} | {runtime_kind} | {source_kind} | {status}]"
            body = f"{header}\n{note}" if note else header
            item = QListWidgetItem(body)
            item.setData(Qt.ItemDataRole.UserRole, ext_id)
            self.list_widget.addItem(item)
        self._update_buttons()

    def _selected_ext_id(self) -> str:
        item = self.list_widget.currentItem()
        if item is None:
            return ""
        value = item.data(Qt.ItemDataRole.UserRole)
        return str(value or "")

    def _update_buttons(self) -> None:
        has_selection = bool(self._selected_ext_id())
        self.toggle_button.setEnabled(has_selection)
        self.remove_button.setEnabled(has_selection)

    def _install_file(self) -> None:
        message = self.install_file_callback()
        self.status_label.setText(message)
        self.refresh_list()

    def _install_folder(self) -> None:
        message = self.install_folder_callback()
        self.status_label.setText(message)
        self.refresh_list()

    def _toggle_selected(self) -> None:
        ext_id = self._selected_ext_id()
        if not ext_id:
            return
        message = self.toggle_callback(ext_id)
        self.status_label.setText(message)
        self.refresh_list()

    def _remove_selected(self) -> None:
        ext_id = self._selected_ext_id()
        if not ext_id:
            return
        message = self.remove_callback(ext_id)
        self.status_label.setText(message)
        self.refresh_list()

    def _reload_runtime(self) -> None:
        message = self.reload_callback()
        self.status_label.setText(message)
        self.refresh_list()


class SettingsFormWidget(QWidget):
    def __init__(self, config: BrowserConfig, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.original = copy.deepcopy(config)
        self._build_ui(config)

    def _build_ui(self, config: BrowserConfig) -> None:
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(14)

        general_section = QFrame(self)
        general_section.setObjectName("AsterFormSection")
        general_layout = QVBoxLayout(general_section)
        general_layout.setContentsMargins(18, 18, 18, 18)
        general_layout.setSpacing(12)
        general_title = QLabel("General")
        general_title.setObjectName("AsterSectionTitle")
        general_note = QLabel("Homepage, search, theme, tabs, and basic browsing behavior.")
        general_note.setObjectName("AsterMutedLabel")
        general_form = QFormLayout()

        self.homepage_edit = QLineEdit(config.homepage)
        self.search_engine_edit = QLineEdit(config.search_engine)
        self.theme_combo = QComboBox(); self.theme_combo.addItems(["black_arc", "system"]); self.theme_combo.setCurrentText(config.theme if config.theme in {"black_arc", "system"} else "black_arc")
        self.security_combo = QComboBox(); self.security_combo.addItems(["strict", "balanced", "permissive"]); self.security_combo.setCurrentText(config.security_mode)
        self.max_tabs_spin = QSpinBox(); self.max_tabs_spin.setRange(1, 100); self.max_tabs_spin.setValue(config.max_live_tabs)
        self.soft_budget_spin = QSpinBox(); self.soft_budget_spin.setRange(256, 32768); self.soft_budget_spin.setValue(config.soft_memory_budget_mb)
        self.auto_park_check = QCheckBox(); self.auto_park_check.setChecked(config.auto_park)
        self.left_click_menu_check = QCheckBox(); self.left_click_menu_check.setChecked(getattr(config, "active_tab_left_click_menu", True))

        general_form.addRow("Homepage", self.homepage_edit)
        general_form.addRow("Search engine", self.search_engine_edit)
        general_form.addRow("Theme", self.theme_combo)
        general_form.addRow("Security mode", self.security_combo)
        general_form.addRow("Max live tabs", self.max_tabs_spin)
        general_form.addRow("Soft budget (MB)", self.soft_budget_spin)
        general_form.addRow("Auto-park tabs", self.auto_park_check)
        general_form.addRow("Active-tab left click menu", self.left_click_menu_check)
        general_layout.addWidget(general_title)
        general_layout.addWidget(general_note)
        general_layout.addLayout(general_form)

        browsing_section = QFrame(self)
        browsing_section.setObjectName("AsterFormSection")
        browsing_layout = QVBoxLayout(browsing_section)
        browsing_layout.setContentsMargins(18, 18, 18, 18)
        browsing_layout.setSpacing(12)
        browsing_title = QLabel("Browsing and streaming")
        browsing_title.setObjectName("AsterSectionTitle")
        browsing_note = QLabel("Ad blocking, containers, rendering, and streaming compatibility.")
        browsing_note.setObjectName("AsterMutedLabel")
        browsing_form = QFormLayout()
        self.adblock_check = QCheckBox(); self.adblock_check.setChecked(config.adblock_enabled)
        self.strict_adblock_check = QCheckBox(); self.strict_adblock_check.setChecked(config.strict_adblock)
        self.container_edit = QLineEdit(", ".join(config.containers))
        self.default_container_edit = QLineEdit(config.default_container)
        self.extension_backend_combo = QComboBox(); self.extension_backend_combo.addItems(["safe", "native_when_available"]); self.extension_backend_combo.setCurrentText(config.extension_backend)
        self.force_software_check = QCheckBox(); self.force_software_check.setChecked(config.force_software_rendering)
        self.drm_mode_combo = QComboBox(); self.drm_mode_combo.addItems(["internal_preferred", "auto", "internal_only", "external_only"]); self.drm_mode_combo.setCurrentText(config.drm_mode)
        self.external_browser_edit = QLineEdit(config.external_browser_cmd)
        browsing_form.addRow("Adblock", self.adblock_check)
        browsing_form.addRow("Strict adblock", self.strict_adblock_check)
        browsing_form.addRow("Containers", self.container_edit)
        browsing_form.addRow("Default container", self.default_container_edit)
        browsing_form.addRow("Extension backend", self.extension_backend_combo)
        browsing_form.addRow("Force software rendering", self.force_software_check)
        browsing_form.addRow("DRM mode", self.drm_mode_combo)
        browsing_form.addRow("Capsule/browser override", self.external_browser_edit)
        browsing_layout.addWidget(browsing_title)
        browsing_layout.addWidget(browsing_note)
        browsing_layout.addLayout(browsing_form)

        ai_section = QFrame(self)
        ai_section.setObjectName("AsterFormSection")
        ai_layout = QVBoxLayout(ai_section)
        ai_layout.setContentsMargins(18, 18, 18, 18)
        ai_layout.setSpacing(12)
        ai_title = QLabel("Assistant")
        ai_title.setObjectName("AsterSectionTitle")
        ai_note = QLabel("The internal assistant is local-first. External providers remain optional.")
        ai_note.setObjectName("AsterMutedLabel")
        ai_form = QFormLayout()
        self.allow_ai_actions_check = QCheckBox(); self.allow_ai_actions_check.setChecked(config.allow_ai_actions)
        self.ai_context_chars_spin = QSpinBox(); self.ai_context_chars_spin.setRange(1000, 20000); self.ai_context_chars_spin.setSingleStep(500); self.ai_context_chars_spin.setValue(config.ai_page_context_chars)
        self.ai_provider_combo = QComboBox(); self.ai_provider_combo.addItems(["internal", "disabled", "ollama", "openai", "openai_compatible"]); self.ai_provider_combo.setCurrentText(config.ai.provider)
        self.ai_model_edit = QLineEdit(config.ai.model)
        self.ai_base_url_edit = QLineEdit(config.ai.base_url)
        self.ai_api_key_env_edit = QLineEdit(config.ai.api_key_env)
        ai_form.addRow("Allow AI actions by default", self.allow_ai_actions_check)
        ai_form.addRow("Page context chars", self.ai_context_chars_spin)
        ai_form.addRow("AI provider", self.ai_provider_combo)
        ai_form.addRow("AI model", self.ai_model_edit)
        ai_form.addRow("AI base URL", self.ai_base_url_edit)
        ai_form.addRow("AI key env var", self.ai_api_key_env_edit)
        ai_layout.addWidget(ai_title)
        ai_layout.addWidget(ai_note)
        ai_layout.addLayout(ai_form)

        layout.addWidget(general_section)
        layout.addWidget(browsing_section)
        layout.addWidget(ai_section)
        layout.addStretch(1)

    def build_config(self) -> BrowserConfig:
        containers = [item.strip() for item in self.container_edit.text().split(",") if item.strip()]
        if not containers:
            containers = ["default"]
        default_container = self.default_container_edit.text().strip() or containers[0]
        if default_container not in containers:
            containers.insert(0, default_container)
        return BrowserConfig(
            homepage=self.homepage_edit.text().strip() or "aster:newtab",
            search_engine=self.search_engine_edit.text().strip() or "https://duckduckgo.com/?q={query}",
            adblock_enabled=self.adblock_check.isChecked(),
            strict_adblock=self.strict_adblock_check.isChecked(),
            security_mode=self.security_combo.currentText(),
            max_live_tabs=int(self.max_tabs_spin.value()),
            soft_memory_budget_mb=int(self.soft_budget_spin.value()),
            auto_park=self.auto_park_check.isChecked(),
            default_container=default_container,
            hardware_acceleration=self.original.hardware_acceleration,
            force_software_rendering=self.force_software_check.isChecked(),
            containers=containers,
            drm_mode=self.drm_mode_combo.currentText(),
            external_browser_cmd=self.external_browser_edit.text().strip(),
            allow_ai_actions=self.allow_ai_actions_check.isChecked(),
            ai_page_context_chars=int(self.ai_context_chars_spin.value()),
            theme=self.theme_combo.currentText(),
            extension_backend=self.extension_backend_combo.currentText(),
            active_tab_left_click_menu=self.left_click_menu_check.isChecked(),
            ai=AIConfig(
                provider=self.ai_provider_combo.currentText(),
                model=self.ai_model_edit.text().strip() or "aster-internal",
                base_url=self.ai_base_url_edit.text().strip(),
                api_key_env=self.ai_api_key_env_edit.text().strip(),
                system_prompt=self.original.ai.system_prompt,
            ),
        )


class SettingsPageWidget(QWidget):
    save_requested = pyqtSignal(object)
    cancel_requested = pyqtSignal()

    def __init__(self, config: BrowserConfig, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(28, 24, 28, 24)
        outer.addStretch(1)
        row = QHBoxLayout()
        row.addStretch(1)
        shell = QFrame(self)
        shell.setObjectName("AsterPageShell")
        shell.setMaximumWidth(980)
        shell_layout = QVBoxLayout(shell)
        shell_layout.setContentsMargins(24, 24, 24, 24)
        card = QFrame(shell)
        card.setObjectName("AsterSettingsCard")
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(26, 24, 26, 24)
        card_layout.setSpacing(16)
        title = QLabel("Aster Settings", card)
        title.setObjectName("AsterSettingsTitle")
        subtitle = QLabel("A centered settings page inside the browser. Changes apply immediately where possible.", card)
        subtitle.setObjectName("AsterSettingsSubtitle")
        subtitle.setWordWrap(True)
        scroll = QScrollArea(card)
        scroll.setObjectName("AsterSettingsScroll")
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.form_widget = SettingsFormWidget(config, scroll)
        scroll.setWidget(self.form_widget)
        buttons = QHBoxLayout()
        buttons.addStretch(1)
        cancel = QPushButton("Close", card)
        cancel.setObjectName("SecondaryButton")
        cancel.clicked.connect(lambda _checked=False: self.cancel_requested.emit())
        save = QPushButton("Save settings", card)
        save.clicked.connect(lambda _checked=False: self.save_requested.emit(self.form_widget.build_config()))
        buttons.addWidget(cancel)
        buttons.addWidget(save)
        card_layout.addWidget(title)
        card_layout.addWidget(subtitle)
        card_layout.addWidget(scroll, 1)
        card_layout.addLayout(buttons)
        shell_layout.addWidget(card)
        row.addWidget(shell, 1)
        row.addStretch(1)
        outer.addLayout(row)
        outer.addStretch(1)


class SettingsDialog(QDialog):
    def __init__(self, config: BrowserConfig, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Aster Settings")
        self.resize(860, 720)
        layout = QVBoxLayout(self)
        self.form_widget = SettingsFormWidget(config, self)
        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.Shape.NoFrame)
        scroll.setWidget(self.form_widget)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(scroll, 1)
        layout.addWidget(buttons)

    def build_config(self) -> BrowserConfig:
        return self.form_widget.build_config()

PYWIDGETS

cat > "$APP_DIR/browser.py" <<'PYBROWSER'
from __future__ import annotations

import socket
import time
from pathlib import Path
from typing import Any, cast
from urllib.parse import parse_qs, unquote, urlparse

from PyQt6.QtCore import QEvent, QPoint, QSize, QTimer, QUrl, Qt
from PyQt6.QtGui import QAction, QCloseEvent, QDesktopServices, QKeySequence
from PyQt6.QtWidgets import QApplication, QFileDialog, QLineEdit, QMainWindow, QMenu, QMessageBox, QProgressBar, QStatusBar, QTabWidget, QToolBar, QComboBox, QToolButton, QWidget
from PyQt6.QtWebEngineCore import QWebEnginePage
from PyQt6.QtWebEngineWidgets import QWebEngineView

from .adblock import AdblockInterceptor, AdblockRuleset
from .ai import AIBroker, AIContext, CommandRouter, IntentRouter, build_search_url
from .capsules import CapsuleLaunchResult, StreamingCapsuleManager
from .extensions import WebExtensionRegistry
from .config import BrowserConfig, load_config, save_config
from .containers import ContainerManager
from .memory import MemoryGovernor
from .models import TabState
from .network import WireGuardHelper
from .paths import bundled_plugins_dir, downloads_dir, rules_dir, user_plugins_dir
from .plugins import AppAPI, PluginManager
from .security import apply_security_profile
from .streaming import StreamingSupport, detect_streaming_support, is_streaming_service, known_drm_service, open_external, redirect_reason, should_redirect_externally
from .widgets import AIDockWidget, AsterTabBar, AsterTitleBar, CompatibilityRedirectWidget, ErrorPageWidget, ExtensionManagerDialog, LitePageWidget, MobileViewWidget, NewTabWidget, ParkedTabWidget, SettingsPageWidget, StreamingCapsuleWidget
from .icons import svg_icon
from .local_assistant import LocalAssistant
from .local_index import LocalIndex
from .theme import stylesheet_for


class BrowserPage(QWebEnginePage):
    def __init__(self, browser: "BrowserWindow", container_name: str, profile, parent=None) -> None:
        super().__init__(profile, parent)
        self.browser = browser
        self.container_name = container_name

    def createWindow(self, _type):  # type: ignore[override]
        state = self.browser.open_web_tab("about:blank", container=self.container_name, background=False)
        view = self.browser._extract_web_view(state.widget)
        if view is not None:
            return view.page()
        return cast(QWebEnginePage, super().createWindow(_type))

    def acceptNavigationRequest(self, url, nav_type, is_main_frame):  # type: ignore[override]
        try:
            parsed = urlparse(url.toString())
        except Exception:
            parsed = None
        if parsed is not None and parsed.scheme == "aster" and parsed.netloc == "install-extension":
            params = parse_qs(parsed.query)
            source = unquote((params.get("source") or [""])[0])
            label = unquote((params.get("label") or [""])[0])
            self.browser.install_extension_from_web_url(source, label)
            return False
        return super().acceptNavigationRequest(url, nav_type, is_main_frame)


class BrowserWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.config: BrowserConfig = load_config()
        self.ruleset = AdblockRuleset.from_file(rules_dir() / "default_blocklist.txt", strict=self.config.strict_adblock)
        self.containers = ContainerManager(self.config.containers, self._make_interceptor, self._handle_download)
        self.governor = MemoryGovernor(self.config.soft_memory_budget_mb, self.config.max_live_tabs)
        self.streaming_support: StreamingSupport = detect_streaming_support(self.config.drm_mode, self.config.external_browser_cmd)
        self.capsules = StreamingCapsuleManager(self.config.external_browser_cmd)
        self.local_index = LocalIndex()
        self.extensions = WebExtensionRegistry()
        self.tab_states: dict[str, TabState] = {}
        self._profile_sync_generation: dict[int, int] = {}
        self.previous_tab_id: str | None = None
        self.last_current_tab_id: str | None = None
        self._lite_toggle_guard_until = 0.0
        self._connectivity_cache: tuple[float, bool] | None = None
        self.setObjectName("AsterWindow")
        self.setWindowFlags(Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
        self.setWindowTitle("Aster Browser")
        self.setMinimumSize(1120, 760)
        self.resize(1400, 900)
        self._setup_ui()
        self._setup_menus()
        self._setup_ai_and_plugins()
        self._show_startup_status()
        self.open_target(self.config.homepage)

    def _setup_ui(self) -> None:
        self.title_bar = AsterTitleBar(self)
        self.title_bar.logo_button.setToolTip("Open home page")
        self.title_bar.logo_button.clicked.connect(lambda: self.open_target(self.config.homepage))
        self.setMenuWidget(self.title_bar)

        self.status = QStatusBar(self)
        self.status.setObjectName("AsterStatusBar")
        self.status.setSizeGripEnabled(True)
        self.setStatusBar(self.status)

        self.tabs = QTabWidget(self)
        self.tabs.setObjectName("AsterTabs")
        self.tabs.setDocumentMode(True)
        self.tabs.setTabsClosable(True)
        self.tabs.setMovable(True)
        self.tab_bar = AsterTabBar(self.tabs)
        self.tab_bar.set_active_tab_left_click_menu_enabled(getattr(self.config, "active_tab_left_click_menu", True))
        self.tab_bar.quick_actions_requested.connect(self._show_tab_quick_actions_menu)
        self.tabs.setTabBar(self.tab_bar)
        self.tabs.tabCloseRequested.connect(self._close_tab_index)
        self.tabs.currentChanged.connect(self._on_current_tab_changed)
        self.setCentralWidget(self.tabs)

        toolbar = QToolBar("Navigation", self)
        self.toolbar = toolbar
        toolbar.setObjectName("AsterToolbar")
        toolbar.setMovable(False)
        toolbar.setFloatable(False)
        toolbar.setIconSize(QSize(18, 18))
        toolbar.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonIconOnly)
        self.addToolBar(toolbar)

        self.back_action = QAction(svg_icon("arrow_back"), "Back", self)
        self.back_action.setToolTip("Back")
        self.back_action.triggered.connect(lambda: self._with_current_web_view(lambda view: view.back()))

        self.forward_action = QAction(svg_icon("arrow_forward"), "Forward", self)
        self.forward_action.setToolTip("Forward")
        self.forward_action.triggered.connect(lambda: self._with_current_web_view(lambda view: view.forward()))

        self.reload_action = QAction(svg_icon("reload"), "Reload", self)
        self.reload_action.setToolTip("Reload")
        self.reload_action.triggered.connect(lambda: self._with_current_web_view(lambda view: view.reload()))

        self.home_action = QAction(svg_icon("home"), "Home", self)
        self.home_action.setToolTip("Home")
        self.home_action.triggered.connect(lambda: self.open_target(self.config.homepage))

        self.new_tab_action = QAction(svg_icon("new_tab"), "New Tab", self)
        self.new_tab_action.setToolTip("New tab")
        self.new_tab_action.triggered.connect(lambda: self.open_target(self.config.homepage, background=False))

        self.park_action = QAction(svg_icon("park"), "Park", self)
        self.park_action.setToolTip("Park background tabs")
        self.park_action.triggered.connect(self.park_background_tabs)

        self.lite_action = QAction(svg_icon("lite"), "Lite", self)
        self.lite_action.setToolTip("Open Lite mode")
        self.lite_action.triggered.connect(self.open_lite_current)

        self.mobile_action = QAction(svg_icon("mobile"), "Mobile view", self)
        self.mobile_action.setToolTip("Open the current page in mobile view")
        self.mobile_action.triggered.connect(self.open_mobile_current)

        self.add_from_page_action = QAction(svg_icon("add_page"), "Add from page", self)
        self.add_from_page_action.setToolTip("Install extension from current page")
        self.add_from_page_action.triggered.connect(self.install_extension_from_current_page)

        self.extensions_action = QAction(svg_icon("extensions"), "Extensions", self)
        self.extensions_action.setToolTip("Open extensions manager")
        self.extensions_action.triggered.connect(self.open_extension_manager_dialog)

        self.settings_action = QAction(svg_icon("settings"), "Settings", self)
        self.settings_action.setToolTip("Open settings")
        self.settings_action.triggered.connect(self.open_settings_dialog)

        self.new_tab_action.setShortcut(QKeySequence("Ctrl+T"))
        self.park_action.setShortcut(QKeySequence("Ctrl+Shift+P"))
        self.lite_action.setShortcut(QKeySequence("Ctrl+Shift+L"))
        self.mobile_action.setShortcut(QKeySequence("Ctrl+Shift+M"))
        self.add_from_page_action.setShortcut(QKeySequence("Ctrl+Shift+A"))
        self.extensions_action.setShortcut(QKeySequence("Ctrl+Shift+E"))
        self.settings_action.setShortcut(QKeySequence("Ctrl+,"))

        self.primary_toolbar_actions = [self.back_action, self.forward_action, self.reload_action, self.home_action, self.new_tab_action]
        self.secondary_toolbar_actions = [self.park_action, self.lite_action, self.mobile_action, self.add_from_page_action, self.extensions_action, self.settings_action]
        self.compactable_toolbar_actions = [self.home_action, self.new_tab_action, self.park_action, self.lite_action, self.mobile_action, self.add_from_page_action, self.extensions_action, self.settings_action]

        for act in self.primary_toolbar_actions:
            toolbar.addAction(act)
        toolbar.addSeparator()
        for act in self.secondary_toolbar_actions:
            toolbar.addAction(act)

        self.toolbar_overflow_button = QToolButton(self)
        self.toolbar_overflow_button.setObjectName("ToolbarOverflowButton")
        self.toolbar_overflow_button.setIcon(svg_icon("menu_more"))
        self.toolbar_overflow_button.setToolTip("More tools")
        self.toolbar_overflow_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.toolbar_overflow_menu = QMenu(self)
        self.toolbar_overflow_button.setMenu(self.toolbar_overflow_menu)

        self.container_combo = QComboBox(self)
        self.container_combo.setObjectName("ContainerCombo")
        self.container_combo.addItems(self.containers.available())
        self.container_combo.setCurrentText(self.config.default_container)
        self.container_combo.setToolTip("Open new pages in this container profile")
        toolbar.addWidget(self.container_combo)

        self.url_bar = QLineEdit(self)
        self.url_bar.setObjectName("AddressBar")
        self.url_bar.setPlaceholderText("Search or enter address")
        self.url_bar.returnPressed.connect(self.go_from_address_bar)
        self.url_bar.setClearButtonEnabled(True)
        self.url_bar.setMinimumWidth(520)
        toolbar.addWidget(self.url_bar)

        self.load_progress = QProgressBar(self)
        self.load_progress.setObjectName("LoadProgress")
        self.load_progress.setRange(0, 100)
        self.load_progress.setValue(0)
        self.load_progress.setVisible(False)
        self.load_progress.setFixedWidth(140)
        toolbar.addWidget(self.load_progress)

        self.mode_badge = QLineEdit(self)
        self.mode_badge.setObjectName("ModeBadge")
        self.mode_badge.setReadOnly(True)
        self.mode_badge.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.mode_badge.setMaximumWidth(240)
        self.mode_badge.setText(self._mode_badge_text())
        self.mode_badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
        toolbar.addWidget(self.mode_badge)
        toolbar.addWidget(self.toolbar_overflow_button)
        self.toolbar_overflow_button.setVisible(False)

        self.title_bar.set_context_text("Aster is ready")
        self._update_compact_ui()
        self._apply_responsive_density()

        focus_url_action = QAction(self)
        focus_url_action.setShortcut(QKeySequence("Ctrl+L"))
        focus_url_action.triggered.connect(self.url_bar.setFocus)
        self.addAction(focus_url_action)

        close_tab_action = QAction(self)
        close_tab_action.setShortcut(QKeySequence("Ctrl+W"))
        close_tab_action.triggered.connect(self.close_current_tab)
        self.addAction(close_tab_action)

    def resizeEvent(self, event) -> None:  # type: ignore[override]
        super().resizeEvent(event)
        self._update_compact_ui()
        self._apply_responsive_density()

    def changeEvent(self, event) -> None:  # type: ignore[override]
        super().changeEvent(event)
        if event.type() == QEvent.Type.WindowStateChange:
            self.title_bar.sync_window_state()
            QTimer.singleShot(0, self._update_compact_ui)
            QTimer.singleShot(0, self._apply_responsive_density)

    def _setup_menus(self) -> None:
        file_menu = self.title_bar.file_menu
        view_menu = self.title_bar.view_menu
        tools_menu = self.title_bar.tools_menu
        file_menu.clear()
        view_menu.clear()
        tools_menu.clear()

        new_tab = QAction("New Tab", self)
        new_tab.setShortcut(QKeySequence("Ctrl+T"))
        new_tab.triggered.connect(lambda: self.open_target(self.config.homepage))
        file_menu.addAction(new_tab)
        file_menu.addSeparator()
        file_menu.addAction("Open Downloads", self.open_downloads_folder)
        file_menu.addAction("Settings", self.open_settings_dialog)
        file_menu.addSeparator()
        file_menu.addAction("Quit", self.close)

        view_menu.addAction("Park background tabs", self.park_background_tabs)
        view_menu.addAction("Open current page in Lite", self.open_lite_current)
        view_menu.addAction("Open current page in Mobile view", self.open_mobile_current)
        view_menu.addAction("Return current page to Desktop view", self.open_desktop_current)
        view_menu.addAction("Reload current page", self.reload_current)

        extensions_action = QAction("Extensions", self)
        extensions_action.setShortcut(QKeySequence("Ctrl+Shift+E"))
        extensions_action.triggered.connect(self.open_extension_manager_dialog)
        tools_menu.addAction(extensions_action)

        add_from_page_action = QAction("Add extension from page", self)
        add_from_page_action.setShortcut(QKeySequence("Ctrl+Shift+A"))
        add_from_page_action.triggered.connect(self.install_extension_from_current_page)
        tools_menu.addAction(add_from_page_action)
        tools_menu.addSeparator()
        tools_menu.addAction("Streaming status", lambda: QMessageBox.information(self, "Streaming status", self.streaming_status_text()))
        tools_menu.addAction("Assistant status", lambda: QMessageBox.information(self, "Assistant", "Internal assistant is enabled."))
        self.title_bar._rebuild_compact_menu()

    def _show_startup_status(self) -> None:
        if self.config.drm_mode == "internal_preferred" and not self.streaming_support.internal_drm_available():
            self.show_message("Widevine was not detected. Protected streaming can still fall back to Aster's compatibility path.", 9000)
        elif self.config.drm_mode == "internal_preferred":
            self.show_message("Widevine detected. Aster will try protected playback inside the browser first.", 7000)

    def _mode_badge_text(self) -> str:
        return f"DRM: {self.config.drm_mode} | Ext: {self.config.extension_backend}"

    def _extension_backend_native_enabled(self) -> bool:
        return self.config.extension_backend == "native_when_available"

    def _sync_profile_extensions(self, profile: Any) -> None:
        key = id(profile)
        desired_generation = self.extensions.generation() * 10 + (1 if self._extension_backend_native_enabled() else 0)
        if self._profile_sync_generation.get(key) == desired_generation:
            return
        self.extensions.sync_profile(profile, notifier=self.show_message, native_enabled=self._extension_backend_native_enabled())
        self._profile_sync_generation[key] = desired_generation

    def _extension_overlay_allowed(self, url: str) -> bool:
        try:
            host = (urlparse(url).hostname or "").lower()
        except Exception:
            return False
        if host.endswith("addons.mozilla.org") or host.endswith("open-vsx.org") or host.endswith("github.com") or host.endswith("gitlab.com") or host.endswith("codeberg.org"):
            return True
        return any(token in url.lower() for token in (".xpi", ".crx", ".zip", "extension", "addon"))

    def _extract_web_view(self, widget: QWidget | None) -> QWebEngineView | None:
        if isinstance(widget, QWebEngineView):
            return widget
        if hasattr(widget, 'web_view'):
            try:
                candidate = widget.web_view()
            except Exception:
                candidate = None
            if isinstance(candidate, QWebEngineView):
                return candidate
        return None

    def _responsive_scale(self) -> float:
        width = max(960, self.width())
        scale = 1.0
        if width >= 1500:
            scale = 1.16
        elif width >= 1320:
            scale = 1.10
        elif width <= 1160:
            scale = 0.97
        if self.isMaximized():
            scale += 0.03
        return max(0.95, min(scale, 1.22))

    def _apply_responsive_density(self) -> None:
        scale = self._responsive_scale()
        icon_px = max(18, int(round(18 * scale)))
        self.toolbar.setIconSize(QSize(icon_px, icon_px))
        self.toolbar_overflow_button.setIconSize(QSize(icon_px, icon_px))
        self.title_bar.apply_visual_scale(scale)
        for index in range(self.tabs.count()):
            widget = self.tabs.widget(index)
            if isinstance(widget, NewTabWidget):
                widget.apply_density_for_width(widget.width() or self.tabs.width() or self.width())

    def _set_action_widget_visible(self, action: QAction, visible: bool) -> None:
        widget = self.toolbar.widgetForAction(action)
        if widget is not None:
            widget.setVisible(visible)

    def _rebuild_toolbar_overflow_menu(self, actions: list[QAction]) -> None:
        self.toolbar_overflow_menu.clear()
        if not actions:
            self.toolbar_overflow_button.setVisible(False)
            return
        for action in actions:
            self.toolbar_overflow_menu.addAction(action)
        self.toolbar_overflow_button.setVisible(True)

    def _update_compact_ui(self) -> None:
        width = self.width()
        overflow: list[QAction] = []
        if width < 1320:
            overflow.extend([self.add_from_page_action, self.extensions_action, self.settings_action])
        if width < 1160:
            overflow.extend([self.park_action, self.lite_action, self.mobile_action])
        if width < 980:
            overflow.extend([self.home_action, self.new_tab_action])
        overflow_ids = {id(action) for action in overflow}
        for action in self.compactable_toolbar_actions:
            self._set_action_widget_visible(action, id(action) not in overflow_ids)
        self._rebuild_toolbar_overflow_menu(overflow)
        self.container_combo.setVisible(width >= 940)
        self.mode_badge.setVisible(width >= 1080)
        self.title_bar.apply_compact_mode(width < 1120)

    def _show_tab_quick_actions_menu(self, index: int, global_pos: QPoint) -> None:
        state = self._state_for_index(index)
        if state is None:
            return
        self.tabs.setCurrentIndex(index)
        menu = QMenu(self)
        heading = menu.addSection(state.title or state.url or f"Tab {index + 1}")
        heading.setEnabled(False)
        view = self._extract_web_view(state.widget)
        is_muted = state.audio_muted
        if view is not None:
            try:
                is_muted = bool(view.page().isAudioMuted())
            except Exception:
                is_muted = state.audio_muted
        mute_action = menu.addAction("Unmute sound" if is_muted else "Mute sound")
        mute_action.setEnabled(view is not None)
        reload_action = menu.addAction("Reload")
        reload_action.setEnabled(view is not None)
        duplicate_action = menu.addAction("Duplicate tab")
        mobile_action = menu.addAction("Return to desktop view" if state.mobile_mode else "Open mobile view")
        lite_action = menu.addAction("Open in Lite mode")
        if state.kind == 'parked':
            lite_action.setEnabled(False)
        park_action = menu.addAction("Park this tab")
        park_action.setEnabled(state.kind == 'web' and not state.mobile_mode)
        menu.addSeparator()
        left_click_label = "Disable left-click tab menu" if getattr(self.config, "active_tab_left_click_menu", True) else "Enable left-click tab menu"
        left_click_toggle_action = menu.addAction(left_click_label)
        menu.addSeparator()
        close_others_action = menu.addAction("Close other tabs")
        close_action = menu.addAction("Close tab")
        chosen = menu.exec(global_pos)
        if chosen == mute_action:
            self.toggle_tab_audio(state.tab_id)
        elif chosen == reload_action and view is not None:
            view.reload()
        elif chosen == duplicate_action:
            self.tabs.setCurrentIndex(index)
            self.duplicate_current_tab()
        elif chosen == mobile_action:
            if state.mobile_mode:
                self._open_desktop_for_tab(state.tab_id)
            else:
                self.open_mobile_for_tab(state.tab_id)
        elif chosen == lite_action:
            self.open_lite_for_tab(state.tab_id)
        elif chosen == park_action:
            self._park_tab(state.tab_id, "parked from tab quick actions")
            self._refresh_visibility()
        elif chosen == left_click_toggle_action:
            next_value = not bool(getattr(self.config, "active_tab_left_click_menu", True))
            self.config.active_tab_left_click_menu = next_value
            save_config(self.config)
            self.tab_bar.set_active_tab_left_click_menu_enabled(next_value)
            self.show_message(f"{'Enabled' if next_value else 'Disabled'} active-tab left-click menu.")
        elif chosen == close_others_action:
            self.tabs.setCurrentIndex(index)
            self.close_other_tabs()
        elif chosen == close_action:
            self.close_tab_by_id(state.tab_id)

    def toggle_tab_audio(self, tab_id: str) -> bool:
        state = self.tab_states.get(tab_id)
        if state is None:
            return False
        view = self._extract_web_view(state.widget)
        if view is None:
            return False
        try:
            next_value = not bool(view.page().isAudioMuted())
        except Exception:
            next_value = not state.audio_muted
        try:
            view.page().setAudioMuted(next_value)
        except Exception:
            return False
        state.audio_muted = next_value
        index = self.tabs.indexOf(state.widget)
        if index >= 0:
            self.tabs.setTabText(index, self._tab_label(state))
        self.show_message(f"{'Muted' if next_value else 'Unmuted'} tab: {state.title or state.url}")
        return True

    def open_mobile_current(self) -> bool:
        state = self.current_state()
        if state is None or not state.url or state.url == 'aster:newtab':
            return False
        self.open_web_tab(state.url, state.container, replace_tab_id=state.tab_id, mobile_mode=True)
        return True

    def open_mobile_for_tab(self, tab_id: str) -> bool:
        state = self.tab_states.get(tab_id)
        if state is None or not state.url or state.url == 'aster:newtab':
            return False
        current = self.current_state()
        background = current is not None and current.tab_id != tab_id
        self.open_web_tab(state.url, state.container, background=background, replace_tab_id=tab_id, mobile_mode=True)
        return True

    def _open_desktop_for_tab(self, tab_id: str) -> bool:
        state = self.tab_states.get(tab_id)
        if state is None or not state.url or state.url == 'aster:newtab':
            return False
        current = self.current_state()
        background = current is not None and current.tab_id != tab_id
        self.open_web_tab(state.url, state.container, background=background, replace_tab_id=tab_id, mobile_mode=False)
        return True

    def open_desktop_current(self) -> bool:
        state = self.current_state()
        if state is None:
            return False
        return self._open_desktop_for_tab(state.tab_id)

    def _setup_ai_and_plugins(self) -> None:
        actions = {
            "open_url": lambda url: self.open_target(url),
            "open_in_current": self.open_in_current_tab,
            "open_external": self.open_external_target,
            "open_capsule": self.open_capsule_target,
            "search": self.search,
            "find_in_page": self.find_in_page,
            "search_local_documents": self.search_local_documents,
            "search_local_pdfs": self.search_local_pdfs,
            "search_history": self.search_history,
            "open_downloads": self.open_downloads_folder,
            "summarize_selection_or_page": self.summarize_selection_or_page,
            "compare_tabs": self.compare_tabs,
            "list_tabs": self.list_tabs,
            "switch_tab": self.switch_tab,
            "close_current_tab": self.close_current_tab_action,
            "close_tabs_matching": self.close_tabs_matching,
            "close_other_tabs": self.close_other_tabs,
            "bookmark_current_page": self.bookmark_current_page,
            "search_bookmarks": self.search_bookmarks,
            "open_first_bookmark": self.open_first_bookmark,
            "open_first_document": self.open_first_document,
            "open_first_pdf": self.open_first_pdf,
            "list_page_links": self.list_page_links,
            "open_page_link": self.open_page_link,
            "navigate_back": self.navigate_back,
            "navigate_forward": self.navigate_forward,
            "reload_current": self.reload_current,
            "duplicate_current_tab": self.duplicate_current_tab,
            "park_background_tabs": self.park_background_tabs,
            "open_lite_current": self.open_lite_current,
            "open_mobile_current": self.open_mobile_current,
            "open_desktop_current": self.open_desktop_current,
            "get_current_container": self.active_container,
            "set_container": self.set_active_container,
            "stats": self.stats_text,
            "streaming_status": self.streaming_status_text,
            "capsule_status": self.capsule_status_text,
            "wireguard_status": self.wireguard_status,
            "list_extensions": self.list_extensions_text,
            "install_extension": self.install_extension_path,
            "install_extension_from_page": self.install_extension_from_current_page_command,
            "toggle_extension": self.toggle_extension_query,
            "remove_extension": self.remove_extension_query,
            "reload_extensions": self.reload_extensions_runtime,
        }
        self.command_router = CommandRouter(actions)
        self.intent_router = IntentRouter(actions)
        self.ai_dock = AIDockWidget(self.current_broker, self.command_router, self.intent_router, self.current_ai_context, self.config.allow_ai_actions, self)
        self.ai_dock.setObjectName("AsterAIDock")
        self.addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, self.ai_dock)

        api = AppAPI({"show_message": self.show_message, "open_url": lambda url, background=False: self.open_target(url, background=background), "park_background_tabs": self.park_background_tabs}, self.command_router)
        self.plugin_manager = PluginManager([bundled_plugins_dir(), user_plugins_dir()], api)
        loaded = self.plugin_manager.load_all()
        if loaded:
            self.show_message(f"Loaded {len(loaded)} plugin(s).")

    def current_broker(self) -> AIBroker:
        ai_cfg = self.config.ai
        return AIBroker(ai_cfg.provider, ai_cfg.model, ai_cfg.base_url, ai_cfg.api_key_env, ai_cfg.system_prompt)

    def current_ai_context(self) -> AIContext:
        state = self.current_state()
        if not state:
            return AIContext()
        selected_text = self._current_selected_text()
        previous_title = ''
        previous_url = ''
        previous_page_text = ''
        if self.previous_tab_id and self.previous_tab_id in self.tab_states:
            previous_state = self.tab_states[self.previous_tab_id]
            previous_title = previous_state.title
            previous_url = previous_state.url
            previous_page_text = previous_state.page_text
        return AIContext(
            url=state.url,
            title=state.title,
            container=state.container,
            page_text=state.page_text,
            page_links=state.page_links,
            selected_text=selected_text,
            previous_title=previous_title,
            previous_url=previous_url,
            previous_page_text=previous_page_text,
        )

    def _make_interceptor(self) -> AdblockInterceptor:
        return AdblockInterceptor(self.ruleset, enabled=self.config.adblock_enabled)

    def active_container(self) -> str:
        text = self.container_combo.currentText().strip()
        return text or self.config.default_container

    def set_active_container(self, name: str) -> None:
        name = self.containers.ensure(name)
        if self.container_combo.findText(name) < 0:
            self.container_combo.addItem(name)
        self.container_combo.setCurrentText(name)
        if name not in self.config.containers:
            self.config.containers.append(name)
            save_config(self.config)

    def _resolve_target(self, text: str) -> tuple[str, str]:
        text = text.strip()
        if not text:
            return ("internal", self.config.homepage)
        lowered = text.lower()
        if lowered in {"aster:newtab", "about:newtab", "newtab"}:
            return ("internal", "aster:newtab")
        if lowered in {"aster:settings", "settings"}:
            return ("internal", "aster:settings")
        if lowered == "about:blank":
            return ("web", text)
        if "://" in text or text.startswith("file://"):
            return ("web", text)
        if " " not in text and "." in text:
            return ("web", f"https://{text}")
        return ("search", text)

    def open_target(self, target: str, background: bool = False, replace_tab_id: str | None = None, container: str | None = None, bypass_drm_redirect: bool = False) -> TabState:
        kind, value = self._resolve_target(target)
        if kind == "internal":
            if value == "aster:settings":
                return self.open_settings_tab(background=background, replace_tab_id=replace_tab_id)
            return self.open_new_tab_page(background=background, replace_tab_id=replace_tab_id)
        url = build_search_url(self.config.search_engine, value) if kind == "search" else value
        selected_container = container or self.active_container()
        if not bypass_drm_redirect:
            redirected = self._maybe_redirect_streaming(url, selected_container, background, replace_tab_id)
            if redirected is not None:
                return redirected
        return self.open_web_tab(url, selected_container, background, replace_tab_id)

    def _maybe_redirect_streaming(self, url: str, container: str, background: bool, replace_tab_id: str | None) -> TabState | None:
        service = known_drm_service(url)
        if not service or not should_redirect_externally(url, self.streaming_support):
            return None
        launch = self.capsules.launch(url)
        if launch.ok:
            self.show_message(f"Opened {service} in a managed streaming capsule.")
        else:
            self.show_message(launch.note)
        reason = redirect_reason(url, self.streaming_support)
        return self.open_capsule_tab(url, container, reason, launch, background, replace_tab_id)

    def open_external_target(self, url: str | None = None) -> bool:
        target = (url or "").strip()
        if not target:
            state = self.current_state()
            target = state.url if state else ""
        if not target or target == "aster:newtab":
            return False
        opened = open_external(target, self.streaming_support.external_browser_command)
        if opened:
            self.show_message(f"Opened externally: {target}")
        else:
            self.show_message(f"Could not open externally: {target}")
        return opened

    def open_capsule_target(self, url: str | None = None) -> bool:
        target = (url or "").strip()
        state = self.current_state()
        if not target and state is not None:
            target = state.url
        if not target or target == "aster:newtab":
            return False
        container = state.container if state else self.active_container()
        launch = self.capsules.launch(target)
        self.show_message(launch.note)
        replace_tab_id = state.tab_id if state and state.url == target else None
        reason = "Opened manually in a managed streaming capsule."
        self.open_capsule_tab(target, container, reason, launch, background=False, replace_tab_id=replace_tab_id)
        return launch.ok

    def search(self, query: str) -> None:
        self.open_target(build_search_url(self.config.search_engine, query))

    def search_local_documents(self, query: str) -> str:
        assistant = LocalAssistant(self.local_index)
        return assistant.search_documents(query)

    def search_local_pdfs(self, query: str) -> str:
        assistant = LocalAssistant(self.local_index)
        return assistant.search_documents(query, pdf_only=True)

    def search_history(self, query: str) -> str:
        assistant = LocalAssistant(self.local_index)
        return assistant.search_history(query)

    def list_extensions_text(self) -> str:
        records = self.extensions.list_records()
        native_supported = False
        for profile in self.containers.profiles():
            if self.extensions.supports_native_loader(profile):
                native_supported = True
                break
        prefix = "Extension runtime: native MV3 loader available.\n" if native_supported else "Extension runtime: native MV3 loader not detected yet; Aster will still use fallback content scripts when possible.\n"
        return prefix + self.extensions.summary_text()

    def install_extension_path(self, source: str) -> str:
        source = (source or '').strip()
        if not source:
            return 'Usage: provide a path to an unpacked extension directory, .zip, .xpi, or .crx file.'
        try:
            record = self.extensions.install(source)
            self._sync_extensions_to_profiles()
            return f'Installed extension: {record.name} ({record.runtime_kind})'
        except Exception as exc:
            return f'Extension install failed: {exc}'

    def toggle_extension_query(self, query: str) -> str:
        query = (query or '').strip()
        if not query:
            return 'Usage: provide an extension name or ID.'
        record = self.extensions.find_record(query)
        if record is None:
            return f'No extension matched "{query}".'
        updated = self.extensions.set_enabled(record.ext_id, not record.enabled)
        if updated is None:
            return f'No extension matched "{query}".'
        self._sync_extensions_to_profiles()
        state = 'enabled' if updated.enabled else 'disabled'
        return f'{updated.name} is now {state}.'

    def remove_extension_query(self, query: str) -> str:
        query = (query or '').strip()
        if not query:
            return 'Usage: provide an extension name or ID.'
        removed = self.extensions.remove(query)
        if removed is None:
            return f'No extension matched "{query}".'
        self._sync_extensions_to_profiles()
        return f'Removed extension: {removed.name}'

    def reload_extensions_runtime(self) -> str:
        self._sync_extensions_to_profiles()
        return 'Reloaded extension runtime across open profiles.'

    def install_extension_from_web_url(self, source_url: str, label: str = "") -> str:
        target = (source_url or "").strip()
        if not target:
            message = "This page did not expose a direct extension package yet."
            self.show_message(message)
            return message
        reply = QMessageBox.question(
            self,
            "Install extension into Aster",
            f"Install this extension into Aster?\n\n{target}",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.Yes,
        )
        if reply != QMessageBox.StandardButton.Yes:
            message = "Extension install cancelled."
            self.show_message(message)
            return message
        try:
            record = self.extensions.install_from_url(target, suggested_name=label)
            self._sync_extensions_to_profiles()
            message = f"Installed extension from web: {record.short_label}"
            self.show_message(message)
            return message
        except Exception as exc:
            message = f"Extension web install failed: {exc}"
            self.show_message(message)
            return message

    def install_extension_from_current_page(self) -> bool:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if not state or view is None:
            self.show_message("Open a webpage first, then use Add from page.")
            return False
        view.page().runJavaScript(self._extension_candidate_script(), self._handle_extension_candidate_result)
        return True

    def install_extension_from_current_page_command(self) -> str:
        ok = self.install_extension_from_current_page()
        return "Checking this page for an installable extension package…" if ok else "Open a webpage first, then try Add from page."

    def _handle_extension_candidate_result(self, result: Any) -> None:
        if isinstance(result, dict) and result.get("unsupported") == "chrome_web_store":
            message = (
                "Chrome Web Store pages cannot be installed directly in Aster. Use a downloaded .crx/unpacked extension, "
                "or use a site that exposes a direct .zip/.xpi/.crx package."
            )
            QMessageBox.information(self, "Chrome Web Store", message)
            self.show_message(message)
            return
        if not isinstance(result, dict) or not str(result.get("href", "")).strip():
            message = "No direct .zip/.xpi/.crx extension package was found on this page."
            self.show_message(message)
            return
        self.install_extension_from_web_url(str(result.get("href", "")), str(result.get("label", "")))

    def _extension_candidate_script(self) -> str:
        return r"""
(() => {
  const trim = (value) => String(value || '').trim();
  const lower = (value) => trim(value).toLowerCase();
  const host = lower(location.hostname);
  if (host === 'chromewebstore.google.com' || host.endsWith('.chromewebstore.google.com')) {
    return { unsupported: 'chrome_web_store', host };
  }
  const anchors = Array.from(document.querySelectorAll('a[href]'));
  let best = null;
  let bestScore = -1;
  for (const anchor of anchors) {
    const href = trim(anchor.href);
    if (!href) continue;
    const hrefLower = lower(href);
    const text = lower(anchor.innerText || anchor.textContent || anchor.getAttribute('aria-label') || '');
    let score = 0;
    let kind = '';
    if (hrefLower.endsWith('.xpi') || hrefLower.includes('.xpi?')) { score += 120; kind = 'xpi'; }
    if (hrefLower.endsWith('.crx') || hrefLower.includes('.crx?')) { score += 110; kind = kind || 'crx'; }
    if (hrefLower.endsWith('.zip') || hrefLower.includes('.zip?')) { score += 90; kind = kind || 'zip'; }
    if (host.includes('addons.mozilla.org') && hrefLower.includes('/downloads/file/')) { score += 95; kind = kind || 'xpi'; }
    if (text.includes('download file')) score += 70;
    if (text.includes('install') || text.includes('download') || text.includes('add to firefox')) score += 25;
    if (anchor.hasAttribute('download')) score += 20;
    if (score > bestScore) {
      bestScore = score;
      best = { href, text, kind, score };
    }
  }
  if (best && best.score >= 90) {
    return { supported: true, href: best.href, kind: best.kind || 'archive', label: trim(document.title || '') };
  }
  return null;
})()
""".strip()

    def _extension_overlay_script(self) -> str:
        detector = self._extension_candidate_script()
        return rf"""
(() => {{
  const detector = () => ({detector});
  const rootId = '__aster-add-extension';
  const styleId = '__aster-add-extension-style';
  const removeExisting = () => {{
    const old = document.getElementById(rootId);
    if (old) old.remove();
  }};
  const ensureStyle = () => {{
    if (document.getElementById(styleId)) return;
    const style = document.createElement('style');
    style.id = styleId;
    style.textContent = `#${{rootId}}{{position:fixed;top:18px;right:18px;z-index:2147483647;display:flex;gap:8px;align-items:center;font-family:sans-serif;}}
#${{rootId}} .aster-add-btn{{border:0;border-radius:999px;padding:10px 14px;font-weight:700;cursor:pointer;box-shadow:0 4px 18px rgba(0,0,0,.25);background:#1f6feb;color:#fff;}}
#${{rootId}} .aster-add-note{{border-radius:999px;padding:8px 12px;background:rgba(17,24,39,.92);color:#fff;font-size:12px;box-shadow:0 4px 18px rgba(0,0,0,.25);max-width:420px;}}`;
    (document.head || document.documentElement).appendChild(style);
  }};
  const render = () => {{
    const result = detector();
    removeExisting();
    if (!result) return;
    ensureStyle();
    const root = document.createElement('div');
    root.id = rootId;
    if (result.unsupported === 'chrome_web_store') {{
      const note = document.createElement('div');
      note.className = 'aster-add-note';
      note.textContent = 'Chrome Web Store direct install is not available in Aster. Use a direct package download instead.';
      root.appendChild(note);
    }} else if (result.href) {{
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'aster-add-btn';
      button.textContent = 'Add to Aster';
      button.addEventListener('click', (event) => {{
        event.preventDefault();
        event.stopPropagation();
        location.href = 'aster://install-extension?source=' + encodeURIComponent(String(result.href || '')) + '&label=' + encodeURIComponent(String(document.title || ''));
      }});
      root.appendChild(button);
    }}
    (document.body || document.documentElement).appendChild(root);
  }};
  try {{
    if (window.__asterAddExtObserver) {{
      try {{ window.__asterAddExtObserver.disconnect(); }} catch (e) {{}}
    }}
    const observer = new MutationObserver(() => {{
      clearTimeout(window.__asterAddExtTimer);
      window.__asterAddExtTimer = setTimeout(render, 250);
    }});
    observer.observe(document.documentElement || document, {{ childList: true, subtree: true }});
    window.__asterAddExtObserver = observer;
  }} catch (e) {{}}
  render();
  return true;
}})()
""".strip()

    def install_extension_file_dialog(self) -> str:
        chosen, _ = QFileDialog.getOpenFileName(
            self,
            'Install browser extension',
            str(Path.home()),
            'Browser extensions (*.zip *.xpi *.crx);;All files (*)',
        )
        if not chosen:
            return 'Extension install cancelled.'
        return self.install_extension_path(chosen)

    def install_extension_folder_dialog(self) -> str:
        chosen = QFileDialog.getExistingDirectory(self, 'Install unpacked browser extension', str(Path.home()))
        if not chosen:
            return 'Extension install cancelled.'
        return self.install_extension_path(chosen)

    def open_extension_manager_dialog(self) -> None:
        dialog = ExtensionManagerDialog(
            self.extensions.list_records,
            self.install_extension_file_dialog,
            self.install_extension_folder_dialog,
            self.toggle_extension_query,
            self.remove_extension_query,
            self.reload_extensions_runtime,
            self,
        )
        dialog.exec()

    def _sync_extensions_to_profiles(self) -> None:
        self._profile_sync_generation.clear()
        for profile in self.containers.profiles():
            try:
                self._sync_profile_extensions(profile)
            except Exception as exc:
                self.show_message(f'Extension sync failed: {exc}')

    def list_tabs(self) -> str:
        if self.tabs.count() == 0:
            return "No tabs are open."
        current = self.current_state()
        lines = ["Open tabs:"]
        for index in range(self.tabs.count()):
            state = self._state_for_index(index)
            if not state:
                continue
            marker = "*" if current and state.tab_id == current.tab_id else " "
            label = (state.title or state.url or "Tab").strip()
            kind = state.kind
            lines.append(f"{index + 1}. {marker} {label} [{kind} | {state.container}]")
        return "\n".join(lines)

    def _ordered_states(self) -> list[TabState]:
        ordered: list[TabState] = []
        for index in range(self.tabs.count()):
            state = self._state_for_index(index)
            if state is not None:
                ordered.append(state)
        return ordered

    def switch_tab(self, target: str) -> str:
        target = (target or '').strip()
        ordered = self._ordered_states()
        if not ordered:
            return 'No tabs are open.'
        if target.isdigit():
            index = int(target) - 1
            if 0 <= index < len(ordered):
                self.tabs.setCurrentIndex(index)
                state = ordered[index]
                return f'Switched to tab {index + 1}: {state.title or state.url}'
            return f'No tab numbered {target}.'
        lowered = target.lower()
        best_index = -1
        best_score = -1
        best_state: TabState | None = None
        for index, state in enumerate(ordered):
            haystack = f"{state.title} {state.url}".lower()
            score = 0
            if lowered == (state.title or '').lower() or lowered == state.url.lower():
                score = 100
            elif lowered and lowered in (state.title or '').lower():
                score = 80
            elif lowered and lowered in state.url.lower():
                score = 60
            elif lowered:
                terms = [term for term in lowered.split() if term]
                score = sum(8 for term in terms if term in haystack)
            if score > best_score:
                best_score = score
                best_state = state
                best_index = index
        if best_state is None or best_score <= 0:
            return f'No open tab matched "{target}".'
        self.tabs.setCurrentIndex(best_index)
        return f'Switched to tab {best_index + 1}: {best_state.title or best_state.url}'

    def close_current_tab_action(self) -> bool:
        state = self.current_state()
        if state is None:
            return False
        self.close_tab_by_id(state.tab_id)
        return True

    def close_tabs_matching(self, query: str) -> int:
        query = (query or '').strip().lower()
        if not query:
            return 0
        current = self.current_state()
        to_close: list[str] = []
        for state in self._ordered_states():
            haystack = f"{state.title} {state.url}".lower()
            if query in haystack and (current is None or state.tab_id != current.tab_id):
                to_close.append(state.tab_id)
        for tab_id in to_close:
            self.close_tab_by_id(tab_id)
        return len(to_close)

    def close_other_tabs(self) -> int:
        current = self.current_state()
        if current is None:
            return 0
        to_close = [state.tab_id for state in self._ordered_states() if state.tab_id != current.tab_id]
        for tab_id in to_close:
            self.close_tab_by_id(tab_id)
        return len(to_close)

    def bookmark_current_page(self) -> str:
        state = self.current_state()
        if not state or not state.url or state.url == 'aster:newtab':
            return 'There is no normal page open to bookmark right now.'
        self.local_index.add_bookmark(state.url, state.title)
        return f'Saved bookmark: {state.title or state.url}'

    def search_bookmarks(self, query: str) -> str:
        assistant = LocalAssistant(self.local_index)
        return assistant.search_bookmarks(query)

    def open_first_bookmark(self, query: str) -> str:
        query = (query or '').strip()
        assistant = LocalAssistant(self.local_index)
        matches = assistant.bookmark_matches(query, limit=1)
        if not matches:
            return f'No saved bookmark matched "{query}".' if query else 'No saved bookmarks yet.'
        match = matches[0]
        self.local_index.touch_bookmark(match.url)
        self.open_target(match.url)
        return f'Opening bookmark: {match.title or match.url}'

    def open_first_document(self, query: str) -> str:
        query = (query or '').strip()
        assistant = LocalAssistant(self.local_index)
        matches = assistant.document_matches(query, limit=1, pdf_only=False)
        if not matches:
            return f'No local document matched "{query}".'
        match = matches[0]
        ok = bool(QDesktopServices.openUrl(QUrl.fromLocalFile(str(match.path.resolve()))))
        return f'Opened local document: {match.path}' if ok else f'Could not open local document: {match.path}'

    def open_first_pdf(self, query: str) -> str:
        query = (query or '').strip()
        assistant = LocalAssistant(self.local_index)
        matches = assistant.document_matches(query, limit=1, pdf_only=True)
        if not matches:
            return f'No local PDF matched "{query}".'
        match = matches[0]
        ok = bool(QDesktopServices.openUrl(QUrl.fromLocalFile(str(match.path.resolve()))))
        return f'Opened local PDF: {match.path}' if ok else f'Could not open local PDF: {match.path}'

    def list_page_links(self) -> str:
        assistant = LocalAssistant(self.local_index)
        return assistant.list_page_links(self.current_ai_context())

    def open_page_link(self, target: str) -> str:
        state = self.current_state()
        if not state or not state.page_links:
            return 'I could not find any readable links on the current page.'
        target = (target or '').strip()
        match_href = ''
        match_label = ''
        if target.isdigit():
            index = int(target) - 1
            if 0 <= index < len(state.page_links):
                match_label, match_href = state.page_links[index]
            else:
                return f'No page link numbered {target}.'
        else:
            lowered = target.lower()
            for label, href in state.page_links:
                label_text = (label or href).lower()
                href_text = href.lower()
                if lowered and (lowered in label_text or lowered in href_text):
                    match_label, match_href = label or href, href
                    break
        if not match_href:
            return f'No current-page link matched "{target}".'
        replace_tab_id = state.tab_id if state.kind == 'web' else None
        self.open_target(match_href, replace_tab_id=replace_tab_id, container=state.container)
        return f'Opening page link: {match_label or match_href}'

    def navigate_back(self) -> bool:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if view is not None:
            view.back()
            return True
        return False

    def navigate_forward(self) -> bool:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if view is not None:
            view.forward()
            return True
        return False

    def reload_current(self) -> bool:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if view is not None:
            view.reload()
            return True
        return False

    def duplicate_current_tab(self) -> bool:
        state = self.current_state()
        if not state or not state.url or state.url == 'aster:newtab':
            return False
        self.open_target(state.url, background=True, container=state.container)
        return True

    def open_in_current_tab(self, target: str) -> str:
        state = self.current_state()
        replace_tab_id = state.tab_id if state is not None else None
        container = state.container if state is not None else None
        opened = self.open_target(target, replace_tab_id=replace_tab_id, container=container)
        return f'Opening {opened.url}'

    def open_downloads_folder(self) -> bool:
        target = downloads_dir()
        target.mkdir(parents=True, exist_ok=True)
        return bool(QDesktopServices.openUrl(QUrl.fromLocalFile(str(target.resolve()))))

    def summarize_selection_or_page(self) -> str:
        assistant = LocalAssistant(self.local_index)
        context = self.current_ai_context()
        if context.selected_text.strip():
            return assistant.summarize_selection(context)
        return assistant.summarize_page(context)

    def compare_tabs(self) -> str:
        assistant = LocalAssistant(self.local_index)
        context = self.current_ai_context()
        return assistant.ask('compare these two tabs', context)

    def _current_selected_text(self) -> str:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if view is not None:
            try:
                return view.page().selectedText().strip()
            except Exception:
                return ''
        return ''

    def find_in_page(self, text: str) -> None:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if view is not None:
            view.findText(text)
            self.show_message(f"Searching this page for: {text}")
        else:
            self.show_message("Find-in-page only works on full web tabs.")

    def open_new_tab_page(self, background: bool = False, replace_tab_id: str | None = None) -> TabState:
        widget = NewTabWidget(self.stats_text)
        state = TabState(kind="internal", title="New Tab", url="aster:newtab", container=self.active_container(), widget=widget, live=False, estimated_cost_mb=8)
        widget.open_requested.connect(lambda text, state=state: self.open_target(text, replace_tab_id=state.tab_id, container=state.container))
        self._insert_state(state, background, replace_tab_id)
        return state

    def open_settings_tab(self, background: bool = False, replace_tab_id: str | None = None) -> TabState:
        widget = SettingsPageWidget(self.config)
        state = TabState(kind="internal", title="Settings", url="aster:settings", container=self.active_container(), widget=widget, live=False, estimated_cost_mb=10)
        widget.save_requested.connect(lambda cfg, rid=state.tab_id: self.apply_settings_from_page(cfg, rid))
        widget.cancel_requested.connect(lambda rid=state.tab_id: self.close_tab_by_id(rid))
        self._insert_state(state, background, replace_tab_id)
        return state

    def open_compat_redirect_tab(self, url: str, container: str, reason: str, background: bool = False, replace_tab_id: str | None = None) -> TabState:
        widget = CompatibilityRedirectWidget("Streaming compatibility mode", url, reason)
        state = TabState(kind="internal", title="Compatibility Mode", url=url, container=container, widget=widget, live=False, estimated_cost_mb=8)
        widget.try_inside_requested.connect(lambda state=state, container_name=container: self.open_target(state.url, container=container_name, replace_tab_id=state.tab_id, bypass_drm_redirect=True))
        widget.open_external_requested.connect(lambda target=url: open_external(target, self.streaming_support.external_browser_command))
        self._insert_state(state, background, replace_tab_id)
        return state

    def open_capsule_tab(self, url: str, container: str, reason: str, launch: CapsuleLaunchResult, background: bool = False, replace_tab_id: str | None = None) -> TabState:
        widget = StreamingCapsuleWidget(launch.service_label, url, reason, launch.runtime_label)
        self._apply_capsule_launch_result(widget, launch)
        state = TabState(kind="internal", title=f"{launch.service_label} Capsule", url=url, container=container, widget=widget, live=False, estimated_cost_mb=8)
        widget.launch_requested.connect(lambda rid=state.tab_id: self._relaunch_capsule_for_tab(rid))
        widget.try_inside_requested.connect(lambda state=state, container_name=container: self.open_target(state.url, container=container_name, replace_tab_id=state.tab_id, bypass_drm_redirect=True))
        widget.reset_profile_requested.connect(lambda label=launch.service_label, widget_ref=widget: self._reset_capsule_profile(label, widget_ref))
        widget.open_raw_requested.connect(lambda target=url: open_external(target, self.streaming_support.external_browser_command))
        self._insert_state(state, background, replace_tab_id)
        return state

    def open_error_tab(self, url: str, container: str, title: str, subtitle: str, details: list[str], background: bool = False, replace_tab_id: str | None = None) -> TabState:
        widget = ErrorPageWidget(title, subtitle, url, details)
        state = TabState(kind="internal", title=title, url=url, container=container, widget=widget, live=False, estimated_cost_mb=8)
        widget.retry_requested.connect(lambda rid=state.tab_id, target=url, cname=container: self.open_target(target, replace_tab_id=rid, container=cname))
        widget.home_requested.connect(lambda rid=state.tab_id: self.open_target(self.config.homepage, replace_tab_id=rid, container=container))
        widget.external_requested.connect(lambda target=url: self.open_external_target(target))
        self._insert_state(state, background, replace_tab_id)
        return state

    def _network_online(self) -> bool:
        now = time.monotonic()
        if self._connectivity_cache and now - self._connectivity_cache[0] < 6.0:
            return self._connectivity_cache[1]
        targets = [("1.1.1.1", 53), ("8.8.8.8", 53)]
        online = False
        for host, port in targets:
            try:
                with socket.create_connection((host, port), timeout=0.45):
                    online = True
                    break
            except OSError:
                continue
        self._connectivity_cache = (now, online)
        return online

    def _classify_error_page(self, url: str) -> tuple[str, str, list[str]]:
        lowered = (url or "").lower()
        online = self._network_online()
        if lowered.startswith("file://"):
            return (
                "Local file unavailable",
                "Aster could not open this local file.",
                ["Check that the file still exists.", "Confirm you still have permission to read it.", "Open the parent folder and try again."],
            )
        if not online:
            return (
                "No internet connection",
                "Aster could not reach the network right now.",
                ["Check Wi-Fi or Ethernet.", "Turn off a broken VPN or proxy.", "Retry after the connection comes back."],
            )
        if lowered.startswith("https://"):
            return (
                "Secure connection failed",
                "Aster could not establish a trusted secure connection to this page.",
                ["Check your system date and time.", "The site certificate may be invalid or incomplete.", "If this is a premium or protected site, try a supported runtime path."],
            )
        return (
            "Page unreachable",
            "Aster could not reach this address.",
            ["Check the URL for typing mistakes.", "The site may be down, moved, or blocked by network rules.", "Try again in a moment."],
        )


    def _apply_capsule_launch_result(self, widget: StreamingCapsuleWidget, launch: CapsuleLaunchResult) -> None:
        widget.set_runtime_hint(launch.runtime_label)
        widget.set_profile_hint(launch.profile_dir or "shared/default")
        widget.set_status_text(launch.note)

    def _relaunch_capsule_for_tab(self, tab_id: str) -> None:
        state = self.tab_states.get(tab_id)
        if not state:
            return
        launch = self.capsules.launch(state.url)
        if isinstance(state.widget, StreamingCapsuleWidget):
            self._apply_capsule_launch_result(state.widget, launch)
        self.show_message(launch.note)

    def _reset_capsule_profile(self, service_label: str, widget: StreamingCapsuleWidget) -> None:
        ok = self.capsules.reset_capsule_profile(service_label)
        if ok:
            widget.set_profile_hint("reset; relaunch to recreate")
            widget.set_status_text(f"Reset capsule profile for {service_label}.")
            self.show_message(f"Reset capsule profile for {service_label}.")
        else:
            widget.set_status_text(f"Could not reset capsule profile for {service_label}.")
            self.show_message(f"Could not reset capsule profile for {service_label}.")

    def open_web_tab(self, url: str, container: str, background: bool = False, replace_tab_id: str | None = None, mobile_mode: bool = False) -> TabState:
        view = QWebEngineView(self)
        profile = self.containers.profile(container, mobile=mobile_mode)
        try:
            self._sync_profile_extensions(profile)
        except Exception as exc:
            self.show_message(f"Extension sync failed: {exc}")
        page = BrowserPage(self, container, profile, view)
        view.setPage(page)
        apply_security_profile(view.settings(), self.config.security_mode)
        if mobile_mode:
            view.setZoomFactor(0.92)
            wrapper = MobileViewWidget(view)
        else:
            wrapper = None
        state_widget = wrapper if wrapper is not None else view
        state = TabState(kind="web", title=url, url=url, container=container, widget=state_widget, live=True, estimated_cost_mb=MemoryGovernor.estimate_cost(url, "web"), mobile_mode=mobile_mode)
        if wrapper is not None:
            wrapper.refresh_requested.connect(view.reload)
            wrapper.open_desktop_requested.connect(lambda state=state: self._open_desktop_for_tab(state.tab_id))
        view.urlChanged.connect(lambda qurl, state=state: self._on_url_changed(state.tab_id, qurl.toString()))
        view.titleChanged.connect(lambda title, state=state: self._on_title_changed(state.tab_id, title))
        page.recentlyAudibleChanged.connect(lambda audible, state=state: self._on_audible_changed(state.tab_id, audible))
        view.loadStarted.connect(lambda state=state: self._on_load_started(state.tab_id))
        view.loadProgress.connect(lambda progress, state=state: self._on_load_progress(state.tab_id, progress))
        view.loadFinished.connect(lambda ok, state=state: self._on_load_finished(state.tab_id, ok))
        self._insert_state(state, background, replace_tab_id)
        view.setUrl(QUrl(url))
        return state

    def open_lite_tab(self, url: str, container: str, background: bool = False, replace_tab_id: str | None = None) -> TabState:
        widget = LitePageWidget(url)
        state = TabState(kind="lite", title=f"Lite: {url}", url=url, container=container, widget=widget, live=False, estimated_cost_mb=MemoryGovernor.estimate_cost(url, "lite"))
        widget.open_full_requested.connect(lambda target, state=state, container_name=container: self.open_target(target, container=container_name, replace_tab_id=state.tab_id))
        widget.open_link_requested.connect(self.open_target)
        widget.title_changed.connect(lambda title, state=state: self._on_title_changed(state.tab_id, f"Lite: {title}"))
        widget.status_message.connect(self.show_message)
        self._insert_state(state, background, replace_tab_id)
        return state

    def open_lite_current(self) -> bool:
        now = time.monotonic()
        if now < self._lite_toggle_guard_until:
            return False
        self._lite_toggle_guard_until = now + 0.35
        state = self.current_state()
        if not state or state.url in {"aster:newtab", "aster:settings"}:
            return False
        if state.kind == "lite":
            self.open_target(state.url, replace_tab_id=state.tab_id, container=state.container)
            return True
        if state.kind not in {"web", "parked", "internal"}:
            return False
        self.open_lite_tab(state.url, state.container, replace_tab_id=state.tab_id)
        return True

    def go_from_address_bar(self) -> None:
        text = self.url_bar.text().strip()
        if not text:
            return
        state = self.current_state()
        current_view = self._extract_web_view(state.widget if state else None)
        if state and state.kind == "web" and current_view is not None:
            kind, value = self._resolve_target(text)
            if kind == "internal":
                if value == "aster:settings":
                    self.open_settings_tab(replace_tab_id=state.tab_id)
                else:
                    self.open_new_tab_page(replace_tab_id=state.tab_id)
            else:
                url = build_search_url(self.config.search_engine, value) if kind == "search" else value
                redirected = self._maybe_redirect_streaming(url, state.container, False, state.tab_id)
                if redirected is None:
                    state.url = url
                    state.estimated_cost_mb = MemoryGovernor.estimate_cost(url, "web", state.audible)
                    current_view.setUrl(QUrl(url))
            return
        replace = state.tab_id if state else None
        target_container = state.container if state else None
        self.open_target(text, replace_tab_id=replace, container=target_container)

    def _insert_state(self, state: TabState, background: bool = False, replace_tab_id: str | None = None) -> None:
        if replace_tab_id and replace_tab_id in self.tab_states:
            old_state = self.tab_states[replace_tab_id]
            index = self.tabs.indexOf(old_state.widget)
            state.tab_id = old_state.tab_id
            self.tabs.removeTab(index)
            self.tabs.insertTab(index, state.widget, self._tab_label(state))
            self.tabs.setCurrentIndex(index)
            try:
                old_state.widget.setParent(None); old_state.widget.deleteLater()
            except Exception:
                pass
            del self.tab_states[replace_tab_id]
            self.tab_states[state.tab_id] = state
        else:
            index = self.tabs.addTab(state.widget, self._tab_label(state))
            if not background:
                self.tabs.setCurrentIndex(index)
            self.tab_states[state.tab_id] = state
        self._refresh_visibility()
        if self.config.auto_park:
            QTimer.singleShot(200, self.park_background_tabs)

    def current_state(self) -> TabState | None:
        widget = self.tabs.currentWidget()
        for state in self.tab_states.values():
            if state.widget is widget:
                return state
        return None

    def _state_for_index(self, index: int) -> TabState | None:
        if index < 0:
            return None
        widget = self.tabs.widget(index)
        for state in self.tab_states.values():
            if state.widget is widget:
                return state
        return None

    def _tab_label(self, state: TabState) -> str:
        parts: list[str] = []
        if state.kind == "parked":
            parts.append("⏸")
        if state.mobile_mode:
            parts.append("📱")
        if state.audio_muted:
            parts.append("🔇")
        prefix = (" ".join(parts) + " ") if parts else ""
        title = state.title or state.url or "Tab"
        return prefix + (title[:28] + "…" if len(title) > 29 else title)

    def _refresh_visibility(self) -> None:
        current = self.current_state()
        for state in self.tab_states.values():
            state.visible = state is current
            if state.visible:
                state.last_active = time.time()
        if current:
            self.url_bar.setText(current.url)
        self.mode_badge.setText(self._mode_badge_text())
        self.tabs.setTabToolTip(self.tabs.currentIndex(), self.stats_text())

    def collect_snapshots(self):
        for state in self.tab_states.values():
            state.estimated_cost_mb = MemoryGovernor.estimate_cost(state.url, state.kind, state.audible)
        return [state.snapshot() for state in self.tab_states.values()]

    def stats_text(self) -> str:
        decision = self.governor.plan(self.collect_snapshots())
        rss = self.governor.process_rss_mb()
        rss_text = f" | process RSS: {rss} MB" if rss is not None else ""
        return f"Live tabs: {decision.live_tabs} | Parked tabs: {decision.parked_tabs} | Estimated live memory: {decision.estimated_live_mb} MB / {self.governor.soft_budget_mb} MB{rss_text}"

    def streaming_status_text(self) -> str:
        self.streaming_support = detect_streaming_support(self.config.drm_mode, self.config.external_browser_cmd)
        self.capsules = StreamingCapsuleManager(self.config.external_browser_cmd)
        return self.streaming_support.render() + "\n\n" + self.capsules.render()

    def capsule_status_text(self) -> str:
        self.capsules = StreamingCapsuleManager(self.config.external_browser_cmd)
        return self.capsules.render()

    def show_message(self, message: str, timeout_ms: int = 5000) -> None:
        self.status.showMessage(message, timeout_ms)

    def wireguard_status(self) -> str:
        return WireGuardHelper.status().render()

    def park_background_tabs(self) -> int:
        decision = self.governor.plan(self.collect_snapshots())
        count = 0
        for tab_id in decision.park_tab_ids:
            if self._park_tab(tab_id, decision.reason):
                count += 1
        if count:
            self.show_message(f"Parked {count} tab(s). {self.stats_text()}")
        return count

    def _park_tab(self, tab_id: str, reason: str) -> bool:
        state = self.tab_states.get(tab_id)
        if not state or state.kind != "web" or state.visible or state.pinned or state.audible or state.mobile_mode:
            return False
        index = self.tabs.indexOf(state.widget)
        if index < 0:
            return False
        parked_widget = ParkedTabWidget(state.title, state.url, state.container, reason)
        parked_widget.restore_requested.connect(lambda rid=tab_id: self.restore_tab(rid))
        parked_widget.lite_requested.connect(lambda rid=tab_id: self.open_lite_for_tab(rid))
        parked_widget.close_requested.connect(lambda rid=tab_id: self.close_tab_by_id(rid))
        old_widget = state.widget
        state.widget = parked_widget
        state.kind = "parked"; state.live = False; state.parked_reason = reason; state.estimated_cost_mb = MemoryGovernor.estimate_cost(state.url, "parked")
        self.tabs.removeTab(index)
        self.tabs.insertTab(index, parked_widget, self._tab_label(state))
        try:
            old_widget.setParent(None); old_widget.deleteLater()
        except Exception:
            pass
        return True

    def open_lite_for_tab(self, tab_id: str) -> None:
        state = self.tab_states.get(tab_id)
        if not state or state.url in {"aster:newtab", "aster:settings"}:
            return
        if state.kind == "lite":
            self.open_target(state.url, replace_tab_id=tab_id, container=state.container)
            return
        self.open_lite_tab(state.url, state.container, replace_tab_id=tab_id)

    def restore_tab(self, tab_id: str) -> None:
        state = self.tab_states.get(tab_id)
        if state:
            current = self.current_state(); background = current is not None and current.tab_id != tab_id
            self.open_web_tab(state.url, state.container, background=background, replace_tab_id=tab_id, mobile_mode=state.mobile_mode)

    def _on_current_tab_changed(self, index: int) -> None:
        state = self._state_for_index(index)
        if state and self.last_current_tab_id and self.last_current_tab_id != state.tab_id:
            self.previous_tab_id = self.last_current_tab_id
        self.last_current_tab_id = state.tab_id if state else None
        self._refresh_visibility()
        if not state:
            self.title_bar.set_context_text("Aster is ready")
            return
        self.url_bar.setText(state.url)
        self.title_bar.set_context_text(state.title or state.url)
        if state.kind == "parked":
            QTimer.singleShot(0, lambda rid=state.tab_id: self.restore_tab(rid))
        elif state.kind == "internal" and hasattr(state.widget, "refresh_stats"):
            state.widget.refresh_stats()

    def _on_url_changed(self, tab_id: str, url: str) -> None:
        state = self.tab_states.get(tab_id)
        if state:
            state.url = url; state.estimated_cost_mb = MemoryGovernor.estimate_cost(url, state.kind, state.audible)
            if state.visible:
                self.url_bar.setText(url)

    def _on_title_changed(self, tab_id: str, title: str) -> None:
        state = self.tab_states.get(tab_id)
        if state:
            state.title = title or state.url or "Tab"
            index = self.tabs.indexOf(state.widget)
            if index >= 0:
                self.tabs.setTabText(index, self._tab_label(state)); self.tabs.setTabToolTip(index, state.url)
            if state.visible:
                self.title_bar.set_context_text(state.title or state.url)

    def _on_audible_changed(self, tab_id: str, audible: bool) -> None:
        state = self.tab_states.get(tab_id)
        if state:
            state.audible = audible
            state.estimated_cost_mb = MemoryGovernor.estimate_cost(state.url, state.kind, state.audible)
            index = self.tabs.indexOf(state.widget)
            if index >= 0:
                self.tabs.setTabText(index, self._tab_label(state))

    def _on_load_started(self, tab_id: str) -> None:
        state = self.tab_states.get(tab_id)
        if state:
            state.title = "Loading…"; self._on_title_changed(tab_id, state.title)
        self.load_progress.setVisible(True)
        self.load_progress.setValue(0)

    def _on_load_progress(self, tab_id: str, progress: int) -> None:
        state = self.tab_states.get(tab_id)
        if state and state.visible:
            self.load_progress.setVisible(True)
            self.load_progress.setValue(max(0, min(100, int(progress))))

    def _on_load_finished(self, tab_id: str, ok: bool) -> None:
        state = self.tab_states.get(tab_id)
        if not state:
            return
        self.load_progress.setValue(100)
        QTimer.singleShot(500, lambda: self.load_progress.setVisible(False))
        if ok:
            self.plugin_manager.invoke("on_page_loaded", state.url, state.title)
            self.local_index.record_history(state.url, state.title)
            self._capture_page_context(tab_id)
            self._inject_extension_page_affordance(tab_id)
            if self.config.auto_park:
                QTimer.singleShot(500, self.park_background_tabs)
        else:
            service = known_drm_service(state.url)
            if service and self.config.drm_mode != "internal_only":
                title = f"{service} could not load inside Aster"
                subtitle = "Protected playback or site compatibility failed in the current runtime."
                details = [
                    "Aster can still keep you in the same workflow, but this page may need a supported DRM/runtime path.",
                    f"Current DRM mode: {self.config.drm_mode}.",
                    "Retry, adjust DRM mode, or use the compatibility path if needed.",
                ]
            else:
                title, subtitle, details = self._classify_error_page(state.url)
            self.open_error_tab(state.url, state.container, title, subtitle, details, replace_tab_id=tab_id)
            self.show_message(f"Could not load {state.url}")

    def _capture_page_context(self, tab_id: str) -> None:
        state = self.tab_states.get(tab_id)
        view = self._extract_web_view(state.widget if state else None)
        if not state or view is None:
            return
        chars = int(self.config.ai_page_context_chars)
        view.page().runJavaScript(f"(() => {{ try {{ return document.body ? document.body.innerText.slice(0, {chars}) : ''; }} catch (e) {{ return ''; }} }})()", lambda text, rid=tab_id: self._store_page_text(rid, text))
        view.page().runJavaScript("(() => { try { return Array.from(document.links).slice(0, 12).map(a => [String(a.innerText || a.textContent || '').trim().slice(0, 120), String(a.href || '')]); } catch (e) { return []; } })()", lambda links, rid=tab_id: self._store_page_links(rid, links))

    def _inject_extension_page_affordance(self, tab_id: str) -> None:
        state = self.tab_states.get(tab_id)
        view = self._extract_web_view(state.widget if state else None)
        if not state or view is None:
            return
        if not self._extension_overlay_allowed(state.url):
            return
        view.page().runJavaScript(self._extension_overlay_script())

    def _store_page_text(self, tab_id: str, text) -> None:
        state = self.tab_states.get(tab_id)
        if state and isinstance(text, str):
            state.page_text = text.strip()

    def _store_page_links(self, tab_id: str, links) -> None:
        state = self.tab_states.get(tab_id)
        if not state:
            return
        cleaned = []
        if isinstance(links, list):
            for item in links[:12]:
                if isinstance(item, (list, tuple)) and len(item) == 2:
                    label = str(item[0]).strip(); href = str(item[1]).strip()
                    if href:
                        cleaned.append((label or href, href))
        state.page_links = cleaned

    def _with_current_web_view(self, callback) -> None:
        state = self.current_state()
        view = self._extract_web_view(state.widget if state else None)
        if view is not None:
            callback(view)

    def _close_tab_index(self, index: int) -> None:
        state = self._state_for_index(index)
        if state:
            self.close_tab_by_id(state.tab_id)

    def close_current_tab(self) -> None:
        state = self.current_state()
        if state:
            self.close_tab_by_id(state.tab_id)

    def close_tab_by_id(self, tab_id: str) -> None:
        state = self.tab_states.pop(tab_id, None)
        if not state:
            return
        index = self.tabs.indexOf(state.widget)
        if index >= 0:
            self.tabs.removeTab(index)
        try:
            state.widget.setParent(None); state.widget.deleteLater()
        except Exception:
            pass
        if self.tabs.count() == 0:
            self.open_target(self.config.homepage)
        else:
            self._refresh_visibility()

    def apply_settings_from_page(self, config: BrowserConfig, tab_id: str | None = None) -> None:
        self.config = config
        save_config(self.config)
        self.ruleset.strict = self.config.strict_adblock
        self.governor = MemoryGovernor(self.config.soft_memory_budget_mb, self.config.max_live_tabs)
        self.streaming_support = detect_streaming_support(self.config.drm_mode, self.config.external_browser_cmd)
        self.capsules = StreamingCapsuleManager(self.config.external_browser_cmd)
        self.ai_dock.set_allow_actions_default(self.config.allow_ai_actions)
        self.tab_bar.set_active_tab_left_click_menu_enabled(getattr(self.config, "active_tab_left_click_menu", True))
        self._rebuild_container_selector()
        self._sync_extensions_to_profiles()
        self.mode_badge.setText(self._mode_badge_text())
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(stylesheet_for(self.config.theme))
        if tab_id and tab_id in self.tab_states:
            state = self.tab_states[tab_id]
            index = self.tabs.indexOf(state.widget)
            if index >= 0:
                self.tabs.setTabText(index, self._tab_label(state))
        self.show_message("Settings saved. Rendering changes apply immediately where possible.", 6000)

    def open_settings_dialog(self) -> None:
        state = self.current_state()
        replace_tab_id = state.tab_id if state and state.kind == "internal" and state.url in {"aster:newtab", "aster:settings"} else None
        self.open_settings_tab(replace_tab_id=replace_tab_id)

    def _rebuild_container_selector(self) -> None:
        current = self.config.default_container
        self.container_combo.clear()
        for name in self.config.containers:
            self.container_combo.addItem(name)
        self.set_active_container(current)

    def _handle_download(self, item) -> None:
        try:
            downloads_dir().mkdir(parents=True, exist_ok=True)
            filename = item.downloadFileName() or "download.bin"
            suggested_path = downloads_dir() / filename
            chosen, _ = QFileDialog.getSaveFileName(self, "Save download", str(suggested_path))
            if not chosen:
                return
            chosen_path = Path(chosen)
            item.setDownloadDirectory(str(chosen_path.parent)); item.setDownloadFileName(chosen_path.name); item.accept()
            self.show_message(f"Downloading to {chosen}")
        except Exception as exc:
            self.show_message(f"Download setup failed: {exc}")

    def changeEvent(self, event) -> None:  # type: ignore[override]
        super().changeEvent(event)
        if event.type() == QEvent.Type.WindowStateChange and hasattr(self, 'title_bar'):
            self.title_bar.sync_window_state()

    def closeEvent(self, event: QCloseEvent) -> None:  # type: ignore[override]
        save_config(self.config)
        super().closeEvent(event)

PYBROWSER

python -m py_compile "$APP_DIR/widgets.py" "$APP_DIR/browser.py"
msg "Applied responsive fullscreen UI fix."
msg "Backup saved to: $BACKUP_DIR"
msg "Restart Aster Browser to see the changes."
