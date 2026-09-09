"""Bounded, local text extraction. No Office automation, macros or network access."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

MAX_FILE = 32 * 1024 * 1024
MAX_XML = 8 * 1024 * 1024
MAX_TEXT = 1_000_000
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


class DocumentError(ValueError):
    pass


@dataclass(frozen=True)
class Document:
    title: str
    text: str
    note: str


def decode_text(data: bytes) -> str:
    try:
        if data.startswith((b"\xff\xfe", b"\xfe\xff")):
            return data.decode("utf-16")
        return data.decode("utf-8-sig")
    except UnicodeError as error:
        raise DocumentError("Save this text as UTF-8 or UTF-16 and open it again.") from error


def paragraph_text(node: ET.Element) -> str:
    pieces = []
    for child in node.iter():
        if child.tag == W + "t":
            pieces.append(child.text or "")
        elif child.tag == W + "tab":
            pieces.append("\t")
        elif child.tag in (W + "br", W + "cr"):
            pieces.append("\n")
    return "".join(pieces)


def docx_text(path: Path) -> str:
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > 4096 or sum(item.file_size for item in entries) > 128 * 1024 * 1024:
                raise DocumentError("This Word file is too large to read safely.")
            if sum(item.filename == "word/document.xml" for item in entries) != 1:
                raise DocumentError("The Word document body is missing or duplicated.")
            entry = archive.getinfo("word/document.xml")
            if entry.file_size > MAX_XML or entry.flag_bits & 1:
                raise DocumentError("This Word file is too large or encrypted.")
            # Read only the body in memory. Never extract ZIP paths or follow relationships.
            with archive.open(entry) as source:
                raw = source.read(MAX_XML + 1)
            if len(raw) > MAX_XML:
                raise DocumentError("The Word document body exceeds the reading limit.")
            xml = decode_text(raw)
            if "<!DOCTYPE" in xml.upper() or "<!ENTITY" in xml.upper():
                raise DocumentError("XML entities and document type declarations are unsupported.")
            root = ET.fromstring(xml)
    except (zipfile.BadZipFile, KeyError, RuntimeError, ET.ParseError, NotImplementedError) as error:
        raise DocumentError("This is not a readable .docx file. Try saving a new copy in Word.") from error
    body = root.find(W + "body")
    if body is None:
        raise DocumentError("The Word document contains no body text.")
    blocks = []
    for block in body:
        if block.tag == W + "p":
            blocks.append(paragraph_text(block))
        elif block.tag == W + "tbl":
            for row in block.findall(W + "tr"):
                blocks.append("\t".join("\n".join(paragraph_text(p) for p in cell.iter(W + "p"))
                                        for cell in row.findall(W + "tc")))
        elif block.tag == W + "sdt":
            blocks.extend(paragraph_text(p) for p in block.iter(W + "p"))
    return "\n\n".join(blocks)


def converted_text(path: Path, kind: str) -> str:
    executable = shutil.which("pdftotext" if kind == ".pdf" else "antiword")
    if not executable:
        package = "poppler-utils (Arch: poppler)" if kind == ".pdf" else "antiword, or save the file as .docx"
        raise DocumentError(f"Install {package} to read this file.")
    command = ([executable, "-f", "1", "-l", "200", "-enc", "UTF-8", str(path), "-"]
               if kind == ".pdf" else [executable, "-m", "UTF-8.txt", str(path)])
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        try:
            result = subprocess.run(command, stdout=output, stderr=errors, timeout=20, check=False)
        except subprocess.TimeoutExpired as error:
            raise DocumentError("Document conversion took too long. Try a smaller file.") from error
        if result.returncode:
            raise DocumentError("This file could not be read. It may be encrypted, damaged or unsupported.")
        output.seek(0)
        raw = output.read(MAX_TEXT * 4 + 1)
    if len(raw) > MAX_TEXT * 4:
        raise DocumentError("This file exceeds the text reading limit.")
    return decode_text(raw)


def read_document(filename: str | Path) -> Document:
    path = Path(filename).expanduser().resolve(strict=True)
    if not path.is_file() or path.stat().st_size > MAX_FILE:
        raise DocumentError("Choose a document smaller than 32 MB.")
    kind = path.suffix.lower()
    if kind == ".docx":
        text = docx_text(path)
        note = "Word reading view · body text and tables; images and page layout are not reproduced."
    elif kind in (".txt", ".md"):
        text = decode_text(path.read_bytes())
        note = "Local text document"
    elif kind in (".pdf", ".doc"):
        text = converted_text(path, kind)
        note = "PDF text · first 200 pages; scanned pages need OCR." if kind == ".pdf" else "Legacy Word text · converted locally with antiword."
    else:
        raise DocumentError("Open a .docx, .doc, .pdf, .txt or .md file. Macro-enabled Word files are unsupported.")
    if len(text) > MAX_TEXT:
        raise DocumentError("This file exceeds the one-million-character reading limit.")
    text = text.replace("\x00", "").strip()
    if not text:
        raise DocumentError("No readable text was found. Scanned documents need OCR first.")
    return Document(path.name, text, note)
