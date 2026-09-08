#!/usr/bin/env python3
"""Builds the two bundled sample EPUBs from the sources in scripts/samples/.

The output is committed under app/src/main/assets/samples/ and shipped inside
the APK (#48). Two properties matter and both are enforced here:

1. **Deterministic bytes.** Every entry gets the same fixed timestamp and the
   same compression level, so rebuilding unchanged sources reproduces the same
   archive byte for byte -- and therefore the same SHA-256. That digest is the
   sample's book identity, pinned in BundledSample.kt and checked on every
   build by SampleAssetsTest.
2. **A real EPUB.** `mimetype` is stored first and uncompressed, as the EPUB
   specification requires, and the package and navigation documents are the
   ordinary EPUB 3 ones, so the sample travels through exactly the same
   reader open path as a book the user added themselves.

Usage:

    python3 scripts/build-sample-epubs.py

Then run `./gradlew testDebugUnitTest --tests '*SampleAssetsTest'`. If the
sources changed, that test fails with the new digest to paste into
BundledSample.kt -- the digest is never derived at runtime.

Provenance and licence of the text itself: scripts/samples/README.md.
"""

from __future__ import annotations

import hashlib
import pathlib
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
SOURCES = REPO / "scripts" / "samples"
OUTPUT = REPO / "app" / "src" / "main" / "assets" / "samples"

# A fixed DOS timestamp (2026-01-01 00:00:00). Without it zipfile stamps "now"
# and two builds of identical content produce different digests.
FIXED_TIME = (2026, 1, 1, 0, 0, 0)

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

SAMPLES = {
    "sample-en.epub": {
        "source": "en",
        "language": "en",
        "title": "A Word at a Time",
        "creator": "FastReader",
        # Stable, made-up URNs: the sample is not a published work and has no
        # ISBN. They only have to be constant across builds.
        "identifier": "urn:uuid:f0b3a1d2-5c47-4e9b-9a01-5ea8c1d7b400",
        "story_title": "A Word at a Time",
        "about_title": "About this text",
        "rights": "Public domain (CC0 1.0 Universal). Written for FastReader.",
    },
    "sample-es.epub": {
        "source": "es",
        "language": "es",
        "title": "Una palabra a la vez",
        "creator": "FastReader",
        "identifier": "urn:uuid:f0b3a1d2-5c47-4e9b-9a01-5ea8c1d7b401",
        "story_title": "Una palabra a la vez",
        "about_title": "Sobre este texto",
        "rights": "Dominio público (CC0 1.0 Universal). Escrito para FastReader.",
    },
}


def escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def package_document(meta: dict) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">{escape(meta["identifier"])}</dc:identifier>
    <dc:title>{escape(meta["title"])}</dc:title>
    <dc:creator>{escape(meta["creator"])}</dc:creator>
    <dc:language>{escape(meta["language"])}</dc:language>
    <dc:rights>{escape(meta["rights"])}</dc:rights>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="story" href="story.xhtml" media-type="application/xhtml+xml"/>
    <item id="about" href="about.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="story"/>
    <itemref idref="about"/>
  </spine>
</package>
"""


def navigation_document(meta: dict) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"
      xml:lang="{escape(meta["language"])}" lang="{escape(meta["language"])}">
  <head>
    <title>{escape(meta["title"])}</title>
  </head>
  <body>
    <nav epub:type="toc" id="toc">
      <ol>
        <li><a href="story.xhtml">{escape(meta["story_title"])}</a></li>
        <li><a href="about.xhtml">{escape(meta["about_title"])}</a></li>
      </ol>
    </nav>
  </body>
</html>
"""


def build(name: str, meta: dict) -> tuple[pathlib.Path, str, int]:
    source_dir = SOURCES / meta["source"]
    entries = [
        ("META-INF/container.xml", CONTAINER.encode("utf-8")),
        ("OEBPS/content.opf", package_document(meta).encode("utf-8")),
        ("OEBPS/nav.xhtml", navigation_document(meta).encode("utf-8")),
        ("OEBPS/story.xhtml", (source_dir / "story.xhtml").read_bytes()),
        ("OEBPS/about.xhtml", (source_dir / "about.xhtml").read_bytes()),
    ]

    OUTPUT.mkdir(parents=True, exist_ok=True)
    target = OUTPUT / name
    with zipfile.ZipFile(target, "w") as archive:
        # The EPUB specification: `mimetype` first, stored, never compressed.
        mimetype = zipfile.ZipInfo("mimetype", date_time=FIXED_TIME)
        mimetype.compress_type = zipfile.ZIP_STORED
        mimetype.external_attr = 0o644 << 16
        archive.writestr(mimetype, b"application/epub+zip")

        for entry_name, payload in entries:
            info = zipfile.ZipInfo(entry_name, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload, compresslevel=9)

    payload = target.read_bytes()
    return target, hashlib.sha256(payload).hexdigest(), len(payload)


def main() -> None:
    for name, meta in SAMPLES.items():
        target, digest, size = build(name, meta)
        print(f"{target.relative_to(REPO)}  {size} bytes  sha256:{digest}")


if __name__ == "__main__":
    main()
