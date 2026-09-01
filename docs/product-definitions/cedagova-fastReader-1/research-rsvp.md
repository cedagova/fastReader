# Research: RSVP reading evidence and app conventions (2026-09-01)

Compiled by a research agent for the product definition of
https://github.com/cedagova/fastReader/issues/1. Findings distinguish
published research from app convention / vendor claims.

## Bottom line

The core RSVP marketing promise (2-3x speed at full comprehension) is not
supported by research - comprehension holds to roughly 300-400 WPM and
degrades beyond that, mainly because RSVP removes regressions (re-reading)
and sentence wrap-up time. The Spritz ORP red-pivot letter is a vendor claim
built on real but misapplied eye-movement research (Optimal Viewing
Position). The evidence-backed mitigations are exactly the features mature
apps converge on: punctuation/long-word pauses, sentence-end delays, easy
rewind, tap-to-pause-with-context, and gradual ramp-up.

## 1. Visual cues

- Real phenomenon: Optimal Viewing Position - words recognized fastest when
  fixated slightly left of center (O'Regan et al.).
- Spritz's claim that ORP alignment + red letter enables 1000 WPM has no
  published validation (Benedetto et al. 2015: claims "do not seem to be
  supported by any scientific evidence").
- No study shows the pivot letter or reticle by themselves improve
  comprehension vs plain centered RSVP. Alignment plausibly provides a
  stable fixation anchor - modest ergonomic benefit.
- Spritz's exact "redicle" trade dress is patented (US 8,903,174); avoid
  cloning it exactly.
- Open-source pivot index convention (Squirt): 0 for 1-char, 1 for 2-3
  chars, floor(len/2)-1 for >=4 chars.

## 2. Comprehension research

- Rayner, Schotter et al. 2016 (Psych. Science in the Public Interest): hard
  speed-accuracy trade-off; RSVP at normal speed comprehends fine, pushing
  speed costs comprehension and memory.
- Schotter, Tran & Rayner 2014: blocking re-reading drops comprehension on
  regression trials from ~75% to ~50% (chance). Regressions causally support
  comprehension - RSVP's biggest structural cost.
- Benedetto et al. 2015: Spritz vs traditional at matched speed - literal
  comprehension impaired, ~50% fewer blinks (fatigue), higher workload.
- Acklin & Papesh 2017: comprehension drops at app-advertised speeds
  (700/1000 WPM).
- Threshold: no comprehension difference at 250-350 WPM vs normal reading;
  significant losses above.
- Masson 1983 / Potter et al. 1980: sentence-end wrap-up pauses materially
  support comprehension.
- Chunking: multi-word chunks help only when segmented on phrase boundaries;
  blind word-count chunks hurt.

## 3. Timing heuristics (app convention, concrete)

Squirt.io constants (base = 60000/WPM ms per word):
- sentence end .!? -> 3.0x; comma/semicolon/colon/dash -> 2.0x
- paragraph break -> 3.5x
- short word (<4 chars) -> 1.2x; long word (>11 chars) -> 1.5x
- abbreviations exempt from sentence pause
- after jump/rewind -> 3x on first word (re-orientation)

Spritz: default 250 WPM, range ~100-1000. Reedy: gradual acceleration ramp,
smart slowing on punctuation and complex vocabulary; practical range
250-600 despite 3000 ceiling. Numbers/ALL-CAPS/rare words treated like long
words (~1.5x) by convention.

## 4. Expected feature set (converged across Reedy/Spritz/Librera/Outread)

- Tap-to-pause showing surrounding paragraph with current word highlighted
  (praised in Reedy; practical substitute for regressions)
- On-the-fly speed adjust during playback
- Rewind by sentence/paragraph; re-orientation pause after jumps
- Ramp-up to target speed
- Progress: percent + time-remaining at current WPM
- Chunk size 1-3 words (phrase-boundary only)
- Themes/dark mode, font size, pivot color toggle
- Continuous position persistence
- Complaints to avoid: subscriptions, TTS bugs, hardware-button conflicts

## 5. EPUB on Android (product-level)

- Readium kotlin-toolkit: maintained, safest EPUB 2/3 parse; heavier.
- epublib (unmaintained) / epub4j: lightweight, own the HTML->text step.
- Pitfalls: Adobe DRM unreadable (expectation-setting); RSVP needs plain
  text in reading order (flatten XHTML, policy for tables/images/figures,
  footnote link filtering); position must map back to spine item + offset;
  malformed EPUBs common - parse leniently.

## Recommended defaults (evidence-supported)

- Default 250 WPM; range 100-1000 in steps of 10-25; gentle warning zone
  above ~450 WPM.
- Ramp on by default: start ~80% of target, reach target over ~15-30 s;
  re-orientation pause (~3x) after any rewind/jump.
- Pause multipliers: sentence 3.0x, clause punctuation 2.0x, paragraph
  3.5x, heading full stop or >=4x, long/number/rare words 1.5x,
  abbreviations exempt.
- Pivot-aligned word + colored pivot letter ON by default, toggleable;
  avoid exact Spritz redicle trade dress.
- Chunk 1 word default; >1 only on phrase boundaries.
- Always-on mitigations: pause-context view, sentence rewind gesture,
  chapter auto-pause, continuous persistence.
- Do not promise ">600 WPM with full comprehension" anywhere in-product.

## Key sources

- https://journals.sagepub.com/doi/10.1177/1529100615623267 (Rayner et al. 2016)
- https://journals.sagepub.com/doi/10.1177/0956797614531148 (Schotter et al. 2014)
- https://www.sciencedirect.com/science/article/abs/pii/S0747563214007663 (Benedetto et al. 2015)
- https://scholarlypublishingcollective.org/uip/ajp/article-pdf/130/2/183/1878086/amerjpsyc.130.2.0183.pdf (Acklin & Papesh 2017)
- https://github.com/cameron/squirt , https://github.com/gleitz/OpenSpritz
- https://www.speedreadinglounge.com/reedy-app-android
- https://github.com/readium/kotlin-toolkit
- https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8903174 (Spritz patent)
