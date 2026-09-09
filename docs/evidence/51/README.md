# Chapter control on a device (#51)

Everything here was captured on **`Phone_Mid_API36`** (the reference AVD: 1080p,
Android 16, 420 dpi), locale `en-US`, in one session on 2026-09-09, from a
`./gradlew installDebug` of **`9c75c02c518f90906519cd39c5d60bff4f35a2d5`** — the
parent of the commit that adds these files, since a commit that only adds
pictures cannot have produced them.

The book is `the-long-approach.epub`, a five-item spine — cover, title page,
copyright, Chapter One, Chapter Two — built for this run and pushed to
`/sdcard/Download/leaf51/`, then added through the app's own **Add books**
picker so it arrives over a real Storage Access Framework URI. It declares
**neither** an EPUB 3 `landmarks` `bodymatter` entry **nor** an EPUB 2
`<guide><reference type="text">`, so this run exercises the weakest of the three
detection paths: the chapter-title heuristic alone.

## REQ-202 — the offer, once

First open of the book. The reader is on the cover, chapter 1 of 5:

![The reader on "Cover", 1 of 5, with a banner above the stream reading "This book starts with cover and title pages." and two buttons: "Skip to Chapter One: The Approach" and "Start at the beginning"](offer-on-first-open-phone-mid-api36.png)

Taking the skip. The word on screen is "Chapter" — the first word of Chapter
One — and the chapter readout says 4 of 5:

![The same reader showing the word "Chapter", the chapter readout "Chapter One: The Approach, 4 of 5", 24% read, and no banner](offer-taken-lands-on-chapter-one-phone-mid-api36.png)

Then the position was scrubbed back to the book's first word, the process was
killed with `am force-stop`, and the app was relaunched. It resumes into the
book **on the cover, 1 of 5 — and makes no offer**:

![The reader resumed on "Cover", 1 of 5, 1% read, with no banner anywhere on the screen](offer-not-repeated-after-process-death-phone-mid-api36.png)

Nothing in memory survived the force-stop, so the only thing that can be
suppressing the offer at token zero is the stored record. It is in the catalog
below.

## REQ-201 — the chapter pause, both ways

**On (the default, v1's behaviour).** Playing from the cover stops the stream on
the first word of the title page and names it:

![The reader held on a titled screen: "2 of 5", "Title Page", "Play to read on.", with the play button showing play rather than pause](chapter-pause-on-holds-phone-mid-api36.png)

**Turned off** in Settings → Chapters. Note that "Reset to defaults" has become
enabled, so the change is a real departure from the shipped defaults:

![Settings showing a Chapters section with "Pause at chapters" switched off, its summary, and the "Reset to defaults" button now enabled](settings-chapter-pause-off-phone-mid-api36.png)

Playing again from the book's first word. The stream crossed **three** chapter
boundaries — cover to title page, title page to copyright, copyright to Chapter
One — without stopping at any of them, and is still running inside Chapter One
at 63%:

![The reader streaming the word "to" inside "Chapter One: The Approach, 4 of 5", 63% read, with the transport button showing pause because the stream is playing](chapter-pause-off-crosses-phone-mid-api36.png)

## What was stored

[catalog-schema-5-phone-mid-api36.txt](catalog-schema-5-phone-mid-api36.txt) —
the catalog the debug build wrote, read back with `run-as`. Schema version 5,
`chapterPauseEnabled: false` (the setting turned off above), and the book's id
in `frontMatterOfferedBookIds`.

`adb logcat -d -s AndroidRuntime:E` was empty for the whole session.
