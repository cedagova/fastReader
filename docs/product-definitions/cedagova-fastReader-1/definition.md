# Product Definition: RSVP fast reader for EPUB books

- Product definition issue: https://github.com/cedagova/fastReader/issues/1
- Product definition PR: https://github.com/cedagova/fastReader/pull/2
- Requirements brief: Pending
- Status: Needs input
- Classification: DECOMPOSE
- Definition lead: cedagova
- Started: 2026-09-01

## Pinned evidence baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `90bfa63c79389fec9db9dd7b5625d920c098505c` |

## Objective

An Android app for reading a book fast by showing one word at a time for a
short interval (RSVP — Rapid Serial Visual Presentation). The app takes an
EPUB book (only EPUB for now); when the user presses play, the book is
presented word after word automatically. Visual cues (colored pivot letter,
vertical guide, or whatever published research supports) aid recognition; all
cues can be toggled and customized to a reasonable degree. Speed and other
helpful controls are provided, and all controls can be hidden for a fully
focused reading experience. Books are supplied by the user; a simple library
lets the user pick books from their device or add a folder, with simple
search. The priority is the reading experience, not a robust library.

## User or operator need

The owner (a solo reader) wants to get through books materially faster than
conventional page reading, without fighting reader chrome. Existing reading
apps either lack RSVP entirely or bury it behind subscriptions, poor EPUB
handling, or cluttered UIs. The need: open one of my own EPUBs, press play,
and read at a controlled high speed with minimal friction.

## Actors and context

- **Reader (only actor):** the device owner. No accounts, no roles, no
  multi-user concerns.
- **Context:** personal Android phone or tablet (minSdk 26+), typically
  held in one hand; sessions from a few minutes to an hour; light and dark
  environments.
- **Triggers:** the reader opens the app to continue the current book, or
  adds a new EPUB obtained elsewhere (download folder, synced folder).
- **Permissions:** read access to user-selected documents/folders only.
  No network use is required for reading.

## Desired outcomes

1. From app launch to words streaming in a previously opened book: at most
   two interactions (open app → tap play, book resumes where it left off).
2. A new DRM-free EPUB on the device can be found, opened, and read without
   leaving the app.
3. Reading speed is adjustable while reading; the presentation is smooth and
   uninterrupted at the configured speed.
4. Visual aids measurably reduce effort at speed (per published research);
   every aid can be turned off or adjusted.
5. The whole UI can recede: a focused mode showing nothing but the word
   stream (and whatever cues the reader left enabled).
6. A versioned, signed APK on GitHub Releases installs on any compatible
   Android device — shareable with friends by link.

## Product behavior and flows

### First launch (empty library)

Empty state explains the two ways to add books: pick individual EPUB files,
or add a folder whose EPUBs appear in the library. Both use the system
document picker; no broad storage permission.

### Library

- Grid or list of books with title, author, and cover when the EPUB provides
  one; a placeholder cover otherwise.
- Simple search filters by title, author, and filename as the reader types.
- Added folders are rescanned for new/removed EPUBs (PRODUCT-TODO: confirm
  rescan trigger — on app open vs manual refresh).
- Each book shows reading progress (% read). Tapping a book opens the reader
  at the saved position.
- Removing a book from the library never deletes the underlying file.

### Reading (core flow)

- Opening a book lands on a paused reader showing the current position with
  surrounding context (PRODUCT-TODO: exact paused presentation — sentence or
  paragraph context, pending research report).
- Play starts the word stream at the configured speed. Pause freezes it and
  restores context so the reader can re-orient.
- Speed is adjustable during reading without stopping the stream.
- Per-word display time is modulated: longer words, punctuation, clause and
  sentence ends, and paragraph breaks earn extra time (PRODUCT-TODO: exact
  heuristics and defaults pending research report).
- Navigation while paused: back/forward by sentence and paragraph; chapter
  picker from the book's table of contents; scrub by progress.
- After any pause or rewind, resuming re-orients the reader (PRODUCT-TODO:
  e.g. short ramp-up or sentence restart, pending research).
- **Chapter boundaries:** the stream auto-pauses at each chapter boundary and
  shows the chapter title; play continues into the chapter (owner decision
  2026-09-01).
- **Non-streamable content:** images and tables are skipped with a brief
  unobtrusive marker (e.g. "[image skipped]") in the stream; footnote markers
  are dropped (owner decision 2026-09-01).
- Position, speed, and settings persist across pause, app switch, process
  death, and device restart.

### Visual cues

- Cues follow published research; the definitive set and defaults are
  PRODUCT-TODO pending the research report. Candidate set: highlighted pivot
  letter with word alignment, vertical/reticle guide marks.
- Every cue is individually toggleable; customization (e.g. cue color,
  font size, theme) is bounded — a small set of options, not a theme engine.

### Focused mode

- One gesture hides all controls and chrome, leaving only the word stream and
  enabled cues; one gesture brings controls back. Pause remains reachable by
  a single tap.

### Completion

- Reaching the end of the book shows an explicit end state (book finished,
  % 100 in library) and offers return to library.

## States and failure behavior

- **Empty library:** guidance state described above.
- **Loading:** opening a large EPUB shows progress; the app stays responsive.
- **Invalid/corrupt EPUB:** the book is marked unreadable in the library with
  a plain-language reason; the app never crashes on malformed input.
- **DRM-protected EPUB:** detected and rejected with a clear message; DRM is
  out of scope.
- **Missing file (folder moved/deleted):** the library entry shows the book as
  missing and offers removal; reading position is retained in case the file
  returns.
- **Permission loss (revoked folder access):** the affected books show as
  inaccessible with a re-grant path.
- **Process death mid-reading:** reopening resumes at the last position with
  no visible data loss.

## Requirements and acceptance

PRODUCT-TODO: Stable requirement IDs (R-LIB-*, R-READ-*, R-CUE-*, R-CTL-*,
R-SET-*, R-REL-*) with observable acceptance examples, to be completed after
the research report and remaining owner decisions.

## Accessibility and content

- Book content renders correctly for Spanish and English text: accented
  characters, ¿¡, dialogue dashes, and Spanish punctuation timing (owner
  decision 2026-09-01). App UI is English-only for now.
- Font size and light/dark themes are adjustable within the bounded
  customization set.
- PRODUCT-TODO: minimum accessibility bar for a shareable app (e.g. TalkBack
  on library and controls; RSVP streaming itself is inherently visual).

## Privacy, security, and policy

- All data stays on device: books, positions, settings. No accounts, no
  telemetry, no network transmission of reading data.
- File access only through user-granted document/folder picks.
- DRM circumvention is explicitly out of scope; DRM-protected books are
  rejected, never unlocked.

## Success measures and guardrails

- The owner reads real books start-to-finish in the app at a speed above
  their normal reading rate with satisfactory comprehension.
- Time from app open to words streaming (existing book): under 3 seconds on
  the reference device.
- Guardrail: presentation timing stays smooth (no visible stutter) at max
  configured speed on the reference device.
- Guardrail: no book content, position, or setting is ever silently lost.

## Constraints and non-goals

- **Formats:** EPUB only (DRM-free). No PDF, MOBI, TXT.
- **Non-goals:** Play Store publishing; cloud sync/backup; annotations,
  highlights, bookmarks beyond the single reading position; a conventional
  page-reading mode; TTS; reading statistics dashboards; robust library
  management (collections, tags, metadata editing); multi-language UI.
- **Platform:** Android only, minSdk 26.
- Distribution: signed APKs attached to GitHub Releases (owner decision
  2026-09-01).

## Evidence

- Pinned repository baseline `90bfa63`: Kotlin 2.1.21 + Compose scaffold with
  Roborazzi screenshot tests exists; no product features yet. The stack is
  chosen and installed; definition builds on that as context, not as a
  product requirement.
- PRODUCT-TODO: research report on RSVP visual cues, comprehension, and
  timing heuristics (in progress) — will be pinned here with sources.

## Assumptions

- The owner's EPUBs are DRM-free and predominantly Spanish or English.
- Single device at a time; no sync between devices is expected.
- Book files remain where the user put them; the app reads in place rather
  than importing copies (PRODUCT-TODO: confirm read-in-place vs import copy
  as a product-visible behavior — affects the missing-file state).

## Owner decisions

| Date | Decision | Rationale | Affects |
| --- | --- | --- | --- |
| 2026-09-01 | Distribution: shareable signed APK via GitHub Releases; Play Store out of scope. | Friends can sideload by link without store ceremony. | Constraints, release outcome |
| 2026-09-01 | Languages: Spanish + English books handled correctly; app UI English-only. | Owner's library is Spanish/English. | Accessibility and content |
| 2026-09-01 | Chapter boundaries auto-pause showing chapter title; images/tables skipped with a marker; footnote markers dropped. | Keeps speed focus while never hiding that content existed. | Reading flow |

## Remaining uncertainty

- Visual cue set, defaults, and timing heuristics: pending research report.
- Paused-context presentation and resume re-orientation behavior.
- Folder rescan trigger; read-in-place vs import copy.
- Accessibility bar for the shareable release.

## Product issue graph

| Key | Kind | Parent | Title | Issue |
| --- | --- | --- | --- | --- |
| ROOT | ROOT | None | RSVP fast reader for EPUB books | https://github.com/cedagova/fastReader/issues/1 |
| O1 | OUTCOME | ROOT | Library: pick, add-folder, search, progress | Pending |
| O2 | OUTCOME | ROOT | Core RSVP reading experience | Pending |
| O3 | OUTCOME | ROOT | Cue customization and focused mode | Pending |
| O4 | OUTCOME | ROOT | Shareable signed release on GitHub Releases | Pending |

## Publication verification

PRODUCT-TODO: Record brief publication, graph verification, exact-head review, and next action.
