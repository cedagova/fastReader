# Requirements Brief: RSVP fast reader for EPUB books

## Problem and intended outcome

Reading books faster than page-by-page reading, without app clutter.
The app flashes one word at a time (RSVP) from the user's own EPUB files:
open the app, tap play, and the book streams word by word at a controlled
speed. The reading experience is the product; the library is deliberately
simple.

## Proposed behavior and main flows

- **Library:** pick individual EPUBs or add folders; books stay where they
  are on the device (nothing is copied); folders rescan on app open plus a
  manual refresh; live search over title/author/filename; per-book progress.
- **Launch:** the app opens straight into the last-read book's paused
  view when it can; otherwise the library, with the reason visible.
- **Reading:** open a book → paused view showing the surrounding paragraph
  with the current word highlighted → play streams single words. Speed
  defaults to 250 WPM (range 100–1000, gentle hint above ~450 where
  research shows comprehension drops), ramping up gradually. Extra display
  time at sentence ends, commas, paragraphs, and long/rare words, scaled by
  one "pause strength" setting. Tap anywhere to pause with context; rewind
  by sentence/paragraph; chapter picker; scrub. Chapters auto-pause showing
  their title; images/tables are skipped with a small marker.
- **Cues:** word aligned on its recognition point with a colored pivot
  letter (on by default, toggleable), optional guide marks, font size,
  themes. Bounded customization with live preview.
- **Focused mode:** one gesture hides everything but the word stream.
- **Persistence & resilience:** position, speed, and settings survive app
  kills and restarts; the screen stays awake while playing; leaving the
  app (switch, call, lock) auto-pauses at the current word.
- **Release:** a versioned installable release obtainable by link
  (GitHub Releases per owner decision); in-place updates never lose data.

## Scope and non-goals

EPUB only (DRM-free, DRM books rejected with a clear message); Android
only. Non-goals: Play Store publishing, cloud sync, annotations/bookmarks,
page-reading mode, TTS, statistics, collections/tags, multi-word chunking,
non-English UI.

## Product outcomes

- OUT001 Library: pick, add-folder, search, progress
- OUT002 Core RSVP reading experience
- OUT003 Cue customization and focused mode
- OUT004 Shareable installable release

## Important constraints and success measures

- Success: real books read start-to-finish above normal reading speed with
  satisfactory comprehension; under 3 s from app open to words streaming.
- Guardrails: smooth presentation at max speed; no silent loss of content,
  position, or settings; in-product copy stays honest about the
  speed/comprehension trade-off; guide-mark design avoids Spritz's patented
  trade dress.
- Spanish and English books render and time correctly; basic accessibility
  bar (TalkBack-navigable library/controls/settings; the word stream itself
  is visual-only and documented as such). The stream changes text only —
  no luminance flashing or animation at any speed.

## Evidence, assumptions, and uncertainty

- Evidence: pinned research review (docs/product-definitions/
  cedagova-fastReader-1/research-rsvp.md) covering published comprehension
  studies, app timing conventions, and EPUB pitfalls; repo baseline
  90bfa63 (Compose scaffold, no product features yet).
- Assumptions: owner's EPUBs are DRM-free, Spanish/English; single device;
  friends accept sideloading.
- Uncertainty: only design-time details (guide-mark visuals, exact gesture
  assignments) — no open product decisions.

## Owner decisions

Six decisions recorded 2026-09-01 in definition.md: GitHub Releases
distribution; Spanish+English books with English UI; chapter
auto-pause/skip markers/dropped footnotes; evidence-backed timing package;
single-word v1 (no chunking); read-in-place library with rescan-on-open;
plus the basic accessibility bar.

## Links and next action

- Root issue: https://github.com/cedagova/fastReader/issues/1
- Definition PR: https://github.com/cedagova/fastReader/pull/2
- Next action: `plan https://github.com/cedagova/fastReader/issues/1`
