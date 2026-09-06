#!/usr/bin/env python3
"""Build the checked local-media test matrix from a populated fixture directory."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ARCHIVES = ["zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt"]
IMAGES = ["jpg", "jpeg", "png", "gif", "webp", "avif", "heif", "heic", "jxl"]
BOOKS = ["epub", "pdf", "txt", "mobi", "prc", "azw", "azw3", "djvu", "djv"]
SUPPORTED = ARCHIVES + IMAGES + BOOKS


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def first(root: Path, pattern: str) -> Path:
    matches = sorted(root.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"No fixture matches {pattern}")
    return matches[0]


def largest(root: Path, pattern: str) -> Path:
    matches = list(root.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"No fixture matches {pattern}")
    return max(matches, key=lambda item: item.stat().st_size)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--generated-epub", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    library = args.library.resolve()
    generated_epub = args.generated_epub.resolve()
    cases: list[dict[str, object]] = []

    def add(
        case_id: str,
        extension: str,
        media_kind: str,
        organization_mode: str,
        source: Path,
        device_relative_path: str,
        expected_reader: str,
        assertions: list[str],
        *,
        expected_outcome: str = "success",
        support: str = "stable",
        timeout_seconds: int = 20,
        tags: list[str] | None = None,
        entry_title: str | None = None,
        chapter_title: str | None = None,
        page_number: int | None = None,
    ) -> None:
        source = source.resolve()
        try:
            source_relative = source.relative_to(library).as_posix()
            source_root = "library"
        except ValueError:
            source_relative = source.name
            source_root = "generated"
        cases.append(
            {
                "caseId": case_id,
                "extension": extension,
                "mediaKind": media_kind,
                "organizationMode": organization_mode,
                "sourceRoot": source_root,
                "sourceRelativePath": source_relative,
                "deviceRelativePath": device_relative_path,
                "sha256": sha256(source),
                "sizeBytes": source.stat().st_size,
                "expectedReader": expected_reader,
                "expectedOutcome": expected_outcome,
                "support": support,
                "timeoutSeconds": timeout_seconds,
                "entryTitle": entry_title,
                "chapterTitle": chapter_title,
                "pageNumber": page_number,
                "assertions": assertions,
                "tags": tags or [],
            },
        )

    series_archive = library / "series-library/comics/[Public Test] Archive Formats"
    individual_comics = library / "single-file-library/comics"
    for extension in ARCHIVES:
        source = first(series_archive, f"*Public Images.{extension}")
        add(
            f"series-archive-{extension}", extension, "archive", "series", source,
            source.relative_to(library).as_posix(), "ReaderActivity",
            ["indexedOnce", "firstFrame", "forwardBackward", "zoomPan", "resume", "seriesBoundary"],
            entry_title="[Public Test] Archive Formats", chapter_title=source.stem,
        )
        individual_matches = sorted(
            item
            for item in individual_comics.glob(f"Public Test - *.{extension}")
            if "negative" not in item.name.lower()
        )
        individual = individual_matches[0] if individual_matches else source
        individual_path = individual.relative_to(library).as_posix()
        if not individual_matches:
            individual_path = f"single-file-library/comics/Public Test - {extension.upper()} Comic.{extension}"
        add(
            f"individual-archive-{extension}", extension, "archive", "individual", individual,
            individual_path, "ReaderActivity",
            ["indexedOnce", "firstFrame", "forwardBackward", "zoomPan", "resume", "isolatedEntry"],
            entry_title=Path(individual_path).stem,
        )

    series_image_dir = library / "series-library/image-folders/[Public Test] Image Formats/Vol.01"
    image_sources = {
        "jpg": first(series_image_dir, "*.jpeg"),
        "jpeg": first(series_image_dir, "*.jpeg"),
        "png": first(series_image_dir, "*.png"),
        "gif": first(series_image_dir, "*.gif"),
        "webp": first(series_image_dir, "*static.webp"),
        "avif": first(series_image_dir, "*.avif"),
        "heif": first(series_image_dir, "*.heif"),
        "heic": first(series_image_dir, "*.heic"),
        "jxl": first(series_image_dir, "*.jxl"),
    }
    individual_image_sources = {
        "jpg": first(individual_comics, "*JPEG.jpeg"),
        "jpeg": first(individual_comics, "*JPEG.jpeg"),
        "png": first(individual_comics, "*.png"),
        "gif": first(individual_comics, "*.gif"),
        "webp": first(individual_comics, "*Static WebP.webp"),
        "avif": first(individual_comics, "*.avif"),
        "heif": first(individual_comics, "*.heif"),
        "heic": first(individual_comics, "*.heic"),
        "jxl": first(individual_comics, "*.jxl"),
    }
    series_image_pages = {
        "jpg": 1,
        "jpeg": 2,
        "png": 3,
        "gif": 4,
        "webp": 5,
        "avif": 7,
        "heic": 8,
        "heif": 9,
        "jxl": 10,
    }
    for extension in IMAGES:
        source = image_sources[extension]
        device_path = source.relative_to(library).as_posix()
        if extension == "jpg":
            device_path = "series-library/image-folders/[Public Test] Image Formats/Vol.01/000-JPG.jpg"
        assertions = ["indexedOnce", "firstFrame", "zoomPan", "orientation"]
        if extension == "gif":
            assertions.append("animationFrames")
        elif extension == "webp":
            assertions.append("staticPixelStable")
        add(
            f"series-image-{extension}", extension, "image", "series", source, device_path,
            "ReaderActivity", assertions + ["forwardBackward", "seriesBoundary"],
            entry_title="[Public Test] Image Formats", chapter_title="Vol.01",
            page_number=series_image_pages[extension],
        )
        individual = individual_image_sources[extension]
        individual_path = individual.relative_to(library).as_posix()
        if extension == "jpg":
            individual_path = "single-file-library/comics/Public Test - JPG.jpg"
        add(
            f"individual-image-{extension}", extension, "image", "individual", individual,
            individual_path, "ReaderActivity", assertions + ["isolatedEntry"],
            entry_title=Path(individual_path).stem,
        )

    series_books = library / "series-library/novels/[Public Test] Alice and Formats"
    individual_books = library / "single-file-library/novels"
    series_book_sources = {
        "epub": first(series_books, "*.epub"),
        "pdf": first(individual_comics, "*.pdf"),
        "txt": first(series_books, "*UTF-8.txt"),
        "mobi": first(series_books, "*.mobi"),
        "prc": first(series_books, "*.prc"),
        "azw": first(series_books, "*.azw"),
        "azw3": first(series_books, "*.azw3"),
        "djvu": first(series_books, "*.djvu"),
        "djv": first(series_books, "*.djv"),
    }
    individual_book_sources = {
        "epub": first(series_books, "*.epub"),
        "pdf": first(individual_comics, "*.pdf"),
        "txt": first(series_books, "*UTF-8.txt"),
        "mobi": first(individual_books, "*.mobi"),
        "prc": first(individual_books, "*.prc"),
        "azw": first(individual_books, "*.azw"),
        "azw3": first(individual_books, "*.azw3"),
        "djvu": first(individual_books, "*.djvu"),
        "djv": first(individual_books, "*.djv"),
    }
    reflowable = {"epub", "txt", "mobi", "prc", "azw", "azw3"}
    experimental = {"mobi", "prc", "azw", "azw3"}
    for extension in BOOKS:
        reader = "EpubReaderActivity" if extension == "epub" else "ReaderActivity"
        assertions = ["indexedOnce", "firstFrame", "forwardBackward", "middleAndEnd", "resume"]
        if extension in reflowable:
            assertions.extend(["textReadable", "tocAndProgress"])
        source = series_book_sources[extension]
        series_path = source.relative_to(library).as_posix()
        if extension == "pdf":
            series_path = "series-library/novels/[Public Test] PDF/01 Public PDF.pdf"
        add(
            f"series-book-{extension}", extension, "book", "series", source, series_path, reader,
            assertions + ["seriesBoundary"], support="experimental" if extension in experimental else "stable",
            timeout_seconds=60 if extension == "pdf" else 30,
            entry_title=Path(series_path).parent.name, chapter_title=Path(series_path).stem,
        )
        individual = individual_book_sources[extension]
        individual_path = individual.relative_to(library).as_posix()
        if extension == "epub":
            individual_path = "single-file-library/novels/Public Test - Alice.epub"
        elif extension == "txt":
            individual_path = "single-file-library/novels/Public Test - Alice UTF-8.txt"
        add(
            f"individual-book-{extension}", extension, "book", "individual", individual,
            individual_path, reader, assertions + ["isolatedEntry"],
            support="experimental" if extension in experimental else "stable",
            timeout_seconds=60 if extension == "pdf" else 30,
            entry_title=Path(individual_path).stem,
        )

    for source in sorted(series_books.glob("*.txt")):
        if "UTF-8.txt" in source.name:
            continue
        encoding = source.stem.split("Alice ", 1)[-1].replace(" ", "-").lower()
        add(
            f"series-text-{encoding}", "txt", "book", "series", source,
            source.relative_to(library).as_posix(), "ReaderActivity",
            ["indexedOnce", "firstFrame", "textReadable", "forwardBackward", "resume"],
            tags=["text-encoding"], entry_title=series_books.name, chapter_title=source.stem,
        )

    animated_webp = first(individual_comics, "*Animated WebP.webp")
    add(
        "individual-image-webp-animated", "webp", "image", "individual", animated_webp,
        animated_webp.relative_to(library).as_posix(), "ReaderActivity",
        ["indexedOnce", "firstFrame", "animationFrames", "isolatedEntry"], tags=["animation"],
        entry_title=animated_webp.stem,
    )
    series_animated_webp = first(series_image_dir, "*animated.webp")
    add(
        "series-image-webp-animated", "webp", "image", "series", series_animated_webp,
        series_animated_webp.relative_to(library).as_posix(), "ReaderActivity",
        ["indexedOnce", "firstFrame", "animationFrames", "forwardBackward", "seriesBoundary"],
        tags=["animation"], entry_title="[Public Test] Image Formats", chapter_title="Vol.01", page_number=6,
    )

    for mode, root in (("series", series_archive), ("individual", individual_comics)):
        for extension in ("rar", "cbr"):
            negative = first(root, f"*Negative*{extension.upper()}*.*")
            add(
                f"{mode}-negative-{extension}-no-pages", extension, "archive", mode, negative,
                negative.relative_to(library).as_posix(), "ReaderActivity", ["handledError", "appResponsive"],
                expected_outcome="handled_error", tags=["negative", "no-pages"],
                entry_title=series_archive.name if mode == "series" else negative.stem,
                chapter_title=negative.stem if mode == "series" else None,
            )

    for mode, device_path in (
        ("series", "series-library/novels/[Public Test] Regression EPUB/01 Standalone Image Regression.epub"),
        ("individual", "single-file-library/novels/Public Test - Standalone Image Regression.epub"),
    ):
        add(
            f"{mode}-epub-standalone-image-regression", "epub", "book", mode, generated_epub,
            device_path, "EpubReaderActivity",
            ["indexedOnce", "firstFrameFitted", "noInitialResizeFlash", "imagePreview", "forwardBackward", "resume"],
            timeout_seconds=30, tags=["regression", "standalone-image"],
            entry_title=Path(device_path).parent.name if mode == "series" else Path(device_path).stem,
            chapter_title=Path(device_path).stem if mode == "series" else None,
        )

    for label, extension, source, reader in (
        ("large-cbz", "cbz", largest(library, "**/*.cbz"), "ReaderActivity"),
        ("large-pdf", "pdf", largest(library, "**/*.pdf"), "ReaderActivity"),
        ("large-epub", "epub", largest(library, "**/*.epub"), "EpubReaderActivity"),
    ):
        add(
            f"performance-{label}", extension, "performance", "existing", source,
            source.relative_to(library).as_posix(), reader,
            ["firstFrame", "openDuration", "peakMemory", "appResponsive"],
            timeout_seconds=90, tags=["performance", "large"], entry_title=source.stem,
        )

    payload = {
        "schemaVersion": 1,
        "supportedExtensions": SUPPORTED,
        "generatedBy": "tools/local-library/build-test-cases.py",
        "cases": cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"cases={len(cases)} output={args.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
