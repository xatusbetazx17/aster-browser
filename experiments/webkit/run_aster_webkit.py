#!/usr/bin/env python3
"""Launch Aster WebKit using the distro's Python and native libraries."""

import argparse
import sys

from aster_webkit import __version__


def main():
    parser = argparse.ArgumentParser(description="Aster WebKit: a non-Chromium Linux browser prototype")
    parser.add_argument("addresses", nargs="*", help="web addresses or quoted search terms")
    parser.add_argument("--check-dependencies", action="store_true", help="check native libraries without opening a window")
    parser.add_argument("--version", action="version", version=f"Aster WebKit {__version__}")
    args = parser.parse_args()
    try:
        import gi

        gi.require_version("Gtk", "4.0")
        gi.require_version("Adw", "1")
        gi.require_version("WebKit", "6.0")
        from gi.repository import Adw, Gtk, WebKit
    except (ImportError, ValueError) as error:
        print(f"Aster WebKit needs Python GObject, GTK 4, libadwaita, and WebKitGTK 6.0.\n{error}\n"
              "See experiments/webkit/README.md for installation commands.\n"
              "Use your system Python (/usr/bin/python3), not Aster's Qt virtual environment.", file=sys.stderr)
        return 1
    if args.check_dependencies:
        print(f"GTK {Gtk.get_major_version()}.{Gtk.get_minor_version()} | "
              f"libadwaita {Adw.get_major_version()}.{Adw.get_minor_version()} | "
              f"WebKitGTK {WebKit.get_major_version()}.{WebKit.get_minor_version()}.{WebKit.get_micro_version()}")
        return 0
    from aster_webkit.app import BrowserApplication

    return BrowserApplication().run([sys.argv[0], *args.addresses])


if __name__ == "__main__":
    raise SystemExit(main())
