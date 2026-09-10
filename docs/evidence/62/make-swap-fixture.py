"""Builds the #62 swap fixture pair.

`original.epub` is an ordinary EPUB whose entries are all STORED, so its chapter
text sits in the file as plain bytes. `swapped.epub` is that same file with one
same-length byte substitution inside the chapter — ALPHA becomes OMEGA — and the
resulting CRC-32 patched into the entry's local header and its central-directory
record. The two files are therefore the same length to the byte, and the only
thing that moved in the central directory is one entry's CRC-32.
"""
import zipfile, zlib, os, sys, hashlib

OUT = os.path.dirname(os.path.abspath(__file__))
FIXED = (2020, 9, 1, 12, 0, 0)

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:leaf62-swap-fixture</dc:identifier>
    <dc:title>Swap Test</dc:title>
    <dc:creator>Leaf Sixty Two</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>"""

WORDS = ["ALPHA"] + ["word%02d" % n for n in range(1, 60)]
CHAPTER = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>'
           + " ".join(WORDS) + '.</p></body></html>')

ENTRIES = [
    ("mimetype", b"application/epub+zip"),
    ("META-INF/container.xml", CONTAINER.encode()),
    ("OEBPS/content.opf", OPF.encode()),
    ("OEBPS/chapter1.xhtml", CHAPTER.encode()),
]

original = os.path.join(OUT, "original.epub")
with zipfile.ZipFile(original, "w") as z:
    for name, data in ENTRIES:
        info = zipfile.ZipInfo(name, date_time=FIXED)
        info.compress_type = zipfile.ZIP_STORED
        info.external_attr = 0o644 << 16
        z.writestr(info, data)

raw = bytearray(open(original, "rb").read())

# Substitute inside the chapter's stored data. Same length, so no offset moves.
needle, replacement = b"ALPHA", b"OMEGA"
assert len(needle) == len(replacement)
at = raw.find(needle)
assert at != -1 and raw.find(needle, at + 1) == -1, "the marker must occur exactly once"
raw[at:at + len(needle)] = replacement

new_crc = zlib.crc32(CHAPTER.replace("ALPHA", "OMEGA").encode()) & 0xFFFFFFFF
old_crc = zlib.crc32(CHAPTER.encode()) & 0xFFFFFFFF
old_bytes = old_crc.to_bytes(4, "little")
new_bytes = new_crc.to_bytes(4, "little")

# The CRC appears twice for this entry: local header offset 14, central header
# offset 16. Both must move or the archive is self-inconsistent.
patched = 0
i = 0
while True:
    i = raw.find(old_bytes, i)
    if i == -1:
        break
    raw[i:i + 4] = new_bytes
    patched += 1
    i += 4
assert patched == 2, "expected exactly the local and central CRC copies, found %d" % patched

swapped = os.path.join(OUT, "swapped.epub")
open(swapped, "wb").write(bytes(raw))

a, b = open(original, "rb").read(), open(swapped, "rb").read()
assert len(a) == len(b), "the swap must not change the file's length"
differing = [i for i in range(len(a)) if a[i] != b[i]]
for path in (original, swapped):
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None, "%s does not verify" % path
        assert b"OMEGA" in z.read("OEBPS/chapter1.xhtml") if path == swapped else True

print("original sha256", hashlib.sha256(a).hexdigest())
print("swapped  sha256", hashlib.sha256(b).hexdigest())
print("length   ", len(a), "==", len(b))
print("differing byte offsets:", differing)
print("old crc %08x -> new crc %08x" % (old_crc, new_crc))
print("words:", len(WORDS))
