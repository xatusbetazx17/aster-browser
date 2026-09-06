from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from aster_webkit.documents import DocumentError, MAX_XML, read_document

PREFIX = '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
SUFFIX = '</w:body></w:document>'


def write_docx(path, body):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", PREFIX + body + SUFFIX)


class DocumentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_word_paragraphs_tables_unicode_and_breaks(self):
        path = self.root / "Work with spaces.docx"
        write_docx(path, '<w:p><w:r><w:t>Hello &amp; hola</w:t><w:br/><w:t>Marcelo</w:t></w:r></w:p>'
                   '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Artículo</w:t></w:r></w:p></w:tc>'
                   '<w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>')
        original = path.read_bytes()
        document = read_document(path)
        self.assertEqual(document.text, "Hello & hola\nMarcelo\n\nArtículo\t2")
        self.assertEqual(path.read_bytes(), original)

    def test_word_does_not_execute_markup_or_follow_relationships(self):
        path = self.root / "example.docx"
        write_docx(path, '<w:p><w:r><w:t>&lt;script&gt;alert(1)&lt;/script&gt;</w:t>'
                   '<w:instrText>INCLUDETEXT "https://example.invalid"</w:instrText></w:r></w:p>')
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("../should-not-exist", "do not extract")
            archive.writestr("word/_rels/document.xml.rels", "https://example.invalid")
        self.assertEqual(read_document(path).text, "<script>alert(1)</script>")
        self.assertEqual(list(self.root.iterdir()), [path])

    def test_malformed_encrypted_or_missing_body_fails(self):
        path = self.root / "broken.docx"
        path.write_bytes(b"not a zip")
        with self.assertRaises(DocumentError): read_document(path)
        with zipfile.ZipFile(path, "w") as archive: archive.writestr("other.xml", "missing")
        with self.assertRaises(DocumentError): read_document(path)

    def test_entity_expansion_is_rejected_in_utf8_and_utf16(self):
        for encoding in ("utf-8", "utf-16"):
            path = self.root / (encoding + ".docx")
            xml = '<!DOCTYPE a [<!ENTITY a "expanded">]>' + PREFIX + '<w:p><w:r><w:t>&a;</w:t></w:r></w:p>' + SUFFIX
            with zipfile.ZipFile(path, "w") as archive: archive.writestr("word/document.xml", xml.encode(encoding))
            with self.assertRaises(DocumentError): read_document(path)

    def test_compressed_oversized_body_is_rejected(self):
        path = self.root / "too-large.docx"
        write_docx(path, " " * (MAX_XML + 1))
        with self.assertRaises(DocumentError): read_document(path)

    def test_text_encodings_and_unsupported_macro_file(self):
        for encoding in ("utf-8-sig", "utf-16"):
            path = self.root / (encoding + ".txt")
            path.write_bytes("Español & English".encode(encoding))
            self.assertEqual(read_document(path).text, "Español & English")
        path = self.root / "macro.docm"
        path.write_text("do not run")
        with self.assertRaises(DocumentError): read_document(path)

    def test_pdf_missing_dependency_is_actionable(self):
        path = self.root / "page.pdf"
        path.write_bytes(b"%PDF-1.7\n")
        with patch("aster_webkit.documents.shutil.which", return_value=None):
            with self.assertRaisesRegex(DocumentError, "poppler"):
                read_document(path)

    def test_word_field_instructions_are_not_read_out(self):
        path = self.root / "fields.docx"
        write_docx(path, '<w:p><w:r><w:instrText>DDEAUTO cmd /c arbitrary</w:instrText><w:t>Visible text</w:t></w:r></w:p>')
        self.assertEqual(read_document(path).text, "Visible text")


if __name__ == "__main__": unittest.main()
