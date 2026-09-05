# Requirements Brief: Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth

## Problem and intended outcome

FastReader v1.0.1 reads well but only for someone who already knows it.
A stranger gets a placeholder icon, cannot open an EPUB from their Files
app, cannot remove a folder they added, cannot tell which version they
have or whether an update exists, and the repository is public with no
license. The outcome: a person with only the release link installs the app
and is reading their own book within three taps, and keeps using it
through updates without asking the owner anything. It stays a personal
app distributed by link (owner decision D1/D6).

## Proposed behavior and main flows

- **v1.1.0, shippable to strangers:** real launcher icon and theme-correct
  first frame; "Open with FastReader" for `.epub` files and shares, with a
  defined path when the source grants session-only access; a folder list
  with removal; undo for removed books; version and check-for-updates in
  Settings (browser hand-off, still no network); privacy copy that says
  nothing the app stores is backed up; one gesture changes speed inside
  focused mode; a bundled English and Spanish sample so the empty library
  can play something; opening a book no longer depends on its image
  payload; MIT license and README; the published build keeps the v1.0.1
  cue set as the owner decided; update over v1.0.1 proven on published
  artifacts.
- **v1.2.0, reading depth:** "pause at chapters" setting and a skip-front-
  matter action; library sorted by recently read; cheaper return-to-app
  rescans; tablet and landscape layouts; Spanish interface; share-a-crash-
  report on next launch; update over v1.1.0 proven.

## Scope and non-goals

Two sequential releases, each installing in place over the last. Unchanged
from v1: EPUB only, DRM-free, Android 8.0+, no network. Distribution stays
GitHub Releases by link; no store or catalogue listing while #33 is open.
Owner-directed delivery constraints (not product behaviour): automated
checks on every change, v1 definition and plan documents on `main`,
toolchain refresh in v1.2.0. Non-goals: Play Store, catalogue, cloud sync,
annotations, page mode, TTS, statistics, chunking, other formats,
collections, metadata editing, remote telemetry.

## Product outcomes

- OUT005 v1.1.0: shippable to strangers (REQ-101 to REQ-113) — Pending
- OUT006 v1.2.0: reading-experience depth (REQ-201 to REQ-208) — Pending

Cross-cutting REQ-301 to REQ-303 carry the v1 accessibility, static-
luminance and on-device guardrails onto every new surface.

## Important constraints and success measures

- One person outside the project, given only the link and an EPUB on
  their phone, reaches a playing stream unaided: tap the EPUB, choose
  FastReader, play. The same person removes a folder and undoes a book
  removal unaided.
- v1 guardrails hold: no silent data loss, smooth at max speed, honest
  speed copy, static luminance.
- No change reaches `main` with a broken golden or test.

## Evidence, assumptions, and uncertainty

- Evidence: production audit #35 verified at `a8caa09` (icon, intent
  filter, folder-removal screen, version, backup rules, README, license
  all absent); #33 for the patent question and its residual risk; v1
  definition PR #2 and plan PR #7. Update history stated precisely: v1.0.0
  → v1.0.2 test build (#16) and a debug 1.0.0 → #32 build migration were
  tested; the published v1.0.1 artifact was not, so v1.1.0 carries the
  first published-to-published update proof.
- Assumptions: sideloading is accepted; the owner's largest EPUB is a fair
  large-book proxy; system file-type association suffices; Spanish is the
  only extra language needed.
- Uncertainty: which gesture carries focused-mode speed; exact rescan and
  undo intervals; which sources grant keepable versus session-only access
  (both paths defined).

## Owner decisions

Recorded 2026-09-05: D1 published builds keep the v1.0.1 cue set as is,
including the opt-in alignment, accepting the residual risk #33 records
for by-link personal distribution; D2 MIT license; D3 nothing the app
stores participates in device backup; D4 chapter pause becomes a setting,
default on; D5 Spanish interface in v1.2.0; D6 GitHub Releases by link
only, no store or catalogue listing while #33 is open.

## Links and next action

- Root issue: https://github.com/cedagova/fastReader/issues/36
- Definition PR: https://github.com/cedagova/fastReader/pull/37
- Audit: https://github.com/cedagova/fastReader/issues/35
- Next action: `plan https://github.com/cedagova/fastReader/issues/36`
