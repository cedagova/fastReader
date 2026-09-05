# Requirements Brief: Consumer-ready FastReader: v1.1.0 shippable to strangers, then v1.2.0 reading depth

## Problem and intended outcome

FastReader v1.0.1 reads well but only for someone who already knows it.
A stranger gets a placeholder icon, cannot open an EPUB from their Files
app, cannot remove a folder they added, cannot tell which version they
have or whether an update exists, and the repository is public with no
license. The outcome: a person with only the release link installs the app
and is reading their own book within three taps, and keeps using it
through updates without asking the owner anything.

## Proposed behavior and main flows

- **v1.1.0, shippable to strangers:** real launcher icon and theme-correct
  first frame; "Open with FastReader" for `.epub` files and shares; a
  folder list with removal; undo for removed books; version and
  check-for-updates in Settings (browser hand-off, still no network);
  privacy copy that matches actual backup behaviour; one gesture changes
  speed inside focused mode; a bundled English and Spanish sample so the
  empty library can play something; opening a book no longer reads the
  whole file; license, README, automated checks; v1 definition and plan
  documents on `main`; the published build reflects the owner's decision
  on the cue set (#33).
- **v1.2.0, reading depth:** "pause at chapters" setting and a skip-front-
  matter action; library sorted by recently read; cheaper return-to-app
  rescans; tablet and landscape layouts; Spanish interface; share-a-crash-
  report on next launch; toolchain and library refresh.

## Scope and non-goals

Two sequential releases, each installing in place over the last. Unchanged
from v1: EPUB only, DRM-free, Android 8.0+, no network, GitHub Releases.
Non-goals: Play Store, cloud sync, annotations, page mode, TTS, statistics,
chunking, other formats, collections, metadata editing, remote telemetry.

## Product outcomes

- OUT001 v1.1.0: shippable to strangers (REQ-101 to REQ-114) — Pending
- OUT002 v1.2.0: reading-experience depth (REQ-201 to REQ-209) — Pending

Cross-cutting REQ-301 to REQ-303 carry the v1 accessibility, static-
luminance and on-device guardrails onto every new surface.

## Important constraints and success measures

- Three taps from install to reading an own EPUB, no in-app picker.
- Zero folder-removal or which-version support questions after v1.1.0.
- Every published build's cue configuration and permission set proved on
  the artifact; automated checks block a broken golden or test.
- v1 guardrails hold: no silent data loss, smooth at max speed, honest
  speed copy, static luminance.

## Evidence, assumptions, and uncertainty

- Evidence: production audit #35 verified at `a8caa09` (build and 330
  tests green; icon, intent filter, folder removal UI, version, backup
  rules, CI, README, license all absent); #33 for the patent question;
  v1 definition PR #2 and plan PR #7.
- Assumptions: sideloading is accepted; the owner's largest EPUB is a fair
  large-book proxy; system file-type association suffices; Spanish is the
  only extra language needed.
- Uncertainty: which gesture carries focused-mode speed; exact rescan and
  undo intervals; whether one-time-access opens persist on every Android
  version (fallback defined).

## Owner decisions

Six proposed defaults await the owner: D1 cue set for published builds
(#33), D2 license, D3 backup participation, D4 chapter pause as a setting,
D5 Spanish interface, D6 third-party catalogue. The contract is drafted on
the recommended answers; each is tied to the requirements it changes.

## Links and next action

- Root issue: https://github.com/cedagova/fastReader/issues/36
- Definition PR: https://github.com/cedagova/fastReader/pull/37
- Audit: https://github.com/cedagova/fastReader/issues/35
- Next action: `plan https://github.com/cedagova/fastReader/issues/36`
