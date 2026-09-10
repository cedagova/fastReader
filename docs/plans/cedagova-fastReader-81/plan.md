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

## Preserved objective and boundaries

Root #81 (owner-authored, 2026-09-10) asks that the speed dial name the
reader's real average speed, pauses included, so that at one setting a
comma-rich paragraph and a long unpunctuated sentence feel like the same
speed, while the research pauses at clause, sentence and paragraph
boundaries stay. Its evidence is the research note
`docs/product-definitions/cedagova-fastReader-1/research-pacing.md` at
`cedagova/fastReader@3315309652c1935f0ece3e4dce13101ee5a9b4a5`
(commit "Research: make the set WPM the average experienced speed").

Boundaries preserved from the root: one PR in this repository; no change to
the RARE classifier, ramp shape, chunking or per-chapter normalization; no
migration of stored per-book WPM values; no second "effective WPM" readout;
cue rendering, patent issue #33 and the v1 definition document untouched.
The v1 acceptance for REQ-011 (sentence end holds ~3x a plain word; pause
strength OFF makes all words uniform), REQ-013 (ramp and re-orientation
hold) and REQ-017 (time remaining at current speed; doubling speed halves it)
remains in force; the root changes only what the dial's number means.

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
condition of the root holds today. Not `NEEDS_DECISION`: the owner's root
fixes the product direction and records the one assumption that touches
existing readers (stored WPM numbers keep their value) with its rationale;
the remaining choices are constants and tokenizer rules that are reversible
and testable.

## Current-state evidence

At the baseline:

- `app/src/main/java/com/cedagova/fastreader/timing/RsvpTimingEngine.kt`:
  `duration = word × hold + word × (pauseMultiplier − 1) × pauseStrength`,
  with `word = (60000 / wpm) / rampSpeedFraction`. Pauses are additive; the
  engine is a pure function of `(token, settings, state)` and never sees the
  book.
- `.../timing/TimingSettings.kt`: `RsvpTiming` holds every constant with a
  research trace (sentence 3.0x, clause 2.0x, paragraph 3.5x, heading 4.0x,
  emphasis 1.5x, ramp 0.8 over 20 s, re-orientation 3x); `PauseStrength`
  scales only the extra part of a pause; `TimingSettings(wpm, pauseStrength,
  rampEnabled)` is the value the engine reads.
- `.../reader/RemainingTime.kt`: `RemainingTimeIndex.build` already sweeps
  the book once at open, on the parsing dispatcher, and stores a suffix sum
  of per-token multipliers measured through the engine; `suffix[0]` divided
  by the token count is the book's mean multiplier at that pause strength.
  `ReaderUiState` rebuilds it on pause-strength change.
- `.../content/Tokenizer.kt`: punctuation is attributed to the preceding
  word as the strongest `Boundary` in the following run (`NONE < CLAUSE <
  SENTENCE < PARAGRAPH < HEADING`); word classes come from
  `WordClassifier` (`LONG` > 11 chars, `RARE` ≥ 8 chars and unique in book).
  Nothing records the distance from the previous boundary.
- `.../settings/ui/SettingsPreview.kt` derives its sample pacing from
  `RsvpTimingEngine.plainWordMillis` with the same settings the reader uses.
- Tests: `RsvpTimingEngineTest`, `TimingScenarios`, `TimingOverRealBookTest`
  (a real EPUB fixture), `TokenizerTest`, `RemainingTimeTest`,
  `ReaderPauseStrengthTest`; goldens under `app/screenshots/`.
- Research note measurements (two Gutenberg books, app multipliers): mean
  multiplier 1.18–1.23, so a dial of 300 averages 244–255 WPM; per-sentence
  speed p5 0.53–0.63 and p95 0.89–0.91 of the dial. With normalization,
  span-proportional pauses and breath holds: sentences 0.90–1.04, 60-word
  windows 0.96–1.04, plain-word burst 1.10–1.13x of the dial.

## Selected implementation direction

One PR on `main`, three behaviours landed as ordered commits so each can be
read and reverted on its own:

1. **Budget normalization.** The per-book mean multiplier for the current
   pause strength is computed where the remaining-time index is built and
   carried into the timing settings the session hands the engine (a new
   settings value, default 1.0, so the engine stays pure and a book still
   parsing streams at the un-normalized rate until the index arrives). The
   engine divides the target word duration by it. The remaining-time index
   is read in the same normalized units, which makes time remaining at the
   first token exactly `tokens / wpm`. The ramp and the re-orientation hold
   keep multiplying the (now normalized) base and stay outside the mean, as
   `estimatedMillis` already excludes them. `PauseStrength.OFF` yields mean
   1.0 and unchanged behaviour. The settings preview feeds its own sample's
   mean through the same value so "300" previews at the speed it reads at.
2. **Span-proportional pauses.** The tokenizer records, per token, the
   number of words since the previous boundary token of `CLAUSE` or stronger
   (a span). The engine scales the extra part of a `CLAUSE`, `SENTENCE` or
   `PARAGRAPH` pause by `min(1, span / SPAN_FULL_PAUSE_WORDS)` with the
   constant around 10; `HEADING` keeps its full value; emphasis multipliers
   are not span-scaled; `max(boundary, emphasis)` and the abbreviation
   exemption are unchanged.
3. **Breath holds.** The tokenizer marks a new mild boundary level (below
   `CLAUSE`) on the word before a conjunction or relative pronoun once about
   8 plain words have run since the last boundary, and unconditionally after
   about 14; English and Spanish word lists live beside the tokenizer. The
   engine maps it to a multiplier around 1.4x through the same
   pause-strength scaler, so OFF removes it and the span counter resets on
   it. Skip markers, headings and abbreviations are untouched.

All new constants are stated once in `RsvpTiming` with a research trace to
`research-pacing.md` and an arithmetic unit test (AD-5). Because the mean is
measured through the engine, any later constant change flows into the
normalization automatically. The KDoc formula in `RsvpTimingEngine` and the
next version's release notes record that the dial now names the average.

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
| Whole-book steady-state time equals `tokens × 60000 / wpm` within 0.5% at SUBTLE, NORMAL, STRONG and 100/250/1000 WPM | Direction 1; `TimingOverRealBookTest` |
| Time remaining at the first token equals `tokens / wpm` within 1%; doubling speed halves it (REQ-017) | Direction 1; `RemainingTimeTest` |
| OFF gives uniform durations; a sentence end after ≥ 10 words holds 3.0x a plain word of the same book (REQ-011) | Directions 1–2; `RsvpTimingEngineTest`, `ReaderPauseStrengthTest` |
| Full stop after a 3-word span holds less than after 12; emphasis 1.5x unaffected by span | Direction 2; `RsvpTimingEngineTest`, `TokenizerTest` (span) |
| 20-word unpunctuated sentence gets ≥ 1 breath hold; ≤ 7 plain words never; Spanish equivalent gets one; OFF removes them | Direction 3; `TokenizerTest`, `RsvpTimingEngineTest` |
| Real-book fixture at NORMAL/300: 60-word windows within 0.85–1.15x, sentences of 4+ words within 0.80–1.20x, p5/p50/p95 recorded in the PR | Directions 1–3; `TimingOverRealBookTest` |
| `testDebugUnitTest`, `verifyRoborazziDebug`, `lint` pass; re-recorded goldens justified only by the settings preview | Direction 1 (preview); hosted checks |
| Phone_Mid_API36: remaining time at open equals words ÷ dial; EN and ES samples play a chapter at 300 without stutter; clean `AndroidRuntime:E`; screencaps | Emulator smoke in the leaf's validation |
| Release notes entry | Direction 1 |

No orphan or overlapping outcome: the root is the only node.

## Validation and feedback

- Unit tests as mapped above, run with `./gradlew testDebugUnitTest`
  (JDK 21). The per-sentence and per-window figures come from a test over
  the real-book fixture that prints them, so the PR records measured, not
  simulated, numbers.
- `./gradlew verifyRoborazziDebug` and `lint`; hosted checks on the PR.
- One emulator run on `Phone_Mid_API36` under the shared emulator lock
  (`mkdir ~/worktrees/fastReader/.emulator.lock`, pid inside, `rmdir` when
  done), with screencaps read by the lead, not inferred from exit codes.
- Reviewer verifies in its own detached worktree and never writes into the
  lead's worktree or `docs/evidence/`.
- Feedback loop: the on-device feel of breath holds is the one criterion
  without a number behind it; the lead may tune the three breath constants
  within the root's acceptance envelope and records the chosen values and
  the reason in the PR.

## Assumptions and open questions

None open. Recorded assumptions, all stated in the root by the owner:

- Stored per-book WPM numbers keep their value; a reader who was at 300
  now averages 300 instead of about 245 and turns the dial down if that is
  too fast. The release notes say so. The alternative (a one-time per-book
  rescale on first open) needs a migration marker and shows an unfamiliar
  number, and is out of scope. Reversible by the reader at the dial; the
  owner may veto before implementation starts.
- The constants (span 10, breath thresholds 8/14, breath multiplier 1.4x)
  are starting values from the research simulation; the implementer tunes
  within the acceptance envelope and records the final values.
- The mean multiplier of a book still parsing is 1.0 until the index is
  built; the root accepts a discontinuity of at most one word.

## Satisfaction proof

Implementation work remains; no acceptance condition of the root holds at
the pinned baseline (pauses are additive in `RsvpTimingEngine`).

## Publication verification

- `cedagova-infra plan validate --path docs/plans/cedagova-fastReader-81 --phase publication-ready`: valid; classification LEAF, delivery DIRECT, 1 manifest row, 0 external prerequisites, 0 inherited predecessors, 0 retained outcomes.
- `cedagova-infra plan verify-graph --path docs/plans/cedagova-fastReader-81`: root #81 open with `Planning root`, `Planning plan` and `Planning kind: LEAF` lines, zero native sub-issues, zero blocked-by edges — the zero-child graph matches the manifest.
- Exact-head semantic-anchor approval is recorded in the native review on PR #82, not here.
