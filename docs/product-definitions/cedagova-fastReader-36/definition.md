# Product Definition: Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth

- Product definition issue: https://github.com/cedagova/fastReader/issues/36
- Product definition PR: https://github.com/cedagova/fastReader/pull/37
- Requirements brief: https://github.com/cedagova/fastReader/issues/36#issuecomment-5555054300
- Status: Ready for planning
- Classification: DECOMPOSE
- Definition lead: cedagova
- Started: 2026-09-05

## Pinned evidence baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `a8caa0964bf892efa6ba5f1a33b47a06309bba6a` |

## Objective

Turn FastReader v1.0.1, today a private sideload used by the owner and a
few friends, into an app that a stranger can install from a link, get
their own books into, and keep using through updates. Deliver it as two
sequential releases: **v1.1.0**, everything a first-time user needs before
and around reading, and **v1.2.0**, depth in the reading experience
itself. The reading engine shipped in v1 (definition PR #2, REQ-001 to
REQ-071) is preserved unchanged except where a requirement below
explicitly amends it. The owner has decided (D1) that this remains a
personal app distributed by link, not a store product.

## User or operator need

The v1 definition served one reader who already knew what the app was and
where their EPUBs lived. A stranger does not. The 2026-09-05 production
audit (#35) found the engine production-grade and the shell missing: the
app has no icon, cannot be chosen from "Open with", cannot forget a folder
once added, does not say which version it is, has no way to learn about
updates, and is public with no license. Each of these turns a first
install into a dead end. The owner wants to share the app beyond friends
without answering support questions that the product should have answered
itself.

## Actors and context

- **New reader:** someone who received a link, has never seen the app, and
  keeps EPUBs wherever their phone put them (Downloads, a browser, an
  email attachment, a synced folder). Android 8.0+ phone or tablet.
- **Returning reader:** the owner or a friend already on v1.0.x, whose
  library, positions and settings must survive the update in place.
- **Owner:** the sole maintainer, who publishes releases and answers
  questions; the product should minimise both.
- **Triggers:** tapping an `.epub` in another app; installing from the
  release link; setting up a new phone; wanting to remove a mistaken
  folder; wanting to know whether an update exists.
- **Permissions:** unchanged from v1. Read access only to documents and
  folders the reader picks or hands to the app. No network.

## Desired outcomes

1. A stranger with the release link is reading one of their own books
   within three taps of installing, without opening the in-app picker.
2. Nothing a reader adds is a trap: folders and books can be removed, and
   a mistaken removal can be undone.
3. The app can identify itself and its data behaviour: the version is
   visible, the update path is discoverable, and the privacy statement
   matches what the installed app actually does.
4. The app is shippable for its purpose: an MIT license, a readable
   repository front page that says how and for whom it is distributed, and
   the owner's recorded decision on the shipped cue set with its residual
   risk stated (#33, D1).
5. After v1.1.0, the reading experience itself gets the depth a daily
   reader asks for: control over chapter pauses, a library ordered by what
   was read last, layouts that use a tablet or a rotated phone, the
   owner's own language in the interface, and a way to report a problem
   without any data leaving the device unasked.

## Product behavior and flows

### v1.1.0: shippable to strangers (OUT005)

**Install and first launch.** The device shows a real FastReader icon in
the launcher, the app switcher and the system splash. The first frame the
app draws already uses the theme the reader chose in Settings (light,
dark, or the device's own when "System" is selected), so a dark device
never flashes white on launch. The empty library offers a bundled sample
text, one short public-domain passage in English and one in Spanish, with
the Spanish one first when the device language is Spanish, so the reader
can press play and see the stream within seconds, before they have found
any EPUB of their own. The sample is not a library book: it is not
written into the reader's storage and does not appear once real books
exist unless the reader asks for it from Settings.

**Getting a book in from anywhere.** Tapping an `.epub` file in Files, a
browser download, an email attachment or a share sheet offers FastReader.
Choosing it opens the book in the reader. When the source grants access
the app can keep, the book is added to the library exactly like a picked
one. When the source grants access for this session only, the book opens
and reads normally but is **not** added to the library; the reader screen
carries one dismissible line saying the book was opened from another app,
that only the reading position will be remembered, and offering "Add to
library", which launches the picker. The reading position is kept by the
book's own identity, so adding it later resumes where the reader stopped.

**Library housekeeping.** The library lists every added folder with its
status and lets the reader remove one. Removal names how many books will
leave the library; it removes only the books that folder alone provided
and never touches files. Removing a book from a row is reversible for a
short time through an undo affordance; the copy says the file and the
reading position are kept.

**Knowing what you have.** Settings shows the installed version and a
"check for updates" action that opens the releases page in the device
browser. The app itself still makes no network request. The privacy copy
states exactly what leaves the device and that nothing the app stores is
included in the device's backup (D3).

**Focused reading.** In focused mode the reader can change speed with one
gesture without leaving focused mode; the new value is shown briefly and
statically, then disappears.

**Publishing.** The repository carries the MIT license and a README with
screenshots, install steps, and a statement that the app is distributed
by link for personal use with a link to #33. The published build keeps
the v1.0.1 cue set, including the opt-in alignment (D1). Installing over
v1.0.1 keeps everything.

### v1.2.0: reading depth (OUT006)

**Chapters.** A "pause at chapters" setting controls whether the stream
stops at each chapter boundary (default on, preserving v1 behaviour, D4).
On first open of a book, the reader is offered a single action to skip
past front matter to the first real chapter.

**Library order.** The library can be ordered by title, recently read, or
recently added; recently read is the default.

**Rescan cost.** Returning to the app after a short absence does not
re-list every folder; the manual refresh remains the explicit path.

**Large screens and rotation.** On tablets and in landscape, the reader
places controls beside the stream rather than below it, and the library
uses the width.

**Language.** The interface is in Spanish when the device is set to
Spanish (D5).

**Reporting a problem.** If the app crashes, the next launch offers to
share a plain-text report through the system share sheet. Nothing is sent
unless the reader chooses to share it, and the report contains no book
content.

## States and failure behavior

- **Opened from outside with access the app can keep:** the book is in
  the library afterwards with the ordinary readable state.
- **Opened from outside with session-only access:** the book reads
  normally; it has no library row; the reader screen shows the one-line
  "opened from another app" notice with "Add to library"; closing the
  reader returns to the library without that book. The position is kept
  by book identity and applies when the same book is added later
  (extends v1 REQ-004's "position kept in case it comes back").
- **Opened from outside but not an EPUB, or DRM-protected:** the existing
  corrupt/DRM explanation is shown on the reader screen with the way back
  to the library; nothing is added.
- **Folder removal:** the confirmation names how many books will leave
  the library; positions are kept per REQ-004.
- **Undo window elapsed:** the removal stands; the book can be re-added
  and its position returns (existing behaviour).
- **New device or reinstall:** the app starts with an empty library,
  because nothing it stores is backed up (D3); the empty state is the
  ordinary first-launch guidance.
- **Update check without connectivity:** the browser shows its own error;
  the app is unaffected.
- **Crash report declined:** the stored report is discarded; the offer is
  not repeated for the same crash.
- **Sample text in a library with real books:** hidden by default;
  reachable from Settings.

## Requirements and acceptance

### v1.1.0 (OUT005)

- **REQ-101** The app has an adaptive launcher icon, including a
  monochrome variant, shown in the launcher, recents and the system
  splash.
  *Accept:* on the reference device the launcher, recents and the cold
  start splash all show the FastReader icon.
- **REQ-102** The first frame after a cold start matches the theme
  selected in Settings, with "System" resolved against the device.
  *Accept:* with Dark selected, and separately with System selected on a
  dark device, no light frame is visible during launch in a 60 fps screen
  recording.
- **REQ-103** FastReader is offered for `.epub` files from other apps and
  from the share sheet. Choosing it opens the reader. With keepable access
  the book is added to the library; with session-only access it is not,
  and the reader screen offers "Add to library". The position is kept by
  book identity either way.
  *Accept:* tapping an EPUB in the Files app lists FastReader; choosing it
  lands in the paused reader and the book is in the library afterwards.
  Sharing the same file from an app that grants session-only access opens
  the reader with the notice; the library has no row for it; after adding
  it through the picker it resumes at the position reached earlier.
- **REQ-104** Added folders are listed with their status and can be
  removed. Removal names the number of books affected, drops only the
  books that folder alone provided, and never touches files.
  *Accept:* remove a folder holding three books, one of which was also
  picked directly; the confirmation says two; two rows disappear, the
  picked one stays, all three files are untouched, and their positions
  are still kept.
- **REQ-105** Removing a book is undoable for a short time, and the copy
  says the file and position are kept.
  *Accept:* remove a book, choose undo within the window; the row is back
  at its previous position and progress.
- **REQ-106** Settings shows the installed version and offers a way to
  check for updates that opens the releases page in the browser. The app
  requests no network permission.
  *Accept:* the version shown equals the release tag; the release check
  still proves no network permission.
- **REQ-107** Nothing FastReader stores (library, positions, settings,
  cover cache) participates in the device's own backup (D3), and the
  privacy statement in the app and in the release notes says so along
  with everything else that leaves the device and what is kept locally
  after a session-only open (the reading position only).
  *Accept:* a device backup taken after adding books contains no
  FastReader data; every sentence of the statement maps to a manifest
  declaration or an observed behaviour.
- **REQ-108** In focused mode, one gesture changes reading speed without
  leaving focused mode; the new speed is shown briefly and statically.
  *Accept:* in focused mode, the gesture changes the speed in 25 WPM steps
  and playback continues; the overlay is text only and disappears within
  two seconds; tap-to-pause and long-press still work.
- **REQ-109** The empty library offers a bundled sample text in English
  and Spanish, Spanish first on a Spanish device, that streams
  immediately. The sample is never written to the reader's storage and is
  reachable later from Settings.
  *Accept:* on a fresh install, "Try a sample" reaches a playing stream in
  two taps; no file is created outside the app's private storage; with
  books present the sample is absent from the library and present in
  Settings.
- **REQ-110** Opening a book takes time proportional to its text, not to
  its file size.
  *Accept:* tap-to-paused-reader time is recorded on the reference device
  for (a) the largest owned illustrated EPUB, at least 50 MB, and (b) a
  copy of the same book with its images removed, first on v1.0.1 and then
  on v1.1.0. On v1.1.0, (a) opens within 25% of (b) measured on that same
  build, and neither opens slower than its own v1.0.1 recording.
- **REQ-111** The repository carries the MIT license (D2) and a README
  with screenshots, install steps, the privacy statement, and a statement
  that the app is distributed by link for personal use with a link to
  #33.
  *Accept:* the repository page shows MIT; the README contains all four
  items.
- **REQ-112** The published build keeps the v1.0.1 cue set unchanged,
  including the opt-in off-centre alignment (D1).
  *Accept:* in v1.1.0 the settings screen offers "Fixed focus letter" off
  by default exactly as in v1.0.1, and a v1.0.1 reader who had turned it
  on still has it on after updating.
- **REQ-113** Installing v1.1.0 over v1.0.1 keeps every book, position and
  setting, and every v1.0.1 capability is still present. This is the
  first update proof over published artifacts (see Evidence).
  *Accept:* on a device running the published v1.0.1 with two books, a
  mid-book position, a non-default colour and Fixed focus letter on,
  installing the published v1.1.0 in place shows the same books, position,
  colour and toggle state without any data clear.

### v1.2.0 (OUT006)

- **REQ-201** A "pause at chapters" setting controls chapter-boundary
  pauses; default on. Amends v1 REQ-015, which made the pause mandatory
  (D4).
  *Accept:* with the setting off, the stream crosses a chapter boundary
  without stopping; with it on, v1 behaviour is unchanged; a v1.1.0
  reader sees no change after updating.
- **REQ-202** On first open of a book, the reader is offered one action to
  skip past front matter to the first chapter.
  *Accept:* on a book whose spine starts with cover, title and copyright
  pages, the action lands on the first chapter's first word; the offer is
  not shown again for that book.
- **REQ-203** The library can be ordered by title, recently read, or
  recently added; recently read is the default; the choice persists.
  *Accept:* reading two words of book B moves it above book A in the
  default order after returning to the library; a chosen order survives
  restart.
- **REQ-204** Returning to the app within a short interval does not
  re-list every folder; the manual refresh always does.
  *Accept:* app-switch and return within the interval shows no scanning
  banner; manual refresh still finds a newly copied EPUB.
- **REQ-205** On screens of tablet width and in landscape, the reader
  places controls beside the stream and the library uses the available
  width.
  *Accept:* the tablet and landscape goldens show no control below the
  stream and no clipped text at the largest font size.
- **REQ-206** The interface is in Spanish when the device language is
  Spanish (D5).
  *Accept:* with the device in Spanish, every screen is in Spanish and no
  string falls back to English; TalkBack announces Spanish labels.
- **REQ-207** After a crash, the next launch offers to share a plain-text
  report through the system share sheet. Nothing is sent without the
  reader's action; the report contains no book text.
  *Accept:* after an induced crash, the offer appears once; declining
  discards it; sharing produces a text containing the version, device
  model, Android version and stack trace, and no words from any book.
- **REQ-208** Installing v1.2.0 over v1.1.0 keeps every book, position and
  setting; the new chapter-pause and sort settings read their defaults.
  *Accept:* on a device running the published v1.1.0 with books, a
  position and non-default settings, installing the published v1.2.0 in
  place shows the same books, position and settings, with "Pause at
  chapters" on and the library ordered by recently read.

### Cross-cutting

- **REQ-301** Every new control on every new surface meets the v1
  accessibility bar (REQ-060): TalkBack label, 48 dp target, respects font
  scale.
- **REQ-302** The stream stays a static-luminance text swap (REQ-062); the
  focused-mode speed overlay and the sample text do not add animation.
- **REQ-303** All data stays on device (REQ-050); the only outbound
  actions are the browser hand-off for updates and the reader-initiated
  share of a crash report.

## Accessibility and content

- New surfaces (folder list, undo, version row, speed overlay, sample
  offer, opened-from-outside notice, sort control, crash-report offer)
  carry the same TalkBack labels, touch targets and font-scale behaviour
  as v1 (REQ-060, REQ-301).
- The speed overlay in focused mode is text only and does not alternate
  luminance (REQ-062, REQ-302).
- Copy stays plain and honest: the update action says it opens the
  browser; the privacy statement lists what leaves the device; the sample
  says it is a sample and names its source.
- The Spanish interface covers every string including accessibility
  labels and the visual-only statement (REQ-061, REQ-206).

## Privacy, security, and policy

- No network permission, unchanged. The update check is a browser
  hand-off; the crash report is a reader-initiated share (REQ-303).
- **Backup (D3):** at the baseline the library document, positions,
  settings and cover cache participate in the device's own backup by
  default, which the release notes do not mention. From v1.1.0 they are
  excluded and the privacy statement says so (REQ-107).
- **Crash report content:** version, device model, Android version, stack
  trace; never book text, file names or paths that reveal the reader's
  library (REQ-207).
- **Bundled sample:** public-domain text only, with its source named in
  the app.
- **License (D2):** the repository is public with no license at the
  baseline (GitHub API: `license: null`, verified 2026-09-05); from v1.1.0
  it carries MIT (REQ-111).
- **Patent (#33, D1):** the owner ships the v1.0.1 cue set unchanged,
  including the opt-in alignment, on the basis that this is a personal
  app distributed by link. The residual risk is recorded in the decision
  table and is not reduced by this definition. Any store or catalogue
  listing is a public launch and is gated on #33 (D6). Nothing in this
  definition is a legal opinion.
- File access remains limited to documents the reader picks or hands to
  the app. A session-only external open persists no access and no library
  row; the only thing kept is the reading position, keyed by the book's
  own identity, and the notice on the reader screen says so.

## Success measures and guardrails

- **Unaided install-to-read:** one person outside the project, given only
  the release link and an EPUB on their phone, reaches a playing stream
  without help. "Three taps" counts from the first tap on the EPUB in the
  Files app: choose FastReader, then play.
- **Unaided housekeeping:** the same person removes a folder and undoes a
  book removal without asking how.
- Guardrails carried from v1: no silent loss of content, position or
  settings; smooth stream at max speed; honest speed copy; static
  luminance.
- Regression gate: no change reaches `main` with a broken golden or test
  (delivery constraint below).

## Constraints and non-goals

- **Sequence:** v1.2.0 ships after v1.1.0. Nothing in OUT006 is
  user-visible before OUT005 is released. Each release installs in place
  over the previous one.
- **Distribution (D6):** GitHub Releases by link only. No store or
  third-party catalogue listing while #33 is open.
- **Unchanged from v1:** EPUB only, DRM-free only, Android 8.0+, no
  network.
- **Delivery constraints the owner directs (engineering, not product
  behaviour; recorded so planning carries them):** automated checks run
  the unit and screenshot suites and static analysis on every push and
  pull request and block a broken golden or test; the v1 definition and
  plan documents are placed on the default branch at the paths the code
  cites; the toolchain and libraries are brought to current stable
  versions with goldens re-recorded in the same change (v1.2.0).
- **Non-goals for this batch:** Play Store publishing, catalogue listing,
  cloud sync, annotations and bookmarks, page-reading mode,
  text-to-speech, reading statistics, multi-word chunking, PDF/MOBI,
  collections and tags, a metadata editor, remote crash telemetry, a
  sample library or downloadable catalogue.

## Evidence

- Production audit 2026-09-05: #35. Every structural finding the
  definition relies on was verified against `a8caa09`: no icon and no
  `android:icon` in the manifest; no `ACTION_VIEW` or `ACTION_SEND`
  intent filter; folder-removal code present with no screen calling it;
  no version string in the app; no backup attributes in the manifest;
  light-only launch theme with no dark variant; no gesture other than tap
  and long-press on the reading surface; the whole file is hashed on
  every book open; one-tap removal with no undo; no continuous
  integration workflow; no README; no license; definition and plan
  documents only on closed PR branches.
- Build state at baseline (lead's run, not independently reproduced):
  debug build, 330 unit tests and lint green.
- **Update-in-place history, stated precisely:** `docs/evidence/16` shows
  the published v1.0.0 updated in place to a **v1.0.2 test build** cut
  from the frozen release head, keeping library, position and settings.
  `docs/evidence/32` (PR #34) shows a debug 1.0.0 build updated in place
  to the #32 build, exercising the schema 3→4 migration with books,
  position and settings kept. The **published v1.0.1 artifact** was never
  itself update-tested over v1.0.0, so REQ-113 is the first update proof
  between two published artifacts.
- v1 product definition: PR #2 at `dde30d59a01b27cf3fc5a60ef3cbddc911b5a78a`;
  plan: PR #7 at `de442b68c600f76518abc801b8b903539c845469`.
- Patent question and options: #33 and its 2026-09-02 status comment,
  which records the letter highlight as unassessed and dropping it as the
  safest prior-art baseline.
- Releases: v1.0.0 and v1.0.1 on GitHub Releases.

## Assumptions

- Readers accept sideloading; there is no catalogue.
- The owner's largest EPUB is a fair proxy for "large illustrated book".
- Android's default file-type association is enough for "Open with"; no
  custom document provider is needed.
- Spanish is the only additional interface language worth carrying now.

## Owner decisions

| Date | Key | Decision | Rationale | Affects |
| --- | --- | --- | --- | --- |
| 2026-09-05 | D1 | Published builds keep the v1.0.1 cue set as is, including the opt-in off-centre alignment. #33 stays open. **Residual risk, stated:** #33 records the alignment as reading on the patent's core claims and the letter highlight as unassessed; this decision accepts that risk for by-link personal distribution and reduces none of it. No capability is removed, so the update guarantee (REQ-113) is unaffected. | "Even if approaching prod grade, this is still a personal app." | REQ-112, REQ-113, README statement, D6 |
| 2026-09-05 | D2 | MIT license. | Permissive; enables forks and legal APK sharing. | REQ-111 |
| 2026-09-05 | D3 | Library, positions, settings and cover cache are excluded from the device's backup; the privacy copy says so. | The data is useless on another device without the folder grants, which never survive a restore. | REQ-107, new-device state |
| 2026-09-05 | D4 | Chapter-boundary pause becomes a setting, default on (amends v1 REQ-015). | Existing readers see no change; anyone can turn it off. | REQ-201 |
| 2026-09-05 | D5 | Spanish interface in v1.2.0 (overturns the v1 English-only non-goal). | The owner's library is Spanish. | REQ-206 |
| 2026-09-05 | D6 | GitHub Releases by link only; no store or catalogue listing while #33 is open. | Consistent with D1: a listing is a public launch, and #33's launch-blocking label applies to that. | Distribution constraint, REQ-106 copy |

## Remaining uncertainty

- Which gesture carries focused-mode speed (vertical drag versus volume
  keys) is interaction design inside REQ-108; either satisfies it.
- The exact "short interval" for the rescan throttle in REQ-204 and the
  undo window in REQ-105 are design choices; the acceptance tests state
  the observable behaviour, not the number.
- Which sources grant keepable versus session-only access varies by app
  and Android version; both paths are defined in REQ-103.

## Product issue graph

| Key | Kind | Parent | Title | Issue |
| --- | --- | --- | --- | --- |
| ROOT | ROOT | None | Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth | https://github.com/cedagova/fastReader/issues/36 |
| OUT005 | OUTCOME | ROOT | v1.1.0: shippable to strangers | https://github.com/cedagova/fastReader/issues/38 |
| OUT006 | OUTCOME | ROOT | v1.2.0: reading-experience depth | https://github.com/cedagova/fastReader/issues/39 |

Keys continue the v1 definition's OUT001 to OUT004 so the two graphs do
not collide. Requirement ownership: OUT005 owns REQ-101 to REQ-113;
OUT006 owns REQ-201 to REQ-208; REQ-301 to REQ-303 constrain both.

## Publication verification

Gate 1 content review at `46ca38f` requested changes (six findings);
delta review at `86283a5` found them resolved (content sound) with two
one-clause fixes carried in this head. Outcome issues published: OUT005
#38, OUT006 #39. The Requirements Brief comment, graph reconciliation and
verification, owner approval and the final exact-head review follow.
