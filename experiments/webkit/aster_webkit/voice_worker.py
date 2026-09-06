"""Eight-second push-to-talk capture with an existing local Vosk model."""
import json
from pathlib import Path
import queue
import sys
import time
import wave


def transcribe_wav(directory, filename):
    """Decode an existing short mono PCM recording, also used by the offline smoke check."""
    from vosk import Model, KaldiRecognizer, SetLogLevel
    SetLogLevel(-1)
    parts = []
    with wave.open(str(filename), "rb") as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2 or source.getcomptype() != "NONE":
            raise ValueError("Use a mono 16-bit PCM WAV recording.")
        if source.getnframes() > source.getframerate() * 30:
            raise ValueError("Use a recording no longer than 30 seconds.")
        recognizer = KaldiRecognizer(Model(str(directory)), source.getframerate())
        while data := source.readframes(4000):
            if recognizer.AcceptWaveform(data):
                parts.append(json.loads(recognizer.Result()).get("text", ""))
        parts.append(json.loads(recognizer.FinalResult()).get("text", ""))
    return " ".join(parts).strip()


def main():
    try:
        directory = Path(sys.argv[1]).expanduser().resolve(strict=True)
        if not (directory / "am/final.mdl").is_file():
            raise ValueError("Choose the extracted Vosk model folder containing am/final.mdl.")
        if len(sys.argv) == 4 and sys.argv[2] == "--wav":
            print(json.dumps({"text": transcribe_wav(directory, sys.argv[3])}))
            return 0
        import sounddevice as sd
        from vosk import Model, KaldiRecognizer, SetLogLevel
        SetLogLevel(-1)
        recognizer = KaldiRecognizer(Model(str(directory)), 16000)
        pending = queue.Queue(maxsize=50)
        def capture(data, frames, timing, status):
            try:
                pending.put_nowait(bytes(data))
            except queue.Full:
                pass
        parts = []
        with sd.RawInputStream(samplerate=16000, blocksize=4000, dtype="int16", channels=1, callback=capture):
            end = time.monotonic() + 8
            while time.monotonic() < end:
                try:
                    data = pending.get(timeout=0.3)
                except queue.Empty:
                    continue
                if recognizer.AcceptWaveform(data):
                    parts.append(json.loads(recognizer.Result()).get("text", ""))
        parts.append(json.loads(recognizer.FinalResult()).get("text", ""))
        print(json.dumps({"text": " ".join(parts).strip()}))
    except ImportError:
        print(json.dumps({"error": "Voice input needs vosk and sounddevice. See docs/setup/assistant.md."}))
        return 1
    except Exception as error:
        print(json.dumps({"error": "Voice input failed: " + str(error)[:250]}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
