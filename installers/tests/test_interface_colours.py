"""What the interface is painted in: the page colour, and the accent.

Every aster: page is painted in the colour of the tab it belongs to.

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

#: A colour is "blue" here when blue is its strongest channel by a clear
#: margin - green and teal have a lot of blue in them without reading as blue.
def is_blue(value: str) -> bool:
    red, green, blue = (int(value.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
    return blue >= max(red, green) and blue > green + 20 and blue > 90


def contrast(first: str, second: str) -> float:
    """WCAG contrast ratio, which is what decides whether text is readable."""
    def luminance(value: str) -> float:
        channels = []
        for raw in (int(value.lstrip("#")[i:i + 2], 16) / 255 for i in (0, 2, 4)):
            channels.append(raw / 12.92 if raw <= 0.03928 else ((raw + 0.055) / 1.055) ** 2.4)
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]

    lighter, darker = sorted((luminance(first), luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


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


class Accent(unittest.TestCase):
    """The interface accent is the white of Aster's mark, not the old blue."""

    def setUp(self):
        self.theme = load_theme()

    def test_the_default_accent_is_white(self):
        self.assertEqual(self.theme.ACCENT.upper(), "#FFFFFF")
        for name, tokens in self.theme._THEME_TOKENS.items():
            with self.subTest(theme=name):
                self.assertFalse(is_blue(tokens["accent"]),
                                 f"{name} still accents the interface in blue: {tokens['accent']}")

    def test_what_sits_on_the_accent_stays_readable(self):
        """A white button with white text is the whole risk of this change."""
        for name, tokens in self.theme._THEME_TOKENS.items():
            for state in ("accent", "accent_hover", "accent_pressed"):
                with self.subTest(theme=name, state=state):
                    ratio = contrast(tokens[state], tokens["accent_text"])
                    self.assertGreaterEqual(ratio, 4.5,
                                            f"{name}: text on {state} ({tokens[state]}) has {ratio:.1f}:1")

    def test_a_chosen_accent_brings_its_own_states(self):
        """Picking an accent used to leave the pressed state on the old colour."""
        for chosen, expect_dark_text in (("#ffffff", True), ("#f2c744", True), ("#7a1f66", False)):
            with self.subTest(accent=chosen):
                sheet = self.theme.stylesheet_for("black_arc", 0, chosen)
                self.assertNotIn("#50bfe9", sheet, "the theme's own accent survived the choice")
                pressed = background_of(sheet, "QPushButton:pressed")
                self.assertNotEqual(pressed.lower(), chosen.lower(), "pressed looks the same as resting")
                colour = re.search(r"QPushButton \{[^}]*?color:\s*([^;]+);", sheet).group(1).strip()
                self.assertEqual(contrast(chosen, colour) >= 4.5, True,
                                 f"text on {chosen} is {colour}, which is not readable")
                self.assertEqual(colour.lower() in ("#0c0e12",), expect_dark_text,
                                 f"{chosen} should carry {'dark' if expect_dark_text else 'light'} text")


class Blooms(unittest.TestCase):
    """The new tab page's blooms stay blue behind the white interface.

    widgets.py cannot be imported without PyQt6, so the backdrop is read rather
    than run: what matters is that it keeps a blue of its own and falls back to
    it whenever the accent has no hue, which white does not.
    """

    def setUp(self):
        self.source = kit.read_text("aster_browser/widgets.py")
        tree = ast.parse(self.source)
        self.backdrop = next(node for node in ast.walk(tree)
                             if isinstance(node, ast.ClassDef) and node.name == "AuroraBackdrop")

    def test_the_backdrop_keeps_a_blue_of_its_own(self):
        colours = [node.value.value for node in self.backdrop.body
                   if isinstance(node, ast.Assign) and isinstance(node.value, ast.Constant)
                   and getattr(node.targets[0], "id", "") == "BLOOM_BLUE"]
        self.assertEqual(len(colours), 1, "AuroraBackdrop no longer names its own colour")
        self.assertTrue(is_blue(colours[0]), f"the blooms are no longer blue: {colours[0]}")

    def test_a_white_accent_falls_back_to_that_blue(self):
        self.assertIn("hue = QColor(self.BLOOM_BLUE).hue()", self.source,
                      "a white accent would leave the blooms without a hue to use")


if __name__ == "__main__":
    unittest.main()
