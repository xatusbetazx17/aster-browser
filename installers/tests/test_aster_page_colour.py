"""Every aster: page is painted in the colour of the tab it belongs to.

The pages the browser draws itself - new tab, settings, history, bookmarks,
downloads, awareness, flags, the task manager, and the parked, lite and error
pages - used to sit on the window's black, which left the selected tab floating
above a darker well. They now share the selected tab's surface, and these checks
keep the two from drifting apart again: one reads the stylesheet the theme
actually generates, the other makes sure each page still carries the object name
that rule matches on.

The application lives inside the kit archive, so its modules are read from there.
theme.py imports nothing but the standard library, which is why it can be
imported here; the widget modules are only parsed.
"""
from __future__ import annotations

import ast
import importlib.util
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "installers"))

import kit  # noqa: E402 - resolved via the path above

PAGE_CLASSES = {
    "aster_browser/widgets.py": (
        "NewTabWidget", "ErrorPageWidget", "CompatibilityRedirectWidget",
        "StreamingCapsuleWidget", "FlagsPageWidget", "ParkedTabWidget",
        "LitePageWidget", "SettingsPageWidget", "TaskManagerWidget",
    ),
    "aster_browser/internal_pages.py": ("_PageShell", "AwarenessPageWidget"),
}
PAGE_OBJECT_NAME = "AsterInternalPage"


def load_theme():
    with tempfile.TemporaryDirectory() as directory:
        module_path = Path(directory) / "aster_theme.py"
        module_path.write_text(kit.read_text("aster_browser/theme.py"), encoding="utf-8")
        spec = importlib.util.spec_from_file_location("aster_theme", module_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module


def background_of(stylesheet: str, selector: str) -> str:
    match = re.search(re.escape(selector) + r"\s*\{[^}]*?background:\s*([^;]+);", stylesheet, re.S)
    assert match, f"{selector} has no background in the stylesheet"
    return match.group(1).strip()


class PageColour(unittest.TestCase):
    def setUp(self):
        self.theme = load_theme()

    def test_a_page_is_the_colour_of_its_tab(self):
        for name in self.theme._THEME_TOKENS:
            with self.subTest(theme=name):
                sheet = self.theme.stylesheet_for(name, background_transparency_percent=0)
                tab = background_of(sheet, "QTabBar::tab:selected")
                page = background_of(sheet, f"QWidget#{PAGE_OBJECT_NAME}")
                self.assertEqual(page, tab, f"{name}: pages and tabs no longer share a colour")
                self.assertEqual(page, self.theme.PAGE_BACKGROUND)

    def test_the_system_theme_follows_the_desktop_palette(self):
        sheet = self.theme.stylesheet_for("system")
        self.assertEqual(background_of(sheet, f"QWidget#{PAGE_OBJECT_NAME}"),
                         background_of(sheet, "QTabBar::tab:selected"))

    def test_a_translucent_window_keeps_them_matched(self):
        """With transparency on, both sides have to pick up the same alpha."""
        sheet = self.theme.stylesheet_for("aster_dark", background_transparency_percent=30)
        self.assertEqual(background_of(sheet, f"QWidget#{PAGE_OBJECT_NAME}"),
                         background_of(sheet, "QTabBar::tab:selected"))


class PagesCarryTheName(unittest.TestCase):
    """The rule above only reaches a page that is named for it."""

    def test_every_page_sets_the_object_name(self):
        for module, classes in PAGE_CLASSES.items():
            tree = ast.parse(kit.read_text(module))
            defined = {node.name: node for node in ast.walk(tree) if isinstance(node, ast.ClassDef)}
            for name in classes:
                with self.subTest(page=f"{module}:{name}"):
                    self.assertIn(name, defined, f"{name} is gone from {module}")
                    names = {
                        call.args[0].value
                        for call in ast.walk(defined[name])
                        if isinstance(call, ast.Call)
                        and isinstance(call.func, ast.Attribute)
                        and call.func.attr == "setObjectName"
                        and call.args and isinstance(call.args[0], ast.Constant)
                    }
                    self.assertIn(PAGE_OBJECT_NAME, names,
                                  f"{name} no longer paints itself as an aster: page")


if __name__ == "__main__":
    unittest.main()
