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
3. Reading speed is adjustable while reading; the presentation is smooth
   at the configured speed, apart from the deliberate pauses defined below.
4. Visual aids and timing follow published research (pinned in
   [research-rsvp.md](research-rsvp.md)); every aid can be turned off or
   adjusted within a bounded set.
5. The whole UI can recede: a focused mode showing nothing but the word
   stream (and whatever cues the reader left enabled).
6. A versioned, installable release of the app exists that friends can
   obtain by link and install on any compatible Android device
   (distribution via GitHub Releases per owner decision).

## Product behavior and flows

### First launch (empty library)

Empty state explains the two ways to add books: pick individual EPUB files,
or add a folder whose EPUBs (including subfolders) appear in the library.
Both use the system document picker; no broad storage permission.

### App launch

- When a last-read, currently readable book exists, the app launches
  directly into its paused reader view — satisfying "open app → tap play".
  One control returns to the library.
- Otherwise the app launches into the library: on first launch / empty
  library the guidance state; if the last-read book is missing or its
  folder permission was lost, the library opens with that book's state
  visible so the reader understands why reading did not resume.

### Library

- Books are read in place — never copied into app storage (owner decision
  2026-09-01). Removing a book from the library never deletes the file.
- Added folders are rescanned on each app open, plus an explicit manual
  refresh (owner decision 2026-09-01).
- List of books with title, author, and cover when the EPUB provides one; a
  placeholder cover otherwise.
- Simple search filters by title, author, and filename as the reader types.
- A book reachable both by direct pick and through an added folder appears
  once in the library.
- Each book shows reading progress (% read, measured over the book's text).
  Tapping a book opens the reader at the saved position.

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
- **Front and back matter** (title page, copyright, dedication, etc.)
  stream like any other chapter in book order; the chapter picker makes
  skipping them a single action.
- While the stream is playing, the screen stays awake — playback is
  exempt from the device screen timeout.
- Losing the foreground (app switch, incoming call, device lock)
  auto-pauses at the current word; the stream never advances off-screen.
  Returning shows the normal paused context view, and resuming applies the
  usual re-orientation hold.
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
- A word that would overflow the stream area at the chosen font size (long
  word, large system font scale) shrinks to fit rather than truncating.

### Focused mode

- One gesture hides all controls and chrome — including progress and the
  speed hint — leaving only the word stream and enabled cues; one gesture
  brings controls back. Tap-to-pause still works in focused mode.

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

- **REQ-001** Add individual EPUBs via the system picker.
  *Accept:* pick three EPUBs → all three appear with title/author, cover or
  placeholder.
- **REQ-002** Add a folder; its EPUBs (recursive) appear; rescan on app
  open and on manual refresh.
  *Accept:* copy a new EPUB into an added folder, reopen the app → it
  appears without further action.
- **REQ-003** Live search over title, author, filename.
  *Accept:* typing a partial author name filters the list as typed.
- **REQ-004** Per-book progress; removal never deletes the file.
  *Accept:* remove a book → file still present on device; re-adding
  restores its position.
- **REQ-005** Corrupt, DRM, missing, and permission-lost books each show a
  distinct plain-language state; missing books keep their position.
  *Accept:* rename a book's folder → entry shows "missing", position
  retained after restoring the folder.

### Reading

- **REQ-009** App launch resumes into the paused reader of the last-read
  book when one is readable; otherwise it opens the library with the
  reason visible (empty, missing, or permission-lost state).
  *Accept:* with a book in progress, launch → its paused view in one step;
  after revoking the folder permission, launch → library showing that book
  as inaccessible.
- **REQ-010** Opening a book shows a paused view: surrounding paragraph,
  current word highlighted.
  *Accept:* open any in-progress book → same sentence visible as when last
  paused.
- **REQ-011** Play streams single words at the configured speed with the
  research-default modulation; pause strength setting scales extra pauses.
  *Accept:* at 250 WPM a sentence end visibly holds ~3× a normal word;
  setting pause strength "off" makes all words uniform.
- **REQ-012** Speed adjustable during playback; default 250, range
  100–1000; non-blocking hint above ~450.
  *Accept:* raising speed mid-play changes pacing without a stop; at 500
  WPM the hint is visible and playback continues.
- **REQ-013** Ramp-up on by default; re-orientation hold after any
  resume, rewind, or jump.
  *Accept:* pressing play starts noticeably slower and reaches target
  within ~30 s.
- **REQ-014** Tap pauses with context; while paused: back/forward by
  sentence and paragraph, chapter picker, progress scrub.
  *Accept:* tap during play → paragraph context with current word
  highlighted; "back one sentence" then play resumes from that sentence.
- **REQ-015** Chapter auto-pause with title; image/table skip markers;
  footnote markers dropped.
  *Accept:* reading across a chapter end pauses on a chapter-title screen;
  a book with an inline image streams "[image skipped]" briefly.
- **REQ-016** Position, speed, and settings persist continuously.
  *Accept:* force-stop the app mid-sentence → reopening resumes at the
  same position with the same speed.
- **REQ-017** Progress percent and time remaining at current speed while
  reading.
  *Accept:* doubling the speed roughly halves the displayed time
  remaining.
- **REQ-018** Explicit end-of-book state; library shows 100%.
- **REQ-019** Spanish and English text renders and times correctly:
  accents, ¿¡, dialogue dashes treated as clause punctuation.
  *Accept:* a Spanish novel streams with no mojibake; ¿…? sentences get
  sentence-end pauses.

### Cues and customization

- **REQ-020** Pivot-aligned word with colored pivot letter, on by default,
  toggleable; pivot color from a small palette.
  *Accept:* disabling the cue shows plain centered words; color change
  applies immediately.
- **REQ-021** Optional guide marks, visually distinct from Spritz's
  patented redicle.
- **REQ-022** Font size and light/dark/system theme.
- **REQ-023** All cue/timing settings show a live preview and a
  reset-to-defaults.

### Controls and focused mode

- **REQ-030** One gesture hides all chrome (focused mode); one gesture
  restores it; tap-to-pause still works.
  *Accept:* in focused mode nothing but the word (and enabled cues) is on
  screen; a tap pauses with context.

### Release

- **REQ-040** A versioned, installable release obtainable by link installs
  on Android 8.0+ (minSdk 26); installing a newer version in place
  preserves library, positions, and settings. (Distribution channel —
  signed APK on GitHub Releases — is recorded as an owner decision and
  constraint, not a product requirement.)
  *Accept:* installing version N+1 over N keeps every book and position
  without uninstalling.

### Playback resilience

- **REQ-070** The screen stays awake while the stream is playing.
  *Accept:* play for longer than the device screen timeout without
  touching the screen → the stream is still visible and running.
- **REQ-071** Foreground loss auto-pauses at the current word; the stream
  never advances while not visible.
  *Accept:* switch apps mid-stream → returning shows the paused context
  view at the word that was on screen when the switch happened.

### Privacy

- **REQ-050** Everything on device; no telemetry, no network transmission
  of reading data; file access only via user-granted picks.

### Accessibility

- **REQ-060** Library, reader controls, and settings are screen-reader
  (TalkBack) navigable with meaningful labels; touch targets meet Android
  minimum sizes; UI text respects the system font scale.
  *Accept:* with TalkBack on, a book can be found, opened, and playback
  started/paused; every control announces a sensible label.
- **REQ-061** The RSVP word stream itself is visual-only by design; this
  limitation is stated plainly in-app (e.g. in settings/about), not hidden.
- **REQ-062** Photosensitivity position: word transitions are
  instantaneous text swaps on a static background — no luminance flashes,
  fades, or motion animation in the stream at any speed. The display never
  alternates bright/dark at flash-risk rates, keeping the stream outside
  WCAG 2.3.1 flash territory even at 1000 WPM.
  *Accept:* at maximum speed the background and overall luminance are
  static; only glyphs change.

## Accessibility and content

- Book content renders correctly for Spanish and English text (owner
  decision 2026-09-01). App UI is English-only for now.
- Font size and themes adjustable within the bounded customization set.
- In-product copy never promises comprehension at high speeds; the speed
  hint reflects the pinned research honestly.
- Assistive-technology bar: basic navigable UI (REQ-060, REQ-061, owner decision
  2026-09-01) — library/controls/settings TalkBack-navigable; the word
  stream is documented as visual-only.
- Photosensitivity: the stream changes text only — static background, no
  luminance flashing or animation at any speed (REQ-062).

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
| 2026-09-01 | Distribution: shareable signed APK via GitHub Releases; Play Store out of scope. | Friends can sideload by link without store ceremony. | Constraints, REQ-040 |
| 2026-09-01 | Languages: Spanish + English books handled correctly; app UI English-only. | Owner's library is Spanish/English. | REQ-019, content |
| 2026-09-01 | Chapter boundaries auto-pause showing chapter title; images/tables skipped with a marker; footnote markers dropped. | Keeps speed focus while never hiding that content existed. | REQ-015 |
| 2026-09-01 | Adopt evidence-backed timing package: 250 WPM default, 100–1000 range, hint above ~450, ramp-up on, research pause multipliers. | Matches published comprehension thresholds and mature-app convention. | REQ-011, REQ-012, REQ-013 |
| 2026-09-01 | Single-word RSVP only in v1; chunking is a non-goal (phrase-boundary only if ever added). | Blind chunks hurt comprehension; phrase detection is significant added scope. | Non-goals |
| 2026-09-01 | Library reads files in place; folders rescan on app open + manual refresh; removal never deletes files. | No duplicate storage; new books appear automatically. | REQ-002, REQ-004, REQ-005 |
| 2026-09-01 | Accessibility: basic bar — TalkBack-navigable library/controls/settings, minimum touch targets, system font scale; word stream documented visual-only. | Proportionate for a sideloaded shareable app; friends with assistive tech can still operate the app. | REQ-060, REQ-061, REQ-040 |

## Remaining uncertainty

- Exact visual design of guide marks (must differ from the patented Spritz
  redicle) — a design-time choice inside REQ-021, not a product decision.
- Exact gesture assignments (hide chrome, sentence rewind) — interaction
  design within REQ-030/REQ-014; any single-gesture mapping satisfies the
  contract.

## Product issue graph

| Key | Kind | Parent | Title | Requirements | Issue |
| --- | --- | --- | --- | --- | --- |
| ROOT | ROOT | None | RSVP fast reader for EPUB books | All (umbrella); cross-cutting REQ-050 | https://github.com/cedagova/fastReader/issues/1 |
| OUT001 | OUTCOME | ROOT | Library: pick, add-folder, search, progress | REQ-001–005; REQ-060 (library surface); REQ-050 | Pending |
| OUT002 | OUTCOME | ROOT | Core RSVP reading experience | REQ-009–019, REQ-070, REQ-071, REQ-062; REQ-060 (reader controls surface); REQ-050 | Pending |
| OUT003 | OUTCOME | ROOT | Cue customization and focused mode | REQ-020–023, REQ-030, REQ-061; REQ-060 (settings surface); REQ-050 | Pending |
| OUT004 | OUTCOME | ROOT | Shareable installable release | REQ-040; REQ-050 | Pending |

Cross-cutting requirements (REQ-050 privacy; REQ-060 accessibility) are
listed under every outcome whose surface they constrain; each owning
outcome carries the acceptance for its own surface. Every REQ-### above is
owned by at least one outcome.

## Publication verification

- Requirements Brief drafted on branch; publication to the root issue
  happens after content review (`definition publish-brief`).
- Product-outcome issues OUT001–OUT004 are `Pending` publication until the
  contract passes review (DECOMPOSE gate 1).
- Graph verification, exact-head owner approval, and exact-head reviewer
  approval recorded here once complete.
- Next action after readiness: `plan https://github.com/cedagova/fastReader/issues/1`.
