"""Audit a PdfExtractionEngine/derived EPUB directory without modifying the book."""

import argparse
import collections
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def audit(directory):
    actual = collections.defaultdict(str)
    ruby_characters = collections.defaultdict(set)
    ruby_groups = collections.defaultdict(list)
    ids = set()
    duplicate_ids = []
    with ZipFile(directory / "book.epub") as archive:
        assert archive.namelist()[0] == "mimetype"
        assert archive.read("mimetype") == b"application/epub+zip"
        assert archive.testzip() is None
        for name in archive.namelist():
            if not name.startswith("EPUB/text/"):
                continue
            for element in ET.fromstring(archive.read(name)).iter():
                anchor = element.get("data-pdf-anchor", "")
                if not anchor:
                    continue
                if anchor in ids:
                    duplicate_ids.append(anchor)
                ids.add(anchor)
                if "-r" in anchor:
                    page_index = int(anchor.split("-")[1])
                    def body_text(node):
                        if node.tag.rsplit("}", 1)[-1] == "rt":
                            indices = [int(x) for x in node.get("data-pdf-characters", "").split(",") if x]
                            ruby_characters[page_index].update(indices)
                            ruby_groups[page_index].append((indices, "".join(node.itertext())))
                            return ""
                        return (node.text or "") + "".join(body_text(child) + (child.tail or "") for child in node)
                    actual[page_index] += body_text(element)

    normalize = lambda text: "".join(text.split())
    passed, mismatches, fallback = [], [], []
    ruby_mismatches = []
    for path in sorted(directory.glob("page-*.json")):
        page = json.loads(path.read_text(encoding="utf-8"))
        if page.get("fallbackAsset"):
            fallback.append(page["index"])
            continue
        # Compare the text layer's body order with emitted XHTML; margin removal is evaluated separately.
        expected = normalize("".join(
            glyph["text"] for glyph in page["glyphs"]
            if page["height"] * .09 <= glyph["box"]["top"]
            and glyph["box"]["bottom"] <= page["height"] * .92
            and glyph["box"]["bottom"] > glyph["box"]["top"]
            and glyph["index"] not in ruby_characters[page["index"]]
        ))
        output = normalize(actual[page["index"]])
        (passed if expected in output else mismatches).append(page["index"])
        glyphs = {glyph["index"]: glyph["text"] for glyph in page["glyphs"]}
        for indices, annotation in ruby_groups[page["index"]]:
            if normalize("".join(glyphs[i] for i in indices)) != normalize(annotation):
                ruby_mismatches.append(page["index"])
    return {
        "orderedBodyPagesPassed": len(passed),
        "mismatchedPages": mismatches,
        "fallbackPages": sorted(fallback),
        "duplicateAnchorIds": duplicate_ids,
        "rubyGroups": sum(map(len, ruby_groups.values())),
        "rubyMismatches": ruby_mismatches,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    result = audit(args.directory)
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result["mismatchedPages"] or result["duplicateAnchorIds"] or result["rubyMismatches"]))
