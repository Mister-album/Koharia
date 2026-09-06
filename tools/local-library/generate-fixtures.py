#!/usr/bin/env python3
"""Generate deterministic local-media device fixtures without external packages."""

from __future__ import annotations

import argparse
import binascii
import struct
import zlib
import zipfile
from pathlib import Path


FIXED_ZIP_TIME = (2020, 1, 1, 0, 0, 0)


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    body = kind + payload
    return struct.pack(">I", len(payload)) + body + struct.pack(">I", binascii.crc32(body) & 0xFFFFFFFF)


def oversized_png(width: int = 1200, height: int = 2000) -> bytes:
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        color = bytes(((y * 7) % 256, (y * 13) % 256, (y * 29) % 256))
        rows.extend(color * width)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(bytes(rows), level=9))
        + png_chunk(b"IEND", b"")
    )


def add_entry(archive: zipfile.ZipFile, name: str, data: bytes, *, stored: bool = False) -> None:
    info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def generate_epub(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w") as archive:
        add_entry(archive, "mimetype", b"application/epub+zip", stored=True)
        add_entry(
            archive,
            "META-INF/container.xml",
            b'''<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>''',
        )
        add_entry(
            archive,
            "OEBPS/content.opf",
            b'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:koharia:test:standalone-image</dc:identifier>
    <dc:title>Koharia Standalone Image Regression</dc:title>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="page1" href="page1.xhtml" media-type="application/xhtml+xml"/>
    <item id="page2" href="page2.xhtml" media-type="application/xhtml+xml"/>
    <item id="image" href="images/oversized.png" media-type="image/png"/>
  </manifest>
  <spine><itemref idref="page1"/><itemref idref="page2"/></spine>
</package>''',
        )
        add_entry(
            archive,
            "OEBPS/nav.xhtml",
            b'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head><body><nav epub:type="toc"><ol>
<li><a href="page1.xhtml">Oversized image</a></li><li><a href="page2.xhtml">Text sentinel</a></li>
</ol></nav></body></html>''',
        )
        add_entry(
            archive,
            "OEBPS/page1.xhtml",
            b'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Oversized image</title></head>
<body><div><img src="images/oversized.png" alt="Koharia oversized image test" style="width:2400px;height:4000px"/></div></body></html>''',
        )
        add_entry(
            archive,
            "OEBPS/page2.xhtml",
            b'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Text sentinel</title></head>
<body><h1>KOHARIA_EPUB_NEXT_PAGE_OK</h1><p>The standalone image page advanced successfully.</p></body></html>''',
        )
        add_entry(archive, "OEBPS/images/oversized.png", oversized_png())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate_epub(args.output.resolve())
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
