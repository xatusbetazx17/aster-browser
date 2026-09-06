"""Native Aster reader, assistant and media tools. Webpages have no command bridge."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import threading

from gi.repository import GLib, Gtk

from .assistant import generate_local, local_answer, parse_command
from .documents import read_document
from .media import MEDIA_PROBE, report_text
from .speech import Speech


def button(label, callback):
    widget = Gtk.Button(label=label)
    widget.connect("clicked", lambda *_: callback())
    return widget


def text_area():
    view = Gtk.TextView(editable=False, cursor_visible=True, wrap_mode=Gtk.WrapMode.WORD_CHAR,
                        left_margin=14, right_margin=14, top_margin=14, bottom_margin=14)
    view.add_css_class("aster-reading")
    scroll = Gtk.ScrolledWindow(vexpand=True, hexpand=True)
    scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
    scroll.set_child(view)
    return view, scroll


class ToolsPanel(Gtk.Box):
    def __init__(self, window):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=10, width_request=360,
                         margin_top=12, margin_bottom=12, margin_start=12, margin_end=12)
        self.window = window
        self.document = None
        self.speech = Speech()
        self.model = None
        self.voice_model = None
        self.closed = False
        self.busy = False
        self.choosers = set()
        self.voice_process = None
        self.model_cancel = threading.Event()
        title = Gtk.Box(spacing=8)
        heading = Gtk.Label(label="Aster companion", xalign=0, hexpand=True)
        heading.add_css_class("title-3")
        title.append(heading)
        title.append(button("Close", lambda: window.tools_revealer.set_reveal_child(False)))
        self.append(title)
        self.stack = Gtk.Stack(vexpand=True, transition_type=Gtk.StackTransitionType.CROSSFADE)
        self.append(Gtk.StackSwitcher(stack=self.stack, halign=Gtk.Align.CENTER))
        self.append(self.stack)
        self._reader()
        self._assistant()
        self._media()

    def show(self, page="assistant"):
        self.window.tools_revealer.set_reveal_child(True)
        self.stack.set_visible_child_name(page)

    def _reader(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.reader_title = Gtk.Label(label="Your reading space", xalign=0, wrap=True)
        self.reader_title.add_css_class("title-3")
        box.append(self.reader_title)
        box.append(button("Clear reader and use current page", self.clear_document))
        row = Gtk.Box(spacing=6, homogeneous=True)
        row.append(button("Open document", self.choose_document))
        row.append(button("Read this page", self.read_page))
        box.append(row)
        self.reader_note = Gtk.Label(label="Open Word, PDF or text files. Documents stay on your computer.", xalign=0, wrap=True)
        self.reader_note.add_css_class("dim-label")
        box.append(self.reader_note)
        self.reader, scroll = text_area()
        box.append(scroll)
        self.reader_search = Gtk.SearchEntry(placeholder_text="Find in document")
        self.reader_search.connect("activate", self.find_document)
        box.append(self.reader_search)
        row = Gtk.Box(spacing=6)
        row.append(button("Read aloud", self.read_aloud))
        row.append(button("Stop", self.speech.stop))
        self.voice = Gtk.DropDown.new_from_strings(["English", "Español"])
        self.voice.set_tooltip_text("Read-aloud voice")
        row.append(self.voice)
        box.append(row)
        row = Gtk.Box(spacing=8)
        row.append(Gtk.Label(label="Speech speed"))
        self.rate = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 100, 300, 10)
        self.rate.set_value(175)
        self.rate.set_hexpand(True)
        row.append(self.rate)
        box.append(row)
        self.stack.add_titled(box, "reader", "Read")

    def _assistant(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.model_label = Gtk.Label(label="Offline commands and text excerpts", xalign=0, wrap=True)
        self.model_label.add_css_class("dim-label")
        box.append(self.model_label)
        row = Gtk.Box(spacing=6, homogeneous=True)
        row.append(button("Choose AI model", self.choose_model))
        row.append(button("Use basic mode", self.basic_mode))
        box.append(row)
        self.answer, scroll = text_area()
        self.answer.get_buffer().set_text(local_answer("help", ""))
        box.append(scroll)
        self.include_context = Gtk.CheckButton(label="Use current page text")
        box.append(self.include_context)
        self.prompt = Gtk.Entry(placeholder_text="Ask Aster or type a browser command")
        self.prompt.connect("activate", lambda *_: self.ask())
        box.append(self.prompt)
        row = Gtk.Box(spacing=6, homogeneous=True)
        self.ask_button = button("Ask Aster", self.ask)
        self.ask_button.add_css_class("suggested-action")
        row.append(self.ask_button)
        self.listen_button = button("Listen (8 s)", self.listen)
        row.append(self.listen_button)
        box.append(row)
        row = Gtk.Box(spacing=6, homogeneous=True)
        row.append(button("Choose voice model", self.choose_voice))
        row.append(button("Read answer aloud", lambda: self.speak_text(self.buffer_text(self.answer))))
        box.append(row)
        self.status = Gtk.Label(label="Voice input fills the box; press Ask to use it.", xalign=0, wrap=True)
        self.status.add_css_class("dim-label")
        box.append(self.status)
        self.stack.add_titled(box, "assistant", "Ask")

    def _media(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        label = Gtk.Label(label="Watch and play", xalign=0)
        label.add_css_class("title-3")
        box.append(label)
        label = Gtk.Label(label="Open a service in an Aster tab. Playback depends on your device and the service's browser support.", wrap=True, xalign=0)
        box.append(label)
        for name, uri in (("Open Boosteroid", "https://cloud.boosteroid.com/"),
                          ("Open Prime Video", "https://www.primevideo.com/"),
                          ("Open Xbox Cloud Gaming", "https://www.xbox.com/play"),
                          ("Open GeForce NOW", "https://play.geforcenow.com/")):
            box.append(button(name, lambda address=uri: self.window.new_tab(address)))
        box.append(button("Check this page's streaming support", self.check_media))
        box.append(button("Fullscreen (F11)", self.window.toggle_fullscreen))
        self.media_result, scroll = text_area()
        self.media_result.get_buffer().set_text("Open an HTTPS page, then run the check to see codec, controller, cloud connection and protected-media support on this device.")
        box.append(scroll)
        self.stack.add_titled(box, "media", "Play")

    @staticmethod
    def buffer_text(view, selection=False):
        buffer = view.get_buffer()
        bounds = buffer.get_selection_bounds() if selection else ()
        if not bounds:
            bounds = buffer.get_bounds()
        return buffer.get_text(*bounds, True)

    def choose_file(self, title, callback, patterns=None, folder=False):
        chooser = Gtk.FileChooserNative.new(title, self.window,
                    Gtk.FileChooserAction.SELECT_FOLDER if folder else Gtk.FileChooserAction.OPEN, "Open", "Cancel")
        self.choosers.add(chooser)
        if patterns:
            file_filter = Gtk.FileFilter()
            file_filter.set_name(title)
            for pattern in patterns:
                file_filter.add_pattern(pattern)
            chooser.add_filter(file_filter)
        def respond(dialog, result):
            selected = dialog.get_file() if result == Gtk.ResponseType.ACCEPT else None
            self.choosers.discard(dialog)
            dialog.destroy()
            if not self.closed and selected and selected.get_path():
                callback(Path(selected.get_path()))
        chooser.connect("response", respond)
        chooser.show()

    def choose_document(self):
        self.show("reader")
        self.choose_file("Open document", self.open_document, ["*.docx", "*.DOCX", "*.doc", "*.pdf", "*.txt", "*.md"])

    def run_job(self, job, done, failed=None):
        def deliver(value, error):
            if not self.closed:
                if error:
                    (failed or self.window.notify)(str(error))
                else:
                    done(value)
            return False
        def work():
            try:
                value, error = job(), None
            except Exception as problem:
                value, error = None, problem
            GLib.idle_add(deliver, value, error)
        threading.Thread(target=work, daemon=True).start()

    def open_document(self, path):
        self.show("reader")
        self.run_job(lambda: read_document(path), self.set_document)

    def set_document(self, document):
        self.speech.stop()
        self.document = document
        self.reader_title.set_text(document.title)
        self.reader_note.set_text(document.note)
        self.reader.get_buffer().set_text(document.text)
        self.include_context.set_label("Use text from: " + document.title[:36])
        self.show("reader")

    def clear_document(self):
        self.speech.stop()
        self.document = None
        self.reader_title.set_text("Your reading space")
        self.reader_note.set_text("Open Word, PDF or text files. Documents stay on your computer.")
        self.reader.get_buffer().set_text("")
        self.include_context.set_label("Use current page text")
        self.include_context.set_active(False)

    def find_document(self, entry):
        text = entry.get_text()
        if not text:
            return
        buffer = self.reader.get_buffer()
        cursor = buffer.get_iter_at_mark(buffer.get_insert())
        result = cursor.forward_search(text, Gtk.TextSearchFlags.CASE_INSENSITIVE, None)
        if not result:
            result = buffer.get_start_iter().forward_search(text, Gtk.TextSearchFlags.CASE_INSENSITIVE, None)
        if result:
            start, end = result
            buffer.select_range(end, start)
            self.reader.scroll_to_iter(start, 0.1, False, 0, 0)
        else:
            self.window.notify("No matching text in this document.")

    def page_text(self, callback):
        tab = self.window.current
        if not tab:
            callback("")
            return
        uri = tab.uri
        def complete(view, result, *_):
            if self.closed:
                return
            try:
                value = view.evaluate_javascript_finish(result).to_string()
                if tab.uri != uri or self.window.current is not tab:
                    self.window.notify("The page changed. Run the reading action again.")
                    callback("")
                else:
                    callback(value[:60000])
            except GLib.Error:
                callback("")
        tab.view.evaluate_javascript("String(getSelection() || document.body?.innerText || '').slice(0,60000)",
                                    -1, "aster-reading", None, None, complete, None)

    def read_page(self):
        from .documents import Document
        title = self.window.current.view.get_title() or "Page reading view"
        self.page_text(lambda text: self.set_document(Document(title, text, "Selected text or page text · captured when you pressed Read this page."))
                       if text else self.window.notify("No readable page text was found."))

    def read_aloud(self):
        if self.document and self.stack.get_visible_child_name() == "reader":
            self.speak_text(self.buffer_text(self.reader, selection=True))
        else:
            self.page_text(self.speak_text)

    def speak_text(self, text):
        try:
            self.speech.speak(text, "es" if self.voice.get_selected() else "en", int(self.rate.get_value()))
            self.window.notify("Reading aloud. Use Stop reading to stop." + (" First 120,000 characters selected." if len(text) > 120000 else ""))
        except (ValueError, OSError) as error:
            self.window.notify(str(error))

    def choose_model(self):
        def selected(path):
            self.model = path
            self.model_label.set_text("Local AI model: " + path.name)
        self.choose_file("Choose a local GGUF AI model", selected, ["*.gguf"])

    def basic_mode(self):
        self.model = None
        self.model_label.set_text("Offline commands and text excerpts")

    def choose_voice(self):
        def selected(path):
            self.voice_model = path
            self.status.set_text("Voice model: " + path.name)
        self.choose_file("Choose the extracted Vosk model folder", selected, folder=True)

    def set_busy(self, busy):
        self.busy = busy
        self.ask_button.set_sensitive(not busy)
        self.listen_button.set_sensitive(not busy)

    def listen(self):
        if self.busy:
            return
        if not self.voice_model:
            self.status.set_text("Choose a local Vosk voice model first. Setup: docs/setup/assistant.md.")
            return
        self.speech.stop()
        self.set_busy(True)
        self.status.set_text("Preparing microphone, then listening for 8 seconds…")
        model = self.voice_model
        def capture():
            interpreter = os.environ.get("ASTER_ASSISTANT_PYTHON") or sys.executable
            self.voice_process = subprocess.Popen([interpreter, str(Path(__file__).with_name("voice_worker.py")), str(model)],
                                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                out, err = self.voice_process.communicate(timeout=45)
                response = json.loads(out)
                if response.get("error"):
                    raise ValueError(response["error"])
                return response.get("text", "")
            finally:
                if self.voice_process.poll() is None:
                    self.voice_process.kill()
                    self.voice_process.communicate()
        def done(text):
            self.set_busy(False)
            self.prompt.set_text(text)
            self.status.set_text("Review the words and press Ask." if text else "No speech recognized. Try again.")
        self.run_job(capture, done, self.failed)

    def failed(self, message):
        self.set_busy(False)
        self.status.set_text(message)

    def ask(self):
        if self.busy:
            return
        question = self.prompt.get_text().strip()
        if not question:
            return
        try:
            command = parse_command(question)
            if command:
                if command.action == "navigate":
                    self.window.new_tab(command.value)
                else:
                    self.window.lookup_action(command.action).activate(None)
                self.answer.get_buffer().set_text("Requested: " + question)
                return
        except (ValueError, GLib.Error) as error:
            self.failed(str(error))
            return
        self.set_busy(True)
        self.status.set_text("Working locally…")
        model = self.model
        def answer_with(context):
            job = lambda: generate_local(model, question, context, cancel=self.model_cancel) if model else local_answer(question, context)
            def done(answer):
                self.answer.get_buffer().set_text(answer)
                self.set_busy(False)
                self.status.set_text("Generated locally; check important details." if model else "Completed with offline text extraction.")
            self.run_job(job, done, self.failed)
        if self.include_context.get_active():
            if self.document:
                answer_with(self.buffer_text(self.reader, selection=True))
            else:
                self.page_text(answer_with)
        else:
            answer_with("")

    def check_media(self):
        self.show("media")
        tab = self.window.current
        if not tab:
            return
        uri = tab.uri
        self.media_result.get_buffer().set_text("Checking this page's media capabilities…")
        def complete(view, result, *_):
            if self.closed:
                return
            try:
                report = json.loads(view.call_async_javascript_function_finish(result).to_string())
                if tab.uri != uri:
                    raise ValueError("The page changed. Run the check again.")
                self.media_result.get_buffer().set_text(report_text(report))
            except (GLib.Error, ValueError) as error:
                self.media_result.get_buffer().set_text("The check could not finish: " + str(error))
        tab.view.call_async_javascript_function(MEDIA_PROBE, -1, None, "aster-media-check", None, None, complete, None)

    def close(self):
        self.closed = True
        self.speech.stop()
        self.model_cancel.set()
        if self.voice_process and self.voice_process.poll() is None:
            self.voice_process.terminate()
        for chooser in list(self.choosers):
            chooser.destroy()
        self.choosers.clear()
