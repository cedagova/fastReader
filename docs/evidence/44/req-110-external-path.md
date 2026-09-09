# REQ-110 on the "Open with" path

#43 wrote the protocol for "opening a book takes time proportional to its text,
not to its file size" and measured it from the library. This is the same
interval measured from the entry point #44 owns, because the external path adds
work the library path does not have: accepting the intent, querying the provider
for a display name, and looking the URI up in the catalog.

[`measure-external-open-time.py`](measure-external-open-time.py) differs from
[#43's script](../43/measure-open-time.py) only in where the tap happens.

- **t0** — the tap on the book's row in the Files app.
- **t1** — the first screen that is still and different from the Files listing.
  Still means three consecutive identical screen hashes; t1 is when the first of
  them returned.

Both endpoints are on the host, so nothing depends on the build logging
anything. The measurement floor is one screen capture, about 130 ms.

## Books

The acceptance pair from
[#43's protocol](../43/req-110-measurement-protocol.md#preparing-book-b): one
illustrated EPUB and a copy of it with every image entry deleted and nothing
else changed.

- **(a)** `plates-and-pages-illustrated.epub` — 52.47 MB, 40 stored plates,
  12 chapters of text.
- **(b)** `plates-and-pages-stripped.epub` — 38.65 kB, the same 12 chapters.

Both were opened once as a warm-up, then measured with `--cold` (the guest page
cache emptied before every run, via `adb root`).

## Result

`Phone_Mid_API36`, debug build, 7 cold runs each, seconds:

| Book | median | min | max |
| --- | --- | --- | --- |
| (a) illustrated, 52.47 MB | **1.586** | 1.546 | 1.966 |
| (b) stripped, 38.65 kB | **1.603** | 1.473 | 1.621 |

The 52 MB book opens **1.1 % faster** than its 38 kB twin — that is, the two are
indistinguishable, against an acceptance band of 25 %. Per-run numbers are in
[`runs/`](runs).

The interval is about ten times #43's in-library number because it contains a
cold process start and an activity transition from another app. That constant is
in both columns and is not what is being compared.

## What this does and does not prove

It shows the image payload costs nothing on this path, on this device. It is
**not** a sensitive test, for exactly the reason #43 recorded: the emulator's
storage is host RAM on an Apple Silicon host, so a 52 MB read is nearly free
there and `drop_caches` does not change that. #43 found that even the published
v1.0.1 — which does hash the whole file before first text — shows no difference
between the same two books on this AVD.

The mechanism is what carries the requirement, and it is proven exactly rather
than statistically:

- `ExternalOpenControllerTest."accepting a hand-over reads none of the book"` —
  `accept()` is given a byte source that throws on any read, and the hand-over
  still completes. The grant, the ingest and the digest are all in
  `resolveIdentity`, which the reader only calls once the stream is running.
- `ReaderOpenCostTest` and `ContentPipelineDeviceTest.imageEntriesAreNeverReadOnDevice`
  (both from #43) — the parse never touches an image entry's bytes.

## Not the owner's book

REQ-110's acceptance names *the owner's largest owned illustrated EPUB, at least
50 MB*. That file is a supplied test input and is not in this repository, so the
pair above is a synthetic substitute — the same substitution, and the same gap,
that #43 recorded. Closing it needs the owner's own book, and ideally a physical
device whose storage is not host RAM.
