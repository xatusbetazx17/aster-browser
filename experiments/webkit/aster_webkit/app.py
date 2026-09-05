"""GTK 4 browser shell. Page rendering and JavaScript come only from WebKit."""

from __future__ import annotations

import hashlib
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("WebKit", "6.0")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango, WebKit

from . import __version__
from .core import BookmarkStore, is_web_uri, navigation_target, profile_paths

HOME_HTML = Path(__file__).with_name("home.html").read_text(encoding="utf-8")
CSS = b"""
.aster-tabstrip { padding: 4px 8px; background: @headerbar_bg_color; }
.aster-brand { font-weight: 700; padding: 0 12px; }
.aster-toolbar { padding: 8px; border-bottom: 1px solid alpha(@window_fg_color, 0.1); }
.aster-address { border-radius: 24px; padding: 5px 14px; min-height: 28px; }
.aster-find { padding: 6px 12px; }
.aster-toolbar button { border-radius: 50%; min-width: 28px; min-height: 28px; }
"""


def icon_button(icon: str, label: str, callback=None) -> Gtk.Button:
    button = Gtk.Button(icon_name=icon, tooltip_text=label)
    button.update_property([Gtk.AccessibleProperty.LABEL], [label])
    button.add_css_class("flat")
    if callback:
        button.connect("clicked", lambda *_: callback())
    return button


class BrowserTab(Gtk.Box):
    def __init__(self, session, related=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.view = (WebKit.WebView.new_with_related_view(related) if related else
                     WebKit.WebView(network_session=session))
        self.view.set_vexpand(True)
        settings = self.view.get_settings()
        settings.set_enable_developer_extras(True)
        settings.set_enable_hyperlink_auditing(False)
        settings.set_javascript_can_open_windows_automatically(False)
        self.append(self.view)
        for prop in ("title", "uri", "is-loading", "estimated-load-progress", "zoom-level"):
            self.view.connect(f"notify::{prop}", self._changed)
        self.view.get_back_forward_list().connect("changed", self._changed)
        self.view.connect("create", self._create)
        self.view.connect("decide-policy", self._policy)
        self.view.connect("permission-request", self._permission)
        self.view.connect("load-failed", self._load_failed)
        self.view.connect("web-process-terminated", self._terminated)

    @property
    def owner(self):
        root = self.get_root()
        return root if isinstance(root, BrowserWindow) else None

    @property
    def uri(self):
        return self.view.get_uri() or "about:blank"

    def navigate(self, uri):
        if uri == "about:blank":
            self.view.load_html(HOME_HTML, "about:blank")
        else:
            self.view.load_uri(uri)

    def _changed(self, *_):
        if self.owner:
            self.owner.sync()

    def _create(self, view, action):
        if self.owner and action.is_user_gesture():
            window = self.owner
            tab = BrowserTab(window.browser.session, related=view)
            # Keep ownership while WebKit prepares the new browsing context.
            window.pending_tabs.add(tab)

            def ready(_view):
                if tab not in window.pending_tabs:
                    return
                window.pending_tabs.remove(tab)
                page = window.tabs.append(tab)
                window.tabs.set_selected_page(page)

            tab.view.connect("ready-to-show", ready)
            return tab.view
        return None

    def _policy(self, view, decision, kind):
        if kind in (WebKit.PolicyDecisionType.NAVIGATION_ACTION,
                    WebKit.PolicyDecisionType.NEW_WINDOW_ACTION):
            uri = decision.get_navigation_action().get_request().get_uri()
            if uri != "about:blank" and not is_web_uri(uri):
                decision.ignore()
                if self.owner:
                    self.owner.notify("This version opens HTTP and HTTPS web links.")
                return True
        elif kind == WebKit.PolicyDecisionType.RESPONSE:
            if decision.is_main_frame_main_resource() and not decision.is_mime_type_supported():
                decision.download()
                return True
        return False

    def _permission(self, _view, request):
        # Site permission controls are not implemented in this first prototype.
        request.deny()
        if self.owner:
            self.owner.notify("This site requested a permission that this version does not support.")
        return True

    def _load_failed(self, _view, _event, _uri, error):
        if self.owner and not error.matches(WebKit.network_error_quark(), WebKit.NetworkError.CANCELLED):
            self.owner.notify("The page could not load. Check the address and connection.")
        return False  # Keep WebKit's error page and certificate validation.

    def _terminated(self, *_):
        if self.owner:
            self.owner.notify("The page process stopped. Reload to try again.")


class BrowserWindow(Adw.ApplicationWindow):
    def __init__(self, application):
        super().__init__(application=application, title="Aster WebKit", default_width=1180, default_height=780)
        self.browser = application
        self.downloads = {}
        self.pending_tabs = set()
        self.last_tab = None
        self.closed_tabs = []
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.overlay = Adw.ToastOverlay()
        self.overlay.set_child(root)
        self.set_content(self.overlay)

        self.tabs = Adw.TabView(vexpand=True)
        self.tabs.connect("notify::selected-page", self._selected)
        self.tabs.connect("close-page", self._close_page)
        self.tabs.connect("page-detached", self._detached)
        tabbar = Adw.TabBar(view=self.tabs, autohide=False, expand_tabs=False, hexpand=True)
        strip = Gtk.Box(spacing=4)
        strip.add_css_class("aster-tabstrip")
        brand = Gtk.Label(label="Aster")
        brand.add_css_class("aster-brand")
        strip.append(brand)
        strip.append(tabbar)
        strip.append(icon_button("list-add-symbolic", "New tab (Ctrl+T)", lambda: self.new_tab()))
        strip.append(Gtk.WindowControls(side=Gtk.PackType.END))
        handle = Gtk.WindowHandle(child=strip)
        root.append(handle)

        toolbar = Gtk.Box(spacing=4)
        toolbar.add_css_class("aster-toolbar")
        self.back = icon_button("go-previous-symbolic", "Back (Alt+Left)", lambda: self.current.view.go_back())
        self.forward = icon_button("go-next-symbolic", "Forward (Alt+Right)", lambda: self.current.view.go_forward())
        self.reload = icon_button("view-refresh-symbolic", "Reload or stop", self.reload_or_stop)
        for button in (self.back, self.forward, self.reload):
            toolbar.append(button)
        self.address = Gtk.Entry(placeholder_text="Search or enter a web address", hexpand=True)
        self.address.add_css_class("aster-address")
        self.address.set_icon_from_icon_name(Gtk.EntryIconPosition.PRIMARY, "web-browser-symbolic")
        self.address.update_property([Gtk.AccessibleProperty.LABEL], ["Address and search bar"])
        self.address.connect("activate", self._navigate)
        self.address_focus = Gtk.EventControllerFocus()
        self.address.add_controller(self.address_focus)
        toolbar.append(self.address)
        self.star = icon_button("non-starred-symbolic", "Bookmark this page (Ctrl+D)", self.toggle_bookmark)
        toolbar.append(self.star)
        self.bookmark_menu = Gtk.MenuButton(icon_name="user-bookmarks-symbolic", tooltip_text="Bookmarks")
        self.bookmark_popover = Gtk.Popover()
        self.bookmark_menu.set_popover(self.bookmark_popover)
        toolbar.append(self.bookmark_menu)
        self.zoom_label = Gtk.Label(label="100%")
        toolbar.append(self.zoom_label)
        menu = Gio.Menu()
        for label, action in (
            ("New tab", "new-tab"), ("Reopen closed tab", "reopen-tab"),
            ("Find in page", "find"), ("Zoom in", "zoom-in"),
            ("Zoom out", "zoom-out"), ("Reset zoom", "zoom-reset"),
            ("Developer tools", "inspect"), ("About Aster", "about"), ("Close window", "close-window"),
        ):
            menu.append(label, f"win.{action}")
        toolbar.append(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, tooltip_text="Aster menu"))
        root.append(toolbar)
        self.progress = Gtk.ProgressBar()
        self.progress.set_visible(False)
        root.append(self.progress)

        self.find_revealer = Gtk.Revealer()
        findbox = Gtk.Box(spacing=6)
        findbox.add_css_class("aster-find")
        self.find_entry = Gtk.SearchEntry(placeholder_text="Find in page", hexpand=True)
        self.find_entry.connect("search-changed", self._find)
        self.find_entry.connect("activate", lambda *_: self.current.view.get_find_controller().search_next())
        self.find_entry.connect("stop-search", lambda *_: self.close_find())
        findbox.append(self.find_entry)
        findbox.append(icon_button("go-up-symbolic", "Previous match", lambda: self.current.view.get_find_controller().search_previous()))
        findbox.append(icon_button("go-down-symbolic", "Next match", lambda: self.current.view.get_find_controller().search_next()))
        findbox.append(icon_button("window-close-symbolic", "Close find", self.close_find))
        self.find_revealer.set_child(findbox)
        root.append(self.find_revealer)
        root.append(self.tabs)
        self._actions()
        self.refresh_bookmarks()
        self.connect("close-request", self._closing)

    @property
    def current(self):
        page = self.tabs.get_selected_page()
        return page.get_child() if page else None

    def notify(self, message):
        self.overlay.add_toast(Adw.Toast(title=GLib.markup_escape_text(message), timeout=5))

    def _actions(self):
        actions = {
            "new-tab": (lambda: self.new_tab(), ["<Control>t"]),
            "close-tab": (self.close_tab, ["<Control>w"]),
            "reopen-tab": (self.reopen_tab, ["<Control><Shift>t"]),
            "location": (self.focus_address, ["<Control>l", "<Alt>d"]),
            "reload": (lambda: self.current.view.reload(), ["<Control>r", "F5"]),
            "reload-fresh": (lambda: self.current.view.reload_bypass_cache(), ["<Control><Shift>r"]),
            "stop": (self.escape, ["Escape"]),
            "back": (lambda: self.current.view.go_back(), ["<Alt>Left"]),
            "forward": (lambda: self.current.view.go_forward(), ["<Alt>Right"]),
            "bookmark": (self.toggle_bookmark, ["<Control>d"]),
            "find": (self.show_find, ["<Control>f"]),
            "zoom-in": (lambda: self.zoom(0.1), ["<Control>plus", "<Control>equal"]),
            "zoom-out": (lambda: self.zoom(-0.1), ["<Control>minus"]),
            "zoom-reset": (lambda: self.current.view.set_zoom_level(1.0), ["<Control>0"]),
            "inspect": (lambda: self.current.view.get_inspector().show(), ["<Control><Shift>i", "F12"]),
            "about": (self.about, []),
            "close-window": (self.close, ["<Control><Shift>w"]),
        }
        for name, (callback, keys) in actions.items():
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", lambda _action, _param, fn=callback: fn())
            self.add_action(action)
            if keys:
                self.browser.set_accels_for_action(f"win.{name}", keys)
        for number in range(1, 10):
            action = Gio.SimpleAction.new(f"tab-{number}", None)
            action.connect("activate", lambda _action, _param, n=number: self.select_tab(n))
            self.add_action(action)
            self.browser.set_accels_for_action(f"win.tab-{number}", [f"<Control>{number}"])
        # Adw.TabView also supplies Ctrl+Tab and Ctrl+Shift+Tab.

    def select_tab(self, number):
        count = self.tabs.get_n_pages()
        index = count - 1 if number == 9 else number - 1
        if 0 <= index < count:
            self.tabs.set_selected_page(self.tabs.get_nth_page(index))

    def new_tab(self, uri="about:blank", related=None):
        tab = BrowserTab(self.browser.session, related)
        page = self.tabs.append(tab)
        page.set_title("New tab")
        self.tabs.set_selected_page(page)
        if uri is not None:
            tab.navigate(uri)
        if uri == "about:blank":
            self.focus_address()
        return tab

    def close_tab(self):
        page = self.tabs.get_selected_page()
        if page:
            self.tabs.close_page(page)

    def _close_page(self, _tabs, page):
        uri = page.get_child().uri
        if is_web_uri(uri):
            self.closed_tabs = (self.closed_tabs + [uri])[-20:]
        page.get_child().view.stop_loading()
        # Default handler removes an unpinned page.
        return False

    def _detached(self, *_):
        if self.tabs.get_n_pages() == 0:
            GLib.idle_add(self._ensure_tab)

    def _ensure_tab(self):
        if self.get_visible() and self.tabs.get_n_pages() == 0:
            self.new_tab()
        return False

    def reopen_tab(self):
        if self.closed_tabs:
            self.new_tab(self.closed_tabs.pop())

    def _selected(self, *_):
        if self.last_tab:
            self.last_tab.view.get_find_controller().search_finish()
        self.last_tab = self.current
        self.find_revealer.set_reveal_child(False)
        self.sync(force_address=True)

    def sync(self, force_address=False):
        for index in range(self.tabs.get_n_pages()):
            page = self.tabs.get_nth_page(index)
            view = page.get_child().view
            page.set_title(view.get_title() or "New tab")
            page.set_loading(view.is_loading())
            page.set_tooltip(GLib.markup_escape_text(view.get_uri() or "New tab"))
        tab = self.current
        if not tab:
            return
        self.set_title(f"{tab.view.get_title() or 'New tab'} — Aster WebKit")
        if force_address or not self.address_focus.contains_focus():
            self.address.set_text("" if tab.uri == "about:blank" else tab.uri)
        self.back.set_sensitive(tab.view.can_go_back())
        self.forward.set_sensitive(tab.view.can_go_forward())
        loading = tab.view.is_loading()
        self.reload.set_icon_name("process-stop-symbolic" if loading else "view-refresh-symbolic")
        self.progress.set_visible(loading)
        self.progress.set_fraction(tab.view.get_estimated_load_progress())
        self.zoom_label.set_text(f"{tab.view.get_zoom_level():.0%}")
        store = self.browser.bookmarks
        self.star.set_sensitive(store is not None and is_web_uri(tab.uri))
        saved = store is not None and store.contains(tab.uri)
        self.star.set_icon_name("starred-symbolic" if saved else "non-starred-symbolic")

    def focus_address(self):
        self.address.grab_focus()
        self.address.select_region(0, -1)

    def _navigate(self, entry):
        try:
            target = navigation_target(entry.get_text())
        except ValueError as error:
            self.notify(str(error))
            return
        if not self.current:
            self.new_tab(target)
        else:
            self.current.navigate(target)
        self.current.view.grab_focus()

    def reload_or_stop(self):
        if self.current:
            view = self.current.view
            view.stop_loading() if view.is_loading() else view.reload()

    def escape(self):
        if self.find_revealer.get_reveal_child():
            self.close_find()
        elif self.current:
            self.current.view.stop_loading()
            self.current.view.grab_focus()
            self.sync(force_address=True)

    def zoom(self, delta):
        if self.current:
            view = self.current.view
            view.set_zoom_level(round(max(0.5, min(3.0, view.get_zoom_level() + delta)), 2))

    def show_find(self):
        self.find_revealer.set_reveal_child(True)
        self.find_entry.grab_focus()
        self._find()

    def _find(self, *_):
        if self.current:
            controller = self.current.view.get_find_controller()
            text = self.find_entry.get_text()
            if text and self.find_revealer.get_reveal_child():
                controller.search(text, WebKit.FindOptions.CASE_INSENSITIVE | WebKit.FindOptions.WRAP_AROUND, 10000)
            else:
                controller.search_finish()

    def close_find(self):
        self.find_revealer.set_reveal_child(False)
        if self.current:
            self.current.view.get_find_controller().search_finish()
            self.current.view.grab_focus()

    def toggle_bookmark(self):
        if self.current and self.browser.bookmarks:
            try:
                added = self.browser.bookmarks.toggle(self.current.uri, self.current.view.get_title())
            except (OSError, ValueError) as error:
                self.notify(str(error))
                return
            self.refresh_bookmarks()
            self.sync()
            self.notify("Bookmark saved" if added else "Bookmark removed")

    def refresh_bookmarks(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4,
                      margin_top=8, margin_bottom=8, margin_start=8, margin_end=8)
        items = self.browser.bookmarks.items if self.browser.bookmarks else []
        if not items:
            box.append(Gtk.Label(label="Use the star to save a page.", margin_top=12, margin_bottom=12))
        for item in items:
            button = Gtk.Button(tooltip_text=item["uri"])
            label = Gtk.Label(label=item["title"], xalign=0, ellipsize=Pango.EllipsizeMode.END, max_width_chars=36)
            button.set_child(label)
            button.add_css_class("flat")
            button.connect("clicked", lambda _button, uri=item["uri"]: self._open_bookmark(uri))
            box.append(button)
        scroll = Gtk.ScrolledWindow(max_content_height=400, propagate_natural_height=True, min_content_width=280)
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroll.set_child(box)
        self.bookmark_popover.set_child(scroll)

    def _open_bookmark(self, uri):
        self.bookmark_popover.popdown()
        self.new_tab(uri)

    def download_started(self, _session, download):
        self.downloads[download] = {"failed": False, "chooser": None}
        download.set_allow_overwrite(False)
        download.connect("decide-destination", self._download_destination)
        download.connect("failed", self._download_failed)
        download.connect("finished", self._download_finished)

    def _download_destination(self, download, suggested):
        chooser = Gtk.FileChooserNative.new("Save download", self, Gtk.FileChooserAction.SAVE, "Save", "Cancel")
        chooser.set_current_name((suggested or "download").replace("\\", "/").rsplit("/", 1)[-1] or "download")
        self.downloads[download]["chooser"] = chooser

        def response(dialog, result):
            state = self.downloads.get(download)
            chosen = dialog.get_file() if result == Gtk.ResponseType.ACCEPT else None
            if state is not None:
                state["chooser"] = None
                if chosen and chosen.get_path():
                    download.set_destination(chosen.get_path())
                    self.notify("Download started")
                else:
                    state["failed"] = True
                    download.cancel()
            dialog.destroy()

        chooser.connect("response", response)
        chooser.show()
        return True  # Set the destination asynchronously after the user's choice.

    def _download_failed(self, download, _error):
        state = self.downloads.get(download)
        if state is not None:
            if not state["failed"]:
                self.notify("Download failed. Choose a new filename and check the connection.")
            state["failed"] = True

    def _download_finished(self, download):
        state = self.downloads.pop(download, None)
        if state:
            if state["chooser"]:
                state["chooser"].destroy()
            if not state["failed"]:
                self.notify("Download saved")

    def _closing(self, *_):
        for tab in self.pending_tabs:
            tab.view.stop_loading()
        self.pending_tabs.clear()
        for download in list(self.downloads):
            self.downloads[download]["failed"] = True
            download.cancel()
        return False

    def about(self):
        dialog = Gtk.AboutDialog(transient_for=self, modal=True, program_name="Aster WebKit",
                                 version=__version__, website="https://github.com/xatusbetazx17/aster-browser",
                                 comments="An experimental Linux browser powered by WebKitGTK and JavaScriptCore.",
                                 license_type=Gtk.License.MIT_X11)
        dialog.present()


class BrowserApplication(Adw.Application):
    def __init__(self):
        data, cache = profile_paths()
        # One application instance per profile avoids competing bookmark writers.
        profile_id = hashlib.sha256(str(data).encode()).hexdigest()[:16]
        super().__init__(application_id=f"org.aster.WebKit.p{profile_id}",
                         flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self.data = data
        self.cache = cache
        self.session = None
        self.bookmarks = None
        self.bookmark_error = None

    def do_startup(self):
        Adw.Application.do_startup(self)
        self.data.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.cache.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            self.bookmarks = BookmarkStore(self.data / "bookmarks.json")
        except ValueError as error:
            self.bookmark_error = str(error)
        self.session = WebKit.NetworkSession.new(str(self.data / "website-data"), str(self.cache))
        self.session.set_tls_errors_policy(WebKit.TLSErrorsPolicy.FAIL)
        self.session.set_itp_enabled(True)
        self.session.set_persistent_credential_storage_enabled(False)
        self.session.get_cookie_manager().set_persistent_storage(str(self.data / "cookies.sqlite"), WebKit.CookiePersistentStorage.SQLITE)
        self.session.connect("download-started", self._download)
        provider = Gtk.CssProvider()
        provider.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    def do_activate(self):
        window = self.get_active_window()
        if not window:
            window = BrowserWindow(self)
            window.new_tab()
        window.present()
        if self.bookmark_error:
            window.notify(self.bookmark_error)
            self.bookmark_error = None

    def do_command_line(self, command_line):
        self.activate()
        window = self.get_active_window()
        for address in command_line.get_arguments()[1:]:
            try:
                window.new_tab(navigation_target(address))
            except ValueError as error:
                window.notify(str(error))
        return 0

    def _download(self, session, download):
        window = self.get_active_window()
        if window:
            window.download_started(session, download)
        else:
            download.cancel()
