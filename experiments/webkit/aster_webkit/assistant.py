"""Offline commands/excerpts and optional local GGUF inference. No cloud client."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import time

from .core import navigation_target


@dataclass(frozen=True)
class Command:
    action: str
    value: str = ""


COMMANDS = {
    "new tab": "new-tab", "open a new tab": "new-tab", "nueva pestaña": "new-tab",
    "go back": "back", "go forward": "forward", "reload": "reload",
    "zoom in": "zoom-in", "zoom out": "zoom-out", "reset zoom": "zoom-reset",
    "bookmark this page": "bookmark", "save bookmark": "bookmark",
    "read aloud": "read-aloud", "read this": "read-aloud", "lee esto": "read-aloud",
    "stop reading": "stop-reading", "deja de leer": "stop-reading",
    "open document": "open-document", "abrir documento": "open-document",
    "fullscreen": "fullscreen", "check streaming": "media-check",
}


def parse_command(prompt: str) -> Command | None:
    clean = " ".join(prompt.strip().split())
    lowered = clean.casefold()
    if lowered in COMMANDS:
        return Command(COMMANDS[lowered])
    for prefix in ("open ", "search ", "buscar "):
        if lowered.startswith(prefix) and clean[len(prefix):].strip():
            return Command("navigate", navigation_target(clean[len(prefix):]))
    return None


def local_answer(question: str, text: str) -> str:
    """Extractive reading assistance, deliberately not labeled a generative model."""
    if question.casefold().strip() in ("help", "what can you do", "ayuda"):
        return ("Try: new tab, open example.com, search networking, bookmark this page, "
                "open document, read aloud, stop reading, fullscreen or check streaming. "
                "Use page/document text for a summary or to find related passages. "
                "Choose a local GGUF model for generated answers.")
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+|\n+", text[:60_000]) if len(s.strip()) > 12]
    if not sentences:
        return "Select ‘Use page/document text’ or open a document first. For general questions, choose a local GGUF model."
    stop = {"the", "and", "this", "that", "with", "from", "what", "does", "about", "summarize", "summary", "page", "document"}
    words = lambda s: [w for w in re.findall(r"[^\W\d_]{3,}", s.casefold()) if w not in stop]
    query = set(words(question))
    counts = Counter(word for sentence in sentences for word in words(sentence))
    def score(item):
        index, sentence = item
        tokens = set(words(sentence))
        return (len(query & tokens) * 10 + sum(counts[w] for w in tokens) / max(len(tokens), 1), -index)
    ranked = sorted(enumerate(sentences), key=score, reverse=True)
    if query and not any(query & set(words(s)) for s in sentences):
        return "I found no matching passage in the supplied text. Try a different question or use a local model."
    chosen = sorted(ranked[:4])
    return "Passages from your text (extracted locally):\n\n" + "\n\n".join(sentence for _, sentence in chosen)


def generate_local(model: Path, question: str, context: str = "", *, python: str | None = None, cancel=None) -> str:
    """Run a local-only worker; user-selected model files cannot select a server URL."""
    model = model.expanduser().resolve(strict=True)
    with model.open("rb") as source:
        if source.read(4) != b"GGUF":
            raise ValueError("Choose a GGUF model file downloaded from a publisher you trust.")
    interpreter = python or os.environ.get("ASTER_ASSISTANT_PYTHON") or sys.executable
    worker = Path(__file__).with_name("local_model_worker.py")
    payload = json.dumps({"model": str(model), "question": question[:4000], "context": context[:10000]}, ensure_ascii=False)
    if cancel and cancel.is_set():
        raise ValueError("Local inference cancelled.")
    process = subprocess.Popen([interpreter, str(worker)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, encoding="utf-8",
                               env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    deadline = time.monotonic() + 180
    pending_input = payload
    try:
        while True:
            if cancel and cancel.is_set():
                raise ValueError("Local inference cancelled.")
            if time.monotonic() > deadline:
                raise ValueError("The local model took too long. Try a smaller model or a shorter question.")
            try:
                output, _errors = process.communicate(input=pending_input, timeout=0.25)
                break
            except subprocess.TimeoutExpired:
                pending_input = None
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate()
    try:
        response = json.loads(output)
    except (ValueError, TypeError) as error:
        raise ValueError("The local model worker failed. Check its Python environment and available memory.") from error
    if process.returncode or response.get("error"):
        raise ValueError(response.get("error", "Local inference failed."))
    answer = response.get("answer")
    if not isinstance(answer, str) or not answer.strip():
        raise ValueError("The local model returned no answer.")
    return answer[:24000]
