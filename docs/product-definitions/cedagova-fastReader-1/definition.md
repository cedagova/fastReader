# Product Definition: RSVP fast reader for EPUB books

- Product definition issue: https://github.com/cedagova/fastReader/issues/1
- Product definition PR: https://github.com/cedagova/fastReader/pull/2
- Requirements brief: Pending
- Status: Draft ready
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
guide marks — following published research) aid recognition; all cues can be
toggled and customized to a reasonable degree. Speed and other helpful
controls are provided, and all controls can be hidden for a fully focused
reading experience. Books are supplied by the user; a simple library lets
the user pick books from their device or add a folder, with simple search.
The priority is the reading experience, not a robust library.

## User or operator need

The owner (a solo reader) wants to get through books materially faster than
conventional page reading, without fighting reader chrome. Existing reading
apps either lack RSVP entirely or bury it behind subscriptions, poor EPUB
handling, or cluttered UIs. The need: open one of my own EPUBs, press play,
and read at a controlled high speed with minimal friction.

## Actors and context

- **Reader (only actor):** the device owner — or a friend who sideloaded the
  released APK. No accounts, no roles, no multi-user concerns.
- **Context:** personal Android phone or tablet (minSdk 26+), typically held
  in one hand; sessions from a few minutes to an hour; light and dark
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
4. Visual aids and timing follow published research (pinned in
   [research-rsvp.md](research-rsvp.md)); every aid can be turned off or
   adjusted within a bounded set.
5. The whole UI can recede: a focused mode showing nothing but the word
   stream (and whatever cues the reader left enabled).
6. A versioned, signed APK on GitHub Releases installs on any compatible
   Android device — shareable with friends by link.

## Product behavior and flows

### First launch (empty library)

Empty state explains the two ways to add books: pick individual EPUB files,
or add a folder whose EPUBs (including subfolders) appear in the library.
Both use the system document picker; no broad storage permission.

### Library

- Books are read in place — never copied into app storage (owner decision
  2026-09-01). Removing a book from the library never deletes the file.
- Added folders are rescanned on each app open, plus an explicit manual
  refresh (owner decision 2026-09-01).
- List of books with title, author, and cover when the EPUB provides one; a
  placeholder cover otherwise.
- Simple search filters by title, author, and filename as the reader types.
- Each book shows reading progress (% read). Tapping a book opens the reader
  at the saved position.

### Reading (core flow)

- Opening a book lands on a paused reader showing the surrounding paragraph
  with the current word highlighted, so the reader can re-orient before
  playing.
- Play starts the word stream, one word at a time (no multi-word chunks in
  v1 — owner decision 2026-09-01), ramping from ~80% of the target speed to
  the target over roughly 15–30 seconds.
- Speed is adjustable during reading without stopping the stream. Default
  250 WPM, range 100–1000; a gentle non-blocking hint marks the zone above
  ~450 WPM where research shows comprehension degrades (owner decision
  2026-09-01).
- Per-word display time is modulated per the pinned research defaults:
  sentence ends ~3.0×, clause punctuation ~2.0×, paragraph breaks ~3.5×,
  in-chapter headings a long break (≥4×), long words / numbers / rare words
  ~1.5×, abbreviations exempt. A single bounded "pause strength" setting
  (off / subtle / normal / strong) scales the extra pauses rather than
  exposing each multiplier.
- Tap anywhere pauses and shows the surrounding paragraph with the current
  word highlighted — the practical substitute for re-reading (regressions)
  that RSVP removes.
- Navigation while paused: back/forward by sentence and by paragraph; a
  chapter picker from the book's table of contents; scrub by progress.
- Resuming after any pause, rewind, or jump re-orients with a brief
  re-orientation hold (~3× on the first word) before normal pacing.
- **Chapter boundaries:** the stream auto-pauses at each chapter boundary
  and shows the chapter title; play continues into the chapter (owner
  decision 2026-09-01).
- **Non-streamable content:** images and tables are skipped with a brief
  unobtrusive marker (e.g. "[image skipped]") in the stream; footnote
  markers are dropped (owner decision 2026-09-01).
- Position, speed, and settings persist continuously across pause, app
  switch, process death, and device restart.
- Progress while reading: percent and estimated time remaining at the
  current speed (visible unless focused mode hides it).

### Visual cues

- Default presentation: the word aligned on its pivot letter (recognition
  point slightly left of center) with the pivot letter in an accent color —
  on by default, individually toggleable.
- Optional minimal guide marks indicating the pivot position. Their visual
  design must be distinct from Spritz's patented "redicle" trade dress.
- Bounded customization set: pivot color (small palette), font size,
  light/dark/system theme, guide marks on/off, pause strength. Changes take
  effect immediately with a live preview. No free-form theme engine.

### Focused mode

- One gesture hides all controls and chrome, leaving only the word stream
  and enabled cues; one gesture brings controls back. Tap-to-pause still
  works in focused mode.

### Completion

- Reaching the end of the book shows an explicit end state (book finished,
  100% in library) and offers return to library.

## States and failure behavior

- **Empty library:** guidance state described above.
- **Loading:** opening a large EPUB shows progress; the app stays
  responsive.
- **Invalid/corrupt EPUB:** the book is marked unreadable in the library
  with a plain-language reason; the app never crashes on malformed input.
- **DRM-protected EPUB:** detected and rejected with a clear message; DRM
  is out of scope.
- **Missing file (folder moved/deleted):** the library entry shows the book
  as missing and offers removal; reading position is retained in case the
  file returns.
- **Permission loss (revoked folder access):** the affected books show as
  inaccessible with a re-grant path.
- **Process death mid-reading:** reopening resumes at the last position
  with no visible data loss.

## Requirements and acceptance

### Library

- **R-LIB-1** Add individual EPUBs via the system picker.
  *Accept:* pick three EPUBs → all three appear with title/author, cover or
  placeholder.
- **R-LIB-2** Add a folder; its EPUBs (recursive) appear; rescan on app
  open and on manual refresh.
  *Accept:* copy a new EPUB into an added folder, reopen the app → it
  appears without further action.
- **R-LIB-3** Live search over title, author, filename.
  *Accept:* typing a partial author name filters the list as typed.
- **R-LIB-4** Per-book progress; removal never deletes the file.
  *Accept:* remove a book → file still present on device; re-adding
  restores its position.
- **R-LIB-5** Corrupt, DRM, missing, and permission-lost books each show a
  distinct plain-language state; missing books keep their position.
  *Accept:* rename a book's folder → entry shows "missing", position
  retained after restoring the folder.

### Reading

- **R-READ-1** Opening a book shows a paused view: surrounding paragraph,
  current word highlighted.
  *Accept:* open any in-progress book → same sentence visible as when last
  paused.
- **R-READ-2** Play streams single words at the configured speed with the
  research-default modulation; pause strength setting scales extra pauses.
  *Accept:* at 250 WPM a sentence end visibly holds ~3× a normal word;
  setting pause strength "off" makes all words uniform.
- **R-READ-3** Speed adjustable during playback; default 250, range
  100–1000; non-blocking hint above ~450.
  *Accept:* raising speed mid-play changes pacing without a stop; at 500
  WPM the hint is visible and playback continues.
- **R-READ-4** Ramp-up on by default; re-orientation hold after any
  resume, rewind, or jump.
  *Accept:* pressing play starts noticeably slower and reaches target
  within ~30 s.
- **R-READ-5** Tap pauses with context; while paused: back/forward by
  sentence and paragraph, chapter picker, progress scrub.
  *Accept:* tap during play → paragraph context with current word
  highlighted; "back one sentence" then play resumes from that sentence.
- **R-READ-6** Chapter auto-pause with title; image/table skip markers;
  footnote markers dropped.
  *Accept:* reading across a chapter end pauses on a chapter-title screen;
  a book with an inline image streams "[image skipped]" briefly.
- **R-READ-7** Position, speed, and settings persist continuously.
  *Accept:* force-stop the app mid-sentence → reopening resumes at the
  same position with the same speed.
- **R-READ-8** Progress percent and time remaining at current speed while
  reading.
  *Accept:* doubling the speed roughly halves the displayed time
  remaining.
- **R-READ-9** Explicit end-of-book state; library shows 100%.
- **R-READ-10** Spanish and English text renders and times correctly:
  accents, ¿¡, dialogue dashes treated as clause punctuation.
  *Accept:* a Spanish novel streams with no mojibake; ¿…? sentences get
  sentence-end pauses.

### Cues and customization

- **R-CUE-1** Pivot-aligned word with colored pivot letter, on by default,
  toggleable; pivot color from a small palette.
  *Accept:* disabling the cue shows plain centered words; color change
  applies immediately.
- **R-CUE-2** Optional guide marks, visually distinct from Spritz's
  patented redicle.
- **R-CUE-3** Font size and light/dark/system theme.
- **R-CUE-4** All cue/timing settings show a live preview and a
  reset-to-defaults.

### Controls and focused mode

- **R-CTL-1** One gesture hides all chrome (focused mode); one gesture
  restores it; tap-to-pause still works.
  *Accept:* in focused mode nothing but the word (and enabled cues) is on
  screen; a tap pauses with context.

### Release

- **R-REL-1** Versioned, signed APK attached to a GitHub Release; installs
  on minSdk 26+; in-place updates preserve library, positions, settings.
  *Accept:* installing version N+1 over N keeps every book and position
  without uninstalling.

### Privacy

- **R-PRIV-1** Everything on device; no telemetry, no network transmission
  of reading data; file access only via user-granted picks.

### Accessibility

- **R-A11Y-1** Library, reader controls, and settings are screen-reader
  (TalkBack) navigable with meaningful labels; touch targets meet Android
  minimum sizes; UI text respects the system font scale.
  *Accept:* with TalkBack on, a book can be found, opened, and playback
  started/paused; every control announces a sensible label.
- **R-A11Y-2** The RSVP word stream itself is visual-only by design; this
  limitation is stated plainly in-app (e.g. in settings/about), not hidden.

## Accessibility and content

- Book content renders correctly for Spanish and English text (owner
  decision 2026-09-01). App UI is English-only for now.
- Font size and themes adjustable within the bounded customization set.
- In-product copy never promises comprehension at high speeds; the speed
  hint reflects the pinned research honestly.
- Assistive-technology bar: basic navigable UI (R-A11Y-1/2, owner decision
  2026-09-01) — library/controls/settings TalkBack-navigable; the word
  stream is documented as visual-only.

## Privacy, security, and policy

- All data stays on device: books, positions, settings. No accounts, no
  telemetry, no network transmission of reading data.
- File access only through user-granted document/folder picks.
- DRM circumvention is explicitly out of scope; DRM-protected books are
  rejected, never unlocked.
- Guide-mark visual design avoids Spritz's patented trade dress (US
  8,903,174).

## Success measures and guardrails

- The owner reads real books start-to-finish in the app at a speed above
  their normal reading rate with satisfactory comprehension.
- Time from app open to words streaming (existing book): under 3 seconds
  on the reference device.
- Guardrail: presentation timing stays smooth (no visible stutter) at max
  configured speed on the reference device.
- Guardrail: no book content, position, or setting is ever silently lost.
- Guardrail: in-product copy stays honest about speed/comprehension per
  the pinned research.

## Constraints and non-goals

- **Formats:** EPUB only (DRM-free). No PDF, MOBI, TXT.
- **Non-goals:** Play Store publishing; cloud sync/backup; annotations,
  highlights, bookmarks beyond the single reading position; a conventional
  page-reading mode; TTS; reading statistics dashboards; robust library
  management (collections, tags, metadata editing); multi-language UI;
  multi-word chunking (possible later objective, phrase-boundary only per
  research).
- **Platform:** Android only, minSdk 26.
- Distribution: signed APKs attached to GitHub Releases (owner decision
  2026-09-01).

## Evidence

- Pinned repository baseline `90bfa63`: Kotlin 2.1.21 + Compose scaffold
  with Roborazzi screenshot tests exists; no product features yet. The
  stack is chosen and installed; definition builds on that as context, not
  as a product requirement.
- [research-rsvp.md](research-rsvp.md) (2026-09-01): published-research
  review (Rayner/Schotter 2016, Schotter 2014, Benedetto 2015, Acklin &
  Papesh 2017, Masson 1983, Potter 1980), app-convention timing constants
  (Squirt, OpenSpritz, Spritz, Reedy), expected feature set, EPUB-on-
  Android pitfalls, and the Spritz patent caution. Timing defaults and cue
  choices in this definition trace to that report.

## Assumptions

- The owner's EPUBs are DRM-free and predominantly Spanish or English.
- Single device at a time; no sync between devices is expected.
- Friends receiving the shared APK accept sideloading (unknown-sources
  install) as the distribution model.

## Owner decisions

| Date | Decision | Rationale | Affects |
| --- | --- | --- | --- |
| 2026-09-01 | Distribution: shareable signed APK via GitHub Releases; Play Store out of scope. | Friends can sideload by link without store ceremony. | Constraints, R-REL-1 |
| 2026-09-01 | Languages: Spanish + English books handled correctly; app UI English-only. | Owner's library is Spanish/English. | R-READ-10, content |
| 2026-09-01 | Chapter boundaries auto-pause showing chapter title; images/tables skipped with a marker; footnote markers dropped. | Keeps speed focus while never hiding that content existed. | R-READ-6 |
| 2026-09-01 | Adopt evidence-backed timing package: 250 WPM default, 100–1000 range, hint above ~450, ramp-up on, research pause multipliers. | Matches published comprehension thresholds and mature-app convention. | R-READ-2/3/4 |
| 2026-09-01 | Single-word RSVP only in v1; chunking is a non-goal (phrase-boundary only if ever added). | Blind chunks hurt comprehension; phrase detection is significant added scope. | Non-goals |
| 2026-09-01 | Library reads files in place; folders rescan on app open + manual refresh; removal never deletes files. | No duplicate storage; new books appear automatically. | R-LIB-2/4/5 |
| 2026-09-01 | Accessibility: basic bar — TalkBack-navigable library/controls/settings, minimum touch targets, system font scale; word stream documented visual-only. | Proportionate for a sideloaded shareable app; friends with assistive tech can still operate the app. | R-A11Y-1/2, R-REL-1 |

## Remaining uncertainty

- Exact visual design of guide marks (must differ from the patented Spritz
  redicle) — a design-time choice inside R-CUE-2, not a product decision.
- Exact gesture assignments (hide chrome, sentence rewind) — interaction
  design within R-CTL-1/R-READ-5; any single-gesture mapping satisfies the
  contract.

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
