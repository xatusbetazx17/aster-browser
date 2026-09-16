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
import shutil
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


def load_package():
    """The kit's aster_browser package, importable without PyQt6.

    config.py and theme.py reach no further than the standard library and each
    other, which is what lets the colours be checked here rather than only in a
    running browser.
    """
    directory = tempfile.mkdtemp()
    for name in kit.names():
        if not name.startswith("aster_browser/") or not name.endswith(".py"):
            continue
        target = Path(directory) / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(kit.read(name))
    return directory


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


class RetiredAccents(unittest.TestCase):
    """An accent nobody chose does not outlive the default that handed it out."""

    def setUp(self):
        self.directory = load_package()
        sys.path.insert(0, self.directory)
        for name in [name for name in sys.modules if name.startswith("aster_browser")]:
            del sys.modules[name]
        import aster_browser.config as config  # noqa: PLC0415 - needs the path above

        self.config = config

    def tearDown(self):
        sys.path.remove(self.directory)
        for name in [name for name in sys.modules if name.startswith("aster_browser")]:
            del sys.modules[name]
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_a_configuration_with_no_accent_gets_the_white_one(self):
        self.assertEqual(self.config.coerce_browser_config({}).accent_color, self.config.DEFAULT_ACCENT)
        self.assertEqual(self.config.DEFAULT_ACCENT, "#ffffff")

    def test_every_accent_aster_used_to_ship_moves_on(self):
        for retired in self.config.RETIRED_ACCENTS:
            with self.subTest(accent=retired):
                self.assertFalse(is_blue(self.config.DEFAULT_ACCENT))
                moved = self.config.coerce_browser_config({"accent_color": retired}).accent_color
                self.assertEqual(moved, self.config.DEFAULT_ACCENT,
                                 f"a configuration still on {retired} keeps an accent nobody picked")

    def test_an_accent_somebody_picked_is_left_alone(self):
        for chosen in ("#f2c744", "#7a1f66", "#31c48d"):
            with self.subTest(accent=chosen):
                self.assertEqual(self.config.coerce_browser_config({"accent_color": chosen}).accent_color, chosen)


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

    def test_a_button_reads_against_its_own_label(self):
        """Buttons carry the field surface now, so the label has to clear it."""
        for name in self.theme._THEME_TOKENS:
            with self.subTest(theme=name):
                sheet = self.theme.stylesheet_for(name, background_transparency_percent=0)
                surface = background_of(sheet, "QPushButton")
                label = re.search(r"QPushButton \{[^}]*?color:\s*([^;]+);", sheet, re.S).group(1).strip()
                self.assertGreaterEqual(contrast(surface, label), 4.5,
                                        f"{name}: a button's label does not clear its own surface")
                self.assertNotEqual(surface.lower(), background_of(sheet, f"QWidget#{PAGE_OBJECT_NAME}").lower(),
                                    f"{name}: a button is the same colour as the page it sits on")

    def test_a_chosen_accent_replaces_the_theme_accent(self):
        """Picking an accent used to leave parts of the interface on the old one."""
        for chosen in ("#f2c744", "#7a1f66"):
            with self.subTest(accent=chosen):
                sheet = self.theme.stylesheet_for("black_arc", 0, chosen)
                self.assertNotIn("#50bfe9", sheet, "the theme's own accent survived the choice")
                self.assertNotIn(self.theme.ACCENT_PRESSED, sheet, "the default's pressed state survived")
                self.assertIn(chosen, sheet.lower(), "the chosen accent never reached the interface")


class Backdrop(unittest.TestCase):
    """The new tab page's light, and the ground the content sits on.

    widgets.py cannot be imported without PyQt6, so the backdrop is read rather
    than run. What matters here is that the page colour is what goes back over
    the middle: the logo, the search field and the tiles have to sit on the same
    surface as the rest of the browser, not on a black hole punched in the
    light, and not on the light itself.
    """

    def setUp(self):
        self.source = kit.read_text("aster_browser/widgets.py")
        tree = ast.parse(self.source)
        self.backdrop = next(node for node in ast.walk(tree)
                             if isinstance(node, ast.ClassDef) and node.name == "AuroraBackdrop")

    def constant(self, name: str):
        for node in self.backdrop.body:
            if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", "") == name:
                return ast.literal_eval(node.value)
        raise AssertionError(f"AuroraBackdrop no longer defines {name}")

    def test_the_content_keeps_the_page_colour_behind_it(self):
        centre = next(node for node in self.backdrop.body
                      if isinstance(node, ast.FunctionDef) and node.name == "_paint_centre")
        painted = ast.dump(centre)
        self.assertIn("PAGE_BACKGROUND", painted,
                      "the middle of the new tab page is no longer laid back in the page colour")
        self.assertNotIn("Constant(value=0), Constant(value=0), Constant(value=0)", painted,
                         "the middle is being painted black again")
        self.assertGreaterEqual(self.constant("CENTRE")[0][1], 0.9,
                                "the content no longer has solid ground under it")

    def test_a_fan_of_light_comes_in_from_every_corner(self):
        self.assertEqual(len(self.constant("CORNERS")), 4)
        self.assertEqual(self.constant("BEAMS_PER_CORNER"), 8)
        self.assertEqual(len(self.constant("PALETTE")), 8, "the shafts have lost their palette")

    def test_the_light_is_additive_over_the_page(self):
        render = next(node for node in self.backdrop.body
                      if isinstance(node, ast.FunctionDef) and node.name == "_render")
        painted = ast.dump(render)
        self.assertIn("CompositionMode_Plus", painted, "crossing shafts no longer brighten")
        self.assertIn("PAGE_BACKGROUND", painted, "the backdrop no longer starts from the page colour")


if __name__ == "__main__":
    unittest.main()
