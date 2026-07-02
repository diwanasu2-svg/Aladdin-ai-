"""File parsers for PDF, DOCX, Excel, text, and images."""
from .pdf_parser import PDFParser
from .docx_parser import DOCXParser
from .excel_parser import ExcelParser
from .text_parser import TextParser
from .image_parser import ImageParser

_PARSERS = {
    "pdf": PDFParser, "docx": DOCXParser, "xlsx": ExcelParser,
    "xls": ExcelParser, "txt": TextParser, "md": TextParser,
    "csv": TextParser, "json": TextParser, "jpg": ImageParser,
    "jpeg": ImageParser, "png": ImageParser, "webp": ImageParser,
    "gif": ImageParser,
}


def get_parser(extension: str):
    """Return the right parser class for a file extension."""
    return _PARSERS.get(extension.lower().lstrip("."))
