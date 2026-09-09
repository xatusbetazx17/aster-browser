"""Local speech playback. Text is passed through stdin, never through a shell."""
import shutil
import subprocess
import tempfile
import threading


class Speech:
    def __init__(self):
        self.process = None
        self._lock = threading.Lock()

    def speak(self, text: str, voice: str = "en", rate: int = 175):
        if not text.strip():
            raise ValueError("There is no text to read aloud.")
        executable = shutil.which("espeak-ng") or shutil.which("espeak")
        if not executable:
            raise ValueError("Install espeak-ng to enable offline read-aloud.")
        if voice not in ("en", "es"):
            raise ValueError("Choose an English or Spanish voice.")
        self.stop()
        # A temporary stream avoids blocking the UI on a full stdin pipe for long documents.
        with tempfile.TemporaryFile() as source:
            source.write(text[:120000].encode("utf-8"))
            source.seek(0)
            with self._lock:
                self.process = subprocess.Popen([executable, "--stdin", "-v", voice, "-s", str(max(100, min(300, rate)))],
                                                stdin=source, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        threading.Thread(target=self.process.wait, daemon=True).start()

    def stop(self):
        with self._lock:
            process, self.process = self.process, None
        if process and process.poll() is None:
            process.terminate()

