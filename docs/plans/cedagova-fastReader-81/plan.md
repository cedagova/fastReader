# Implementation Plan: Speed dial names the average experienced speed

- Planning issue: https://github.com/cedagova/fastReader/issues/81
- Planning PR: https://github.com/cedagova/fastReader/pull/82
- Status: Ready for implementation
- Root classification: LEAF
- Delivery topology: DIRECT
- Planner: Planning lead (Claude)
- Started: 2026-09-10

## Pinned baselines

| Repository | Baseline |
| --- | --- |
| `cedagova/fastReader` | `77266566f937f31e667df9f69c90da0db23d6cf4` |

Research evidence (documents only, on `main` after the baseline):
`docs/product-definitions/cedagova-fastReader-1/research-pacing.md` at
`cedagova/fastReader@752cfcc9a93d767d56117a4ce11b31a509d7b084` (the note at
`3315309652c1935f0ece3e4dce13101ee5a9b4a5` plus the 2026-09-10 addendum that
re-simulates the exact rule this plan states).

## Preserved objective and boundaries

Root #81 (owner-authored, 2026-09-10) asks that the speed dial name the
reader's real average speed, pauses included, so that at one setting a
comma-rich paragraph and a long unpunctuated sentence feel like the same
speed, while the research pauses at clause, sentence and paragraph
boundaries stay.

Boundaries preserved from the root: one PR in this repository; no change to
the RARE classifier, ramp shape, chunking or per-chapter normalization; no
migration of stored per-book WPM values; no second "effective WPM" readout;
cue rendering, patent issue #33 and the v1 definition document untouched.
The v1 acceptance for REQ-011 (sentence end holds ~3x a plain word; pause
strength OFF makes all words uniform), REQ-013 (ramp and re-orientation
hold), REQ-017 (time remaining at current speed; doubling speed halves it)
and REQ-040 (an update in place preserves positions) remains in force; the
root changes only what the dial's number means.

## Classification

- **ROOT #81 — `LEAF`.** One coherent, independently verifiable outcome in
  one repository: three timing behaviours (budget normalization,
  span-proportional pauses, breath holds) that are only meaningful together,
  because each later behaviour re-normalizes the first, and that share one
  validation boundary (the timing unit tests, the real-book fixture, one
  emulator smoke). Splitting them would ship the dial's new meaning in one
  release and the feel that justifies it in another. No children are
  published; the fast-leaf path applies.

Not `ALREADY_SATISFIED`: at the baseline the engine adds every pause on top
of `60000 / wpm` (`RsvpTimingEngine.durationMillis`), so no acceptance
condition of the root holds today. Not `NEEDS_DECISION`: the one
material choice, what happens to the WPM numbers readers already set, was
decided by the owner on 2026-09-10 (Option A, recorded under Assumptions and
open questions). Every other choice is a constant or tokenizer rule that is
reversible and testable.

## Current-state evidence

At the baseline:

- `app/src/main/java/com/cedagova/fastreader/timing/RsvpTimingEngine.kt`:
  `duration = word × hold + word × (pauseMultiplier − 1) × pauseStrength`,
  with `word = (60000 / wpm) / rampSpeedFraction`. Pauses are additive; the
  engine is a pure function of `(token, settings, state)` and never sees the
  book.
- `.../timing/TimingSettings.kt`: `RsvpTiming` holds every constant with a
  research trace (sentence 3.0x, clause 2.0x, paragraph 3.5x, heading 4.0x,
  emphasis 1.5x, ramp 0.8 over 20 s, re-orientation 3x; range 100–1000);
  `PauseStrength` scales only the extra part of a pause; `TimingSettings(wpm,
  pauseStrength, rampEnabled)` is the value the engine reads. It is not
  serialized and is absent from the persisted settings and catalog schema.
- `.../reader/RemainingTime.kt`: `RemainingTimeIndex.build` sweeps the book
  once, off the main thread, and stores a suffix sum of per-token
  multipliers measured through the engine; `suffix[0] / tokens.size` is the
  book's mean multiplier at that pause strength. It is built in two places:
  on the open path (`ReaderBookView`, awaited before the `ReaderSession` is
  constructed, so the mean exists before the first token is shown) and in
  `ReaderViewModel.rebuildIndexIfStale` / `ReaderUiState` when the pause
  strength changes.
- `.../content/Tokenizer.kt` and `TokenStream.kt`: punctuation is
  attributed to the preceding word as the strongest `Boundary` in the
  following run (`NONE < CLAUSE < SENTENCE < PARAGRAPH < HEADING`; the
  ordinal order is load-bearing for `maxOf` and is not persisted anywhere);
  word classes come from `WordClassifier`. Nothing records the distance
  from the previous boundary. `ContentPipelineVersion.CURRENT = 1` is
  persisted with every stored position and a mismatch drops the reader to
  the fallback position; the file's own rule is that it is bumped only when
  token count, order or indices change.
- `.../settings/ui/SettingsPreview.kt` streams a 24-token hand-written
  sample at the default 250 WPM regardless of the dial (a demonstration of
  rhythm, REQ-023), reading `plainWordMillis` and `durationMillis` from the
  engine; the sample's spans before its boundaries are 3, 4, 4, 4 and 9.
- Tests: `RsvpTimingEngineTest`, `TimingScenarios`, `TimingOverRealBookTest`
  (which parses `ContentFixtures.spanishNovel()`, three hand-written spine
  items of about 37 tokens through the real pipeline, so "real" means real
  pipeline, not book-sized), `TokenizerTest`, `RemainingTimeTest`,
  `ReaderPauseStrengthTest`, `ReaderPositionTest`; goldens under
  `app/screenshots/`. Every `Reading` golden renders "Under a minute left"
  from that fixture, because `remainingLabel` is minutes-only. No bundled
  sample book exists at the baseline: `f71cdb9` removed the shipped samples
  and their open path, `a05ce4e` the script that built them; the tree holds
  no `.epub` and no `app/src/main/assets/`.
- Research measurements (two Gutenberg books, app multipliers): mean
  multiplier 1.18–1.23 today, so a dial of 300 averages 244–255 WPM;
  per-sentence speed p5 0.53–0.63 and p95 0.89–0.91 of the dial. Under the
  exact rule this plan states (addendum): mean before normalization
  1.12–1.14; sentences of 4+ words min 0.67–0.71, p5 0.91–0.93, p95
  1.02–1.03, max 1.05–1.07; 60-word windows min 0.77–0.91, p5 0.96–0.97,
  p95 1.03–1.04, max 1.06–1.08; plain-word burst 1.12–1.14x.

## Selected implementation direction

One PR on `main`, three behaviours landed as ordered commits so each can be
read and reverted on its own:

1. **Budget normalization.** The per-book mean multiplier for the current
   pause strength is computed wherever the remaining-time index is built
   and carried into the timing settings the session hands the engine (a new
   non-persisted settings value, default 1.0, so the engine stays pure and
   the reference build used to measure multipliers is not itself
   normalized). The mean travels with every index build: the open path and
   both pause-strength rebuild sites, so a rebuild never leaves a stale mean
   in the session. The engine divides the target word duration by it. The
   remaining-time index is read in the same normalized units, which makes
   time remaining at the first token exactly `tokens / wpm`. The ramp and
   the re-orientation hold keep multiplying the (now normalized) base and
   stay outside the mean, as `estimatedMillis` already excludes them.
   `PauseStrength.OFF` yields mean 1.0 and unchanged behaviour. What the
   dial means changes accordingly: the 100–1000 range bounds the average,
   and plain words inside a book run up to about 1.15x the dial (about
   1150 WPM instantaneous at the ceiling); the `RsvpTiming` KDoc for the
   range and the engine's formula KDoc say so.
2. **Span-proportional pauses.** The tokenizer records on each token its
   span: the number of words since the previous boundary token of `CLAUSE`
   or stronger. Emphasis words and breath holds do not reset it, and it is
   a token property independent of pause strength (this is the rule the
   addendum measured; it differs from the first simulation, where emphasis
   reset the run). The engine scales the extra part of a `CLAUSE`,
   `SENTENCE` or `PARAGRAPH` pause by `min(1, span / SPAN_FULL_PAUSE_WORDS)`
   with the constant around 10; `HEADING` keeps its full value; emphasis
   multipliers are not span-scaled; `max(boundary, emphasis)` and the
   abbreviation exemption are unchanged.
3. **Breath holds.** The tokenizer marks a new breath word class on the
   word before a conjunction or relative pronoun once about 8 plain words
   have run since the last boundary or hold, and unconditionally after
   about 14; the breath counter is its own thing and never changes a span,
   so a 13-word sentence with a hold at word 8 still ends with a full 3.0x.
   English and Spanish word lists live beside the tokenizer. The engine
   gives the class a multiplier around 1.4x combined with any boundary
   pause by `max`, through the same pause-strength scaler, so OFF removes
   it. `Boundary` and its ordinals are untouched. Skip markers, headings
   and abbreviations are untouched.

Neither tokenizer change alters token count, order or indices, so
`ContentPipelineVersion.CURRENT` stays at 1 and no stored position moves
(the same statement the `WordToken` KDoc already makes for increment 002's
change); the implementer must not bump it defensively, and a test asserts a
position stored at the baseline still resolves.

The settings preview keeps its fixed 250 WPM but normalizes by its own
sample's mean, so the speed it states is an average too. Decision recorded
here, stated as the outcome: the span closing at the token the rhythm
readout measures (`SENTENCE_END`, today `SAMPLE[10]` "place." at span 4)
reaches at least 10 words, so the previewed sentence pause stays a full
3.0x and the pause-strength contrast REQ-023 exists to show is not halved
by span-scaling; the resulting golden re-record is the intended, stated
change. The `Reading` goldens do not move: they render "Under a minute
left" from a 37-token fixture and never render a duration.

All new constants are stated once in `RsvpTiming` with a research trace to
`research-pacing.md` and an arithmetic unit test (AD-5). Because the mean is
measured through the engine, any later constant change flows into the
normalization automatically. The next version's release notes record that
the dial now names the average.

Patent note for the implementer: the normalization mechanism appears in the
description of US 8,903,174 (Spritz), but its independent claim 1 covers only
the fixed optimal-recognition-point alignment already tracked by #33; the
same normalization is open source in Sprint Reader. No new exposure beyond
#33 is expected, and nothing here touches cue rendering.

## Issue publication manifest

| Key | Kind | Parent | Repository | Title | Delivery | Blocked by | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ROOT | LEAF | None | cedagova/fastReader | Speed dial names the average experienced speed: pause budget, span-proportional pauses, breath holds | None | None | https://github.com/cedagova/fastReader/issues/81 |

## Acceptance coverage

Every acceptance criterion of root #81 maps to ROOT; the direction above
gives each one an observable check:

| Root acceptance | Covered by |
| --- | --- |
| Whole-book steady-state time equals `tokens × 60000 / wpm` within 0.5% or one millisecond per token, whichever is larger, at SUBTLE, NORMAL, STRONG and 100/250/1000 WPM | Direction 1; `TimingOverRealBookTest` on the pipeline fixture and the corpus fixture (the per-token rounding of a ~53 ms plain word at 1000 WPM is systematic, hence the millisecond floor) |
| Time remaining at the first token equals `tokens / wpm` within 1%; doubling speed halves it (REQ-017) | Direction 1; `RemainingTimeTest` |
| OFF gives uniform durations; a sentence end after ≥ 10 words holds 3.0x a plain word of the same book (REQ-011) | Directions 1–3; `RsvpTimingEngineTest`, `ReaderPauseStrengthTest` (mean republished on every rebuild) |
| Full stop after a 3-word span holds less than after 12; emphasis 1.5x unaffected by span | Direction 2; `RsvpTimingEngineTest`, `TokenizerTest` (span) |
| 20-word unpunctuated sentence gets ≥ 1 breath hold; ≤ 7 plain words never; Spanish equivalent gets one; OFF removes them; a hold at word 8 leaves the full stop at 3.0x | Direction 3; `TokenizerTest`, `RsvpTimingEngineTest` |
| Corpus fixtures (EN and ES, test-only, ≥ 50,000 words each) at NORMAL/300: sentences of 4+ words p5 ≥ 0.88 and p95 ≤ 1.08; 60-word windows p5 ≥ 0.93 and p95 ≤ 1.07; min/p5/p50/p95/max recorded in the PR; breath holds fire on both corpora and never inside a run of ≤ 7 plain words | Directions 1–3; a corpus test beside `TimingOverRealBookTest` (thresholds from the addendum's p5/p95 with a small margin; the 37-token pipeline fixture cannot define a 60-word window and is heading-dense, so it is not the subject of this row) |
| `testDebugUnitTest`, `verifyRoborazziDebug`, `lint` pass; only the settings-preview goldens change, by the lengthened sample span | Direction 1 and the preview decision; hosted checks; `Reading` goldens are unaffected for the stated reason |
| `ContentPipelineVersion.CURRENT` unchanged; a baseline-stored position still resolves | Tokenizer note above; `ReaderPositionTest` or `TokenizerTest` |
| Phone_Mid_API36 with two pushed public-domain EPUBs (EN and ES, each long enough that the readout states minutes): remaining time at open equals words ÷ dial within one minute; each plays a chapter at 300 without stutter; clean `AndroidRuntime:E`; screencaps | Emulator smoke in the leaf's validation (procedure below) |
| Release notes entry | Direction 1 |

No orphan or overlapping outcome: the root is the only node.

## Validation and feedback

- Unit tests as mapped above, run with `./gradlew testDebugUnitTest`
  (JDK 21).
- **Corpus fixture (test-only).** Two public-domain plain texts, one
  English and one Spanish, at least 50,000 words each, under
  `app/src/test/resources` with their licence notice kept and the Project
  Gutenberg header and footer stripped; they are test bytes, never in the
  APK, which is the distinction `f71cdb9`/`a05ce4e` drew when the shipped
  samples were removed. A corpus test splits them into paragraphs on blank
  lines, runs the real `Tokenizer` and `WordClassifier`, and prints
  min/p5/p50/p95/max for sentences of 4+ words and for 60-word windows at
  NORMAL/300, so the PR records measured, not simulated, numbers. If a
  corpus falls outside the p5/p95 thresholds the implementer reports the
  figures on the PR and tunes only the three breath constants or the span
  constant before widening any threshold, and any widening is stated with
  the measured cause. The 37-token pipeline fixture keeps the identities it
  can prove: the budget identity, OFF uniformity, the 3-versus-12-word span
  comparison and the pipeline-version check.
- `./gradlew verifyRoborazziDebug` and `lint`; hosted checks on the PR.
- One emulator run on `Phone_Mid_API36` under the shared emulator lock
  (`mkdir ~/worktrees/fastReader/.emulator.lock`, pid inside, `rmdir` when
  done), with screencaps read by the lead, not inferred from exit codes.
  There is no bundled book, so the implementer downloads two public-domain
  EPUBs at run time (for example Project Gutenberg's `.epub.noimages`
  files, one English and one Spanish, each of at least 3,000 words so a
  dial of 300 reads as minutes), `adb push`es them into a folder on the
  device, adds that folder in the app's library, opens each book, and
  checks the time-remaining readout against words ÷ dial before playing a
  chapter. The EPUBs are not committed.
- Reviewer verifies in its own detached worktree and never writes into the
  lead's worktree or `docs/evidence/`.
- Feedback loop: the on-device feel of breath holds is the one criterion
  without a number behind it; the lead may tune the three breath constants
  within the root's acceptance envelope and records the chosen values and
  the reason in the PR.

## Assumptions and open questions

### Owner decision: WPM numbers readers already set

Decided by the owner on 2026-09-10 on the planning PR: **Option A — keep
the stored per-book WPM numbers unchanged; no migration.** Owner rationale:
this is a personal app with no current users, so the one-time rise of the
experienced average (a book stored at 300 was averaging about 245 and will
average 300) needs no rescale and no notice; the release notes still say
so. The options considered were A (keep numbers), B (one-time per-book
rescale on first open, needing a migration marker and showing an unfamiliar
number) and C (a one-time notice); the owner chose the simplest. No open
decision remains.

### Recorded assumptions

- The constants (span 10, breath thresholds 8/14, breath multiplier 1.4x)
  are starting values from the research addendum; the implementer tunes
  within the acceptance envelope and records the final values.
- The settings-preview sample is lengthened as stated in the direction; the
  golden re-record is intended.

## Satisfaction proof

Implementation work remains; no acceptance condition of the root holds at
the pinned baseline (pauses are additive in `RsvpTimingEngine`).

## Publication verification

- `cedagova-infra plan validate --path docs/plans/cedagova-fastReader-81 --phase publication-ready`: valid; classification LEAF, delivery DIRECT, 1 manifest row, 0 external prerequisites, 0 inherited predecessors, 0 retained outcomes.
- `cedagova-infra plan verify-graph --path docs/plans/cedagova-fastReader-81`: root #81 open with `Planning root`, `Planning plan` and `Planning kind: LEAF` lines, zero native sub-issues, zero blocked-by edges — the zero-child graph matches the manifest.
- Exact-head semantic-anchor approval is recorded in the native review on PR #82, not here.
