# Product Definition: Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth

- Product definition issue: https://github.com/cedagova/fastReader/issues/36
- Product definition PR: https://github.com/cedagova/fastReader/pull/37
- Requirements brief: Pending
- Status: Under review
- Classification: DECOMPOSE
- Definition lead: cedagova
- Started: 2026-09-05

## Pinned evidence baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `a8caa0964bf892efa6ba5f1a33b47a06309bba6a` |

## Objective

Turn FastReader v1.0.1, today a private sideload used by the owner and a
few friends, into a consumer product that a stranger can install from a
link, get their own books into, and keep using through updates. Deliver it
as two sequential releases: **v1.1.0**, everything a first-time user needs
before and around reading, and **v1.2.0**, depth in the reading experience
itself. The reading engine shipped in v1 (definition PR #2, REQ-001 to
REQ-071) is preserved unchanged except where a requirement below explicitly
amends it.

## User or operator need

The v1 definition served one reader who already knew what the app was and
where their EPUBs lived. A stranger does not. The 2026-09-05 production
audit (#35) found the engine production-grade and the shell missing: the
app has no icon, cannot be chosen from "Open with", cannot forget a folder
once added, does not say which version it is, has no way to learn about
updates, is public with no license, and still carries the unresolved
patent question (#33). Each of these turns a first install into a
dead end. The owner wants to share the app beyond friends without
answering support questions that the product should have answered itself.

## Actors and context

- **New reader:** someone who received a link, has never seen the app, and
  keeps EPUBs wherever their phone put them (Downloads, a browser, an
  email attachment, a synced folder). Android 8.0+ phone or tablet.
- **Returning reader:** the owner or a friend already on v1.0.x, whose
  library, positions and settings must survive the update in place.
- **Owner:** the sole maintainer, who publishes releases and answers
  questions; the product should minimise both.
- **Triggers:** tapping an `.epub` in another app; installing from the
  release link; a device restore onto a new phone; wanting to remove a
  mistaken folder; wanting to know whether an update exists.
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
4. The app is legally and organisationally shippable: a license, a
   readable repository front page, automated checks on every change, and
   a recorded decision on the shipped cue set for published builds (#33).
5. After v1.1.0, the reading experience itself gets the depth a daily
   reader asks for: control over chapter pauses, a library ordered by what
   was read last, layouts that use a tablet or a rotated phone, the
   owner's own language in the interface, and a way to report a problem
   without any data leaving the device unasked.

## Product behavior and flows

### v1.1.0: shippable to strangers (OUT001)

**Install and first launch.** The device shows a real FastReader icon in
the launcher, the app switcher and the system splash. The first frame the
app draws already uses the reader's theme, so a dark-mode device never
flashes white on launch. The empty library offers a bundled sample text
(one short public-domain passage in English and one in Spanish) so the
reader can press play and see the stream within seconds, before they have
found any EPUB of their own. The sample is not a library book: it is not
written into the reader's storage and does not appear once real books
exist unless the reader asks for it.

**Getting a book in from anywhere.** Tapping an `.epub` file in Files, a
browser download, an email attachment or a share sheet offers FastReader.
Choosing it adds the book to the library and opens it in the reader. Where
the source grants durable access, the book stays in the library like a
picked one; where the source grants one-time access only, the app says so
and offers to add the book properly through the picker.

**Library housekeeping.** The library lists every added folder with its
status and lets the reader remove one. Removing a folder removes the books
only that folder provided and never touches files. Removing a book from a
row is reversible for a short time through an undo affordance; the copy
says the file and the reading position are kept.

**Knowing what you have.** Settings shows the installed version and a
"check for updates" action that opens the releases page in the device
browser. The app itself still makes no network request. The privacy copy
states exactly what leaves the device, including whether the library
document participates in the device's own backup.

**Focused reading.** In focused mode the reader can change speed with one
gesture without leaving focused mode; the new value is shown briefly and
statically, then disappears.

**Publishing.** The repository carries a license, a README with
screenshots and install steps, and automated checks that run the test and
screenshot suites on every change. The definition and plan documents from
v1 live on the default branch so code references resolve. The published
build reflects the owner's decision on the shipped cue set (#33).

### v1.2.0: reading depth (OUT002)

**Chapters.** A "pause at chapters" setting controls whether the stream
stops at each chapter boundary (default on, preserving v1 behaviour). On
first open of a book, the reader is offered a single action to skip past
front matter to the first real chapter.

**Library order.** The library can be ordered by title, recently read, or
recently added; recently read is the default.

**Rescan cost.** Returning to the app after a short absence does not
re-list every folder; the manual refresh remains the explicit path.

**Large screens and rotation.** On tablets and in landscape, the reader
places controls beside the stream rather than below it, and the library
uses the width.

**Language.** The interface is available in Spanish when the device is set
to Spanish, if the owner overturns the v1 English-only non-goal.

**Reporting a problem.** If the app crashes, the next launch offers to
share a plain-text report through the system share sheet. Nothing is sent
unless the reader chooses to share it, and the report contains no book
content.

**Currency.** The build toolchain and libraries are brought to current
stable versions with the screenshot goldens re-recorded in the same
change.

## States and failure behavior

- **Opened from outside with one-time access:** the book opens and reads,
  and the library row explains that it must be added through the picker
  to be kept.
- **Opened from outside but not an EPUB or DRM-protected:** the existing
  corrupt/DRM states apply; the reader is not left on a blank screen.
- **Folder removal:** confirmation names how many books will leave the
  library; positions are kept per REQ-004.
- **Undo window elapsed:** the removal stands; the book can be re-added
  and its position returns (existing behaviour).
- **Device restore onto a new device:** every book reads as
  access-revoked with the existing re-grant path, and one banner explains
  that grants do not survive a restore. If the owner excludes the library
  from backup, the app starts empty on the new device and this state does
  not arise.
- **Update check without connectivity:** the browser shows its own error;
  the app is unaffected.
- **Crash report declined:** the stored report is discarded; the offer is
  not repeated for the same crash.
- **Sample text in a library with real books:** hidden by default;
  reachable from Settings.

## Requirements and acceptance

### v1.1.0 (OUT001)

- **REQ-101** The app has an adaptive launcher icon, including a
  monochrome variant, shown in the launcher, recents and the system
  splash.
  *Accept:* on the reference device the launcher, recents and the cold
  start splash all show the FastReader icon; the static analysis warning
  for a missing icon is gone.
- **REQ-102** The first frame after a cold start matches the reader's
  theme choice.
  *Accept:* with the dark theme selected, no light frame is visible during
  launch in a screen recording at 60 fps.
- **REQ-103** FastReader is offered for `.epub` files from other apps and
  from the share sheet. Choosing it adds the book and opens the reader.
  *Accept:* tapping an EPUB in the Files app lists FastReader; choosing it
  lands in the paused reader for that book; the book appears in the
  library afterwards. A share from a browser download does the same.
- **REQ-104** Added folders are listed with their status and can be
  removed. Removal drops only the books that folder alone provided and
  never touches files.
  *Accept:* remove a folder holding three books, one of which was also
  picked directly; two rows disappear, the picked one stays, all three
  files are untouched, and their positions are still kept.
- **REQ-105** Removing a book is undoable for a short time, and the copy
  says the file and position are kept.
  *Accept:* remove a book, choose undo within the window; the row is back
  at its previous position and progress.
- **REQ-106** Settings shows the installed version and offers a way to
  check for updates that opens the releases page in the browser. The app
  requests no network permission.
  *Accept:* the version shown equals the release tag; the release build
  still declares no network permission (existing release check).
- **REQ-107** The privacy statement in the app and in the release notes
  states exactly what leaves the device, including the backup behaviour
  chosen by the owner (decision D3).
  *Accept:* a reviewer can match every sentence of the statement to a
  manifest declaration or an observed behaviour.
- **REQ-108** In focused mode, one gesture changes reading speed without
  leaving focused mode; the new speed is shown briefly and statically.
  *Accept:* in focused mode, the gesture changes the speed in 25 WPM steps
  and playback continues; the overlay is text only and disappears within
  two seconds; tap-to-pause and long-press still work.
- **REQ-109** The empty library offers a bundled sample text in English
  and Spanish that streams immediately. The sample is never written to the
  reader's storage.
  *Accept:* on a fresh install, "Try a sample" reaches a playing stream in
  two taps; no file is created outside the app's private storage.
- **REQ-110** Opening a book costs I/O proportional to its text, not to
  its whole file size.
  *Accept:* on the largest owned EPUB (an illustrated book of at least
  50 MB), time from tap to paused reader is at least halved against
  v1.0.1 on the reference device, and no book opens slower than before.
- **REQ-111** The repository has a license, a README with screenshots and
  install steps, and automated checks that run the unit and screenshot
  suites and static analysis on every push and pull request.
  *Accept:* the repository page shows the license; a pull request that
  breaks a golden shows a failed check.
- **REQ-112** The v1 definition and plan documents are on the default
  branch at the paths the code cites.
  *Accept:* every `docs/product-definitions/...` and `docs/plans/...` path
  mentioned in source comments resolves on `main`.
- **REQ-113** The published build reflects the owner's decision on the
  shipped cue set (D1). If the decision removes a capability from
  published builds, that capability is absent from the release artifact,
  not merely hidden.
  *Accept:* the release check proves the decided configuration on the
  artifact itself, the same way it proves the absence of network
  permission today.
- **REQ-114** Installing v1.1.0 over v1.0.1 keeps every book, position and
  setting (REQ-040 re-proved for this release).

### v1.2.0 (OUT002)

- **REQ-201** A "pause at chapters" setting controls chapter-boundary
  pauses; default on. Amends v1 REQ-015, which made the pause mandatory
  (decision D4).
  *Accept:* with the setting off, the stream crosses a chapter boundary
  without stopping; with it on, v1 behaviour is unchanged.
- **REQ-202** On first open of a book, the reader is offered one action to
  skip past front matter to the first chapter.
  *Accept:* on a book whose spine starts with cover, title and copyright
  pages, the action lands on the first chapter's first word; the offer is
  not shown again for that book.
- **REQ-203** The library can be ordered by title, recently read, or
  recently added; recently read is the default; the choice persists.
  *Accept:* reading two words of book B moves it above book A in the
  default order after returning to the library.
- **REQ-204** Returning to the app within a short interval does not
  re-list every folder; the manual refresh always does.
  *Accept:* app-switch and return within the interval shows no scanning
  banner; manual refresh still finds a newly copied EPUB.
- **REQ-205** On screens of tablet width and in landscape, the reader
  places controls beside the stream and the library uses the available
  width.
  *Accept:* the tablet and landscape goldens show no control below the
  stream and no clipped text at the largest font size.
- **REQ-206** The interface is available in Spanish when the device
  language is Spanish (decision D5).
  *Accept:* with the device in Spanish, every screen is in Spanish and no
  string falls back to English; TalkBack announces Spanish labels.
- **REQ-207** After a crash, the next launch offers to share a plain-text
  report through the system share sheet. Nothing is sent without the
  reader's action; the report contains no book text.
  *Accept:* after an induced crash, the offer appears once; declining
  discards it; sharing produces a text containing the version, device
  model, Android version and stack trace, and no words from any book.
- **REQ-208** The build toolchain and libraries are at current stable
  versions and every golden is re-recorded in the same change.
  *Accept:* static analysis reports no "newer version available" for the
  build plugin, language, UI framework or screenshot library.
- **REQ-209** Installing v1.2.0 over v1.1.0 keeps every book, position and
  setting, including the new sort and chapter-pause settings' defaults.

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
  offer, sort control, crash-report offer) carry the same TalkBack labels,
  touch targets and font-scale behaviour as v1 (REQ-060, REQ-301).
- The speed overlay in focused mode is text only and does not alternate
  luminance (REQ-062, REQ-302).
- Copy stays plain and honest: the update action says it opens the
  browser; the privacy statement lists what leaves the device; the sample
  says it is a sample.
- Spanish UI, if decided, covers every string including accessibility
  labels and the visual-only statement (REQ-061).

## Privacy, security, and policy

- No network permission, unchanged. The update check is a browser
  hand-off; the crash report is a reader-initiated share (REQ-303).
- **Backup (decision D3):** today the library document, positions,
  settings and cover cache participate in the device's own backup by
  default, which the release notes do not mention. The owner decides
  whether to exclude them; either way the privacy statement says what
  happens (REQ-107).
- **Crash report content:** version, device model, Android version, stack
  trace; never book text, file names or paths that reveal the reader's
  library (REQ-207).
- **Bundled sample:** public-domain text only, with its source named in
  the app.
- **License (decision D2):** the repository has been public since
  2026-09-02 with no license, which means all rights reserved.
- **Patent (#33, decision D1):** the published build carries only the cue
  configuration the owner decides to ship. Nothing in this definition is
  a legal opinion.
- File access remains limited to documents the reader picks or hands to
  the app; an external open with one-time access is not silently
  persisted.

## Success measures and guardrails

- A person who has never seen the app installs from the link and is
  reading their own EPUB within three taps, without the in-app picker.
- Zero "how do I remove this folder" and "which version do I have"
  questions to the owner after v1.1.0.
- Guardrails carried from v1: no silent loss of content, position or
  settings; smooth stream at max speed; honest speed copy; static
  luminance.
- New guardrail: every published build's cue configuration and permission
  set are proved on the artifact, not assumed from source.
- Regression gate: the automated checks fail any change that breaks a
  golden or a test before it reaches `main`.

## Constraints and non-goals

- **Sequence:** v1.2.0 ships after v1.1.0. Nothing in OUT002 is
  user-visible before OUT001 is released. Each release installs in place
  over the previous one.
- **Unchanged from v1:** EPUB only, DRM-free only, Android 8.0+, no
  network, signed APK on GitHub Releases as the primary channel.
- **Non-goals for this batch:** Play Store publishing, cloud sync,
  annotations and bookmarks, page-reading mode, text-to-speech, reading
  statistics, multi-word chunking, PDF/MOBI, collections and tags, a
  metadata editor, remote crash telemetry. Distribution through a
  third-party catalogue is decision D6 and, if taken, is an owner action
  after v1.1.0 rather than a product requirement.
- The bundled sample is not a library feature: no sample library, no
  downloadable catalogue.

## Evidence

- Production audit 2026-09-05: #35. Every finding cited there was verified
  against `a8caa09`: no icon and no `android:icon` in the manifest (lint
  `MissingApplicationIcon`); no `ACTION_VIEW` intent filter; repository
  code for folder removal exists with no screen calling it; no version
  string in the app; no backup attributes in the manifest; light-only
  launch theme; no gesture other than tap and long-press on the reading
  surface; the whole file is hashed on every book open; no CI workflow;
  no README; no license; definition and plan documents only on closed PR
  branches.
- Build state at baseline: `assembleDebug`, 330 unit tests and lint green.
- v1 product definition: PR #2 at `dde30d59a01b27cf3fc5a60ef3cbddc911b5a78a`;
  plan: PR #7 at `de442b68c600f76518abc801b8b903539c845469`.
- Patent question and options: #33 and its 2026-09-02 status comment.
- Releases: v1.0.0 and v1.0.1 on GitHub Releases, both installed and
  update-tested on the emulator matrix (evidence under `docs/evidence/16`).

## Assumptions

- Readers accept sideloading; a third-party catalogue is optional.
- The owner's largest EPUB is a fair proxy for "large illustrated book".
- Android's default file-type association is enough for "Open with"; no
  custom document provider is needed.
- Spanish is the only additional interface language worth carrying now.

## Owner decisions

None recorded yet. The following are **proposed defaults awaiting the
owner's answer**; the lead drafted the contract on the recommended choice
so review can proceed, and the affected requirements are marked. Each will
be moved into the dated table above once answered; a different answer
changes only the requirements it names.

| Key | Question | Recommended | Alternatives | Affects |
| --- | --- | --- | --- | --- |
| D1 | What cue configuration do published builds ship, given #33? | Publish without the off-centre alignment capability; keep the letter highlight and guide marks; seek an attorney opinion before any catalogue or store listing. | (b) also drop the highlight; (c) attorney opinion first, ship nothing until then; (d) license the SDK; (e) wait for expiry. | REQ-113 |
| D2 | Which license? | A permissive OSI license (MIT or Apache-2.0). | Copyleft (GPL-3.0); keep all rights reserved (blocks catalogues and forks). | REQ-111, D6 |
| D3 | Does the library document participate in device backup? | Exclude the library, positions, settings and cover cache from backup and say so; they are useless on another device without the folder grants. | Keep backup and add the restore-explanation state. | REQ-107, restore state |
| D4 | Make the chapter pause a setting (amends v1 REQ-015)? | Yes, default on. | Keep mandatory. | REQ-201 |
| D5 | Add a Spanish interface (overturns the v1 English-only non-goal)? | Yes, in v1.2.0. | Stay English-only. | REQ-206 |
| D6 | Distribute through a third-party catalogue after v1.1.0? | Yes, IzzyOnDroid, once D2 is a permissive license; it needs no app changes. | GitHub Releases only. | Update copy in REQ-106 |

## Remaining uncertainty

- Which gesture carries focused-mode speed (vertical drag versus volume
  keys) is interaction design inside REQ-108; either satisfies it.
- The exact "short interval" for the rescan throttle in REQ-204 and the
  undo window in REQ-105 are design choices; the acceptance tests state
  the observable behaviour, not the number.
- Whether one-time-access opens can be persisted on every Android
  version is a platform question inside REQ-103; the fallback path is
  defined.

## Product issue graph

| Key | Kind | Parent | Title | Issue |
| --- | --- | --- | --- | --- |
| ROOT | ROOT | None | Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth | https://github.com/cedagova/fastReader/issues/36 |
| OUT001 | OUTCOME | ROOT | v1.1.0: shippable to strangers | Pending |
| OUT002 | OUTCOME | ROOT | v1.2.0: reading-experience depth | Pending |

Requirement ownership: OUT001 owns REQ-101 to REQ-114; OUT002 owns
REQ-201 to REQ-209; REQ-301 to REQ-303 constrain both.

## Publication verification

Gate 1 (content review of this contract and the pending outcome manifest)
is the current step. Outcome issues, the Requirements Brief comment, graph
reconciliation and verification, owner approval and the final exact-head
review follow after review and the owner's answers to D1 to D6.
