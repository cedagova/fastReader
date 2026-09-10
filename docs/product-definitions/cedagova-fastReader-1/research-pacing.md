# Research: making the set WPM the *average* experienced speed (2026-09-10)

Follow-up to [research-rsvp.md](research-rsvp.md). Problem raised while
reading with v1.3.0: the pause multipliers (REQ-011) are *added on top of*
the set speed, so the number on the dial is the burst speed of plain words,
not the speed the reader experiences. A reader who is comfortable at "300"
in a comma-rich paragraph is then hit by a long unpunctuated sentence that
really runs at 300, and a reader comfortable in plain prose finds a choppy
dialogue passage crawling. Both are the same defect: the dial names the
wrong quantity.

## Bottom line

- **Known solution, and Spritz patented the mechanism:** compute the average
  relative duration over the text, divide the base word time by it, and the
  selected WPM becomes the true average including every pause. Nothing we
  ship needs to change *which* pauses exist — only what the number means.
- **Global normalization alone is not enough.** Simulated on two full books
  it fixes the average exactly but leaves individual sentences between
  0.6x and 1.1x of the dial. The swing the reader feels is *local*.
- **Two evidence-backed levers shrink the local swing** to roughly 0.9x–1.04x
  per sentence: (a) make a pause proportional to the span it closes
  (Spritz already scales the sentence-end pause with sentence length), and
  (b) insert mild "breath" holds inside long unpunctuated runs, which is
  what text-to-speech systems do with phrase-break prediction and what
  Castelhano & Muter did by hand in their second RSVP experiment.
- **Keep the pauses.** No source argues for dropping them; the research
  case for sentence wrap-up time is unchanged. The recommendation
  redistributes time inside a fixed budget rather than adding it.

## 1. What the current engine does

[RsvpTimingEngine](../../../app/src/main/java/com/cedagova/fastreader/timing/RsvpTimingEngine.kt):

```
word     = 60000 / wpm
duration = word * hold + word * (pauseMultiplier - 1) * pauseStrength
```

Every pause is extra time. The set WPM is the fastest the stream ever goes,
and the true average is lower by the mean multiplier of the book. This is
the same design as Squirt, Reedy and most open-source readers — it is the
*default* design, not a considered one.

Measured on two public-domain books with the app's own multipliers
(sentence 3.0x, clause 2.0x, paragraph 3.5x, long/number/caps 1.5x; the
RARE class was not simulated):

| Book | Mean multiplier | Dial 300 → real average | Per-sentence speed, p5 / median / p95 (fraction of dial) |
|---|---|---|---|
| Pride and Prejudice (fiction, dialogue-heavy) | 1.23 | 244 WPM | 0.63 / 0.81 / 0.89 |
| On the Origin of Species (non-fiction, long sentences) | 1.18 | 255 WPM | 0.53 / 0.86 / 0.91 |

Plain-word runs between pauses: median 5–6 words, p95 14–17, max 44–49.
Those long runs are the "volley"; the p5 sentences are the "crawl".

## 2. Prior art

### Spritz — normalize so the dial is the average (patented mechanism)

US 2014/0016867 A1 (Maurer, Klein, Waldman), Fig. 6–7:

- Word multipliers by length: >13 chars 1.6x, >7 chars 1.3x, else 1.0x.
- A blank element after each sentence whose multiplier depends on
  **sentence length**: >22 words 3.3x, >7 words 2.2x, else 1.0x.
- Step 718 computes the *average word relative duration* (awdr) over the
  text; step 719 divides the update time derived from the WPM setting by
  awdr to get the default update time; step 720 shows each element for
  `multiplier × default update time`.

So in Spritz the WPM setting is, by construction, the average including
pauses, and the sentence-end pause is already proportional to what it has
to wrap up. This is the closest thing to an industry reference, and the
mechanism (not the redicle trade dress) is what we want. The patent covers
the specific combination; a normalization by mean multiplier is ordinary
arithmetic and also appears in Sprint Reader (below).

### Sprint Reader — character-budget normalization

`engine.js` word-length algorithm:

```
totalDuration = (totalSegments / WPM) * 60000
unitTime      = totalDuration / totalLength
duration      = unitTime * text.length
```

The whole text's time budget is fixed by the WPM, then divided by
characters, so long words get more time *paid for* by short words. Its
punctuation delays, however, are still additive post-delays outside the
budget.

### Reedy, Squirt, QuickReader and most GitHub readers — additive

- Reedy `Sequencer.js`: `res = 60000/wpm; res *= complexity` with
  `SENTENCE_END_COMPL = 2.1`; no averaging.
- Squirt: `intervalMs = 60000/wpm` times 1.2 (short word), 1.5 (long),
  2 (comma), 3 (period), 3.5 (paragraph); no averaging.
- Several newer readers instead *display* an "actual WPM" readout below the
  configured one and document that it "will be somewhat lower than
  configured, by design". That is honest but does not fix the swing.

### Castelhano & Muter 2001 — the pauses, and the caveat

Experiment 1 ran plain words at 230 ms (~260 WPM) and punctuated words at
460 ms, additive. Punctuation pauses were the only modification readers
rated as valued. The authors' own caveat in the discussion: the preference
"may have been because it reduced the within-sentence presentation rate,
and so allotted extra processing time" — i.e. their additive pauses were
partly a hidden speed reduction, exactly the confound our dial has.

Experiment 2 ("modified RSVP") is the direct precedent for breath holds:
passages were parsed by hand and words that *begin a new clause without
punctuation* ("singularity", "phenomena" in their example) plus proposition
connectives (`if`, `because`, `after`, `although`) were shown for twice the
normal duration.

### Text-to-speech — phrase breaks where there is no punctuation

Speech synthesizers face the same problem: a long sentence with no commas
still needs pauses, or it sounds like a volley. The standard solution is
phrase-break prediction from part-of-speech sequences (Taylor & Black 1998,
F-score 78% on breaks that humans place without punctuation), with later
work adding distance-to-punctuation features. Human readers-aloud insert
respiratory pauses at word transitions with no punctuation at roughly
breath-group length. For an RSVP reader the cheap proxy is: after N plain
words, take the break at the next conjunction or relative pronoun, or force
one at a hard cap.

### Reading research on wrap-up time

Just & Carpenter 1980: gaze duration rises with word length, word rarity,
and at clause and sentence ends (wrap-up); those variables explain ~72% of
per-word variance. The wrap-up cost scales with how much has to be
integrated, which is the justification for span-proportional pauses rather
than a flat 3.0x after every full stop, including the one after "Yes."

## 3. Candidate designs, simulated

Same two books, same multipliers. "Speed" is words ÷ total multiplier, as
a fraction of the dial; 1.00 means the passage runs exactly at the dial.

| Strategy | Mean | Per-sentence p5 / p50 / p95 | Per-60-word window p5 / p95 | Plain-word burst |
|---|---|---|---|---|
| **Current (additive)** — P&P | 1.23 | 0.63 / 0.81 / 0.89 | 0.73 / 0.90 | 1.00x |
| **Current (additive)** — Origin | 1.18 | 0.53 / 0.86 / 0.91 | 0.79 / 0.92 | 1.00x |
| Global normalization only — P&P | 1.00 | 0.78 / 0.99 / 1.10 | 0.90 / 1.10 | 1.23x |
| Global normalization only — Origin | 1.00 | 0.63 / 1.01 / 1.07 | 0.93 / 1.08 | 1.18x |
| Span-proportional pauses + global — P&P | 1.00 | 0.90 / 0.99 / 1.04 | 0.96 / 1.04 | 1.13x |
| Span-proportional pauses + global — Origin | 1.00 | 0.93 / 0.99 / 1.03 | 0.97 / 1.03 | 1.10x |
| + breath holds — P&P | 1.00 | 0.91 / 0.99 / 1.03 | 0.96 / 1.04 | 1.13x |
| + breath holds — Origin | 1.00 | 0.93 / 0.99 / 1.02 | 0.97 / 1.03 | 1.11x |

Definitions used in the simulation:

- *Global normalization*: every duration divided by the book's mean
  multiplier (Spritz step 718–719).
- *Span-proportional pause*: the extra part of a pause is scaled by
  `min(1, wordsSinceLastPause / 10)`, so a comma after three words gets
  30% of the clause pause and a full stop after twelve words gets all of
  it. Caps at the research value; never exceeds it.
- *Breath hold*: 1.4x on the word before a conjunction/relative pronoun
  once 8 plain words have run, or unconditionally after 14.

Readings:

- Global normalization fixes the number on the dial and nothing else. The
  p5 sentence in Origin still runs at 0.63x.
- Span-proportional pauses do most of the work on the local swing: the
  choppy sentences stop crawling because many small pauses no longer cost
  a full 2.0x each. The burst speed of plain words also drops from 1.23x to
  1.13x of the dial because less total pause has to be paid for.
- Breath holds barely move the aggregate numbers (the volley sentences
  were already near 1.0x on average). Their value is subjective — a
  44-word run gets three brief holds instead of none — and is the one
  lever here without a number behind it. Ship it behind the existing pause
  strength setting, or test it on device before deciding.

## 4. Recommendation

1. **Redefine WPM as the average.** Precompute the mean multiplier per book
   at open (the suffix array in
   [RemainingTime.kt](../../../app/src/main/java/com/cedagova/fastreader/reader/RemainingTime.kt)
   already holds this sum at index 0) and divide the base word time by it.
   Consequences that fall out for free: time remaining becomes exactly
   `words / wpm`, the ramp still works because it multiplies the base, and
   a pause-strength change re-normalizes so changing the strength never
   changes how long the book takes — only how the time is distributed.
   Pause strength OFF is unchanged (mean multiplier 1.0).
2. **Scale pauses by the span they close**, capped at today's research
   values. Track words since the last boundary in the tokenizer or the
   engine; the multiplier becomes `1 + (m − 1) × min(1, span / 10)`. This
   is the Spritz sentence-length rule generalized to clauses.
3. **Add breath holds in long runs** at conjunction / relative-pronoun
   boundaries after ~8 plain words, forced after ~14. Treat as a new mild
   `Boundary` level or a word class, so it flows through the same
   pause-strength scaler.
4. **Do not** add a second "effective WPM" readout. Once the dial is the
   average, there is one number and it is honest.
5. Chapter-level rather than book-level normalization is not worth it: the
   per-60-word window after step 2 is already within ±4%, and chapter
   boundaries would make the dial's meaning jump mid-book.

Open question for the definition: REQ-011's acceptance ("OFF makes all
words uniform") still holds; REQ-017's "time remaining at current speed"
becomes simpler. The 2026-09-01 decision-log entry adopting the research
multipliers should gain a line recording that they are now *relative
weights inside a fixed budget*, not additions to it.

## Sources

- Spritz patent US 2014/0016867 A1, "Methods and systems for displaying
  text using RSVP": https://patents.google.com/patent/US20140016867A1/en
- Castelhano & Muter 2001, *Optimizing the reading of electronic text using
  rapid serial visual presentation*, Behaviour & Information Technology
  20(4): https://static1.squarespace.com/static/5b316a4de17ba35691dabf1f/t/5cd46ede24a694d84bee6e2a/1557425890280/CastelhanoMuter2001.pdf
- Benedetto et al. 2015, *RSVP in reading: the case of Spritz*, Computers in
  Human Behavior 45: https://www.sciencedirect.com/science/article/abs/pii/S0747563214007663
- Just & Carpenter 1980, *A theory of reading: from eye fixations to
  comprehension*: https://www.semanticscholar.org/paper/7219ac5424180d57c13da42a811a764bd9698903
- Taylor & Black 1998, *Assigning phrase breaks from part-of-speech
  sequences*: https://www.sciencedirect.com/science/article/abs/pii/S0885230898900419
- Sprint Reader source (`src/engine.js`): https://github.com/anthonynosek/sprint-reader-chrome
- Reedy source (`js/content/Sequencer.js`): https://github.com/olegcherr/Reedy-for-Chrome
- Squirt source (`squirt.js`): https://github.com/cameron/squirt
- Simulation texts: Project Gutenberg #1342 and #1228; script in the
  appendix below.

## Appendix: simulation script

Approximates the tokenizer (paragraph on blank line, sentence on `.!?`,
clause on `,;:` and dashes, emphasis on >11 chars / digits / all-caps).
Run with `python3 sim.py` after downloading the two Gutenberg texts as
`pp.txt` and `origin.txt`.

```python
import re, statistics as st
SENT, CLAUSE, PARA, EMPH = 3.0, 2.0, 3.5, 1.5
CONJ = set("and but or nor yet so because although though while whereas if "
           "unless until when whenever where wherever after before since as "
           "that which who whom whose".split())

def load(p):
    t = open(p, encoding='utf-8', errors='ignore').read()
    s, e = t.find('*** START'), t.find('*** END')
    return t[t.find('\n', s) + 1:e] if s > 0 and e > 0 else t

def tokens(text):
    out = []
    for para in re.split(r'\n\s*\n', text):
        ws = para.split()
        for i, w in enumerate(ws):
            core = w.strip('“”"\'()[]‘’'); last = core[-1:] if core else ''
            m = 1.0
            if i == len(ws) - 1: m = PARA
            elif last in '.!?': m = SENT
            elif last in ',;:' or core.endswith('—') or core.endswith('--'): m = CLAUSE
            bare = re.sub(r'[^A-Za-z0-9]', '', core)
            if (len(bare) > 11 or bare.isdigit() or (bare.isupper() and len(bare) > 1)) and m < EMPH: m = EMPH
            out.append((w, m, last in '.!?' or i == len(ws) - 1))
    return out

def strategies(tk):
    ms = [m for _, m, _ in tk]; words = [w for w, _, _ in tk]; out = {'current': ms}
    avg = sum(ms) / len(ms); out['global'] = [m / avg for m in ms]
    prop, run = [], 0
    for w, m, end in tk:
        run += 1
        if m > 1.0: prop.append(1.0 + (m - 1.0) * min(1.0, run / 10.0)); run = 0
        else: prop.append(1.0)
    a = sum(prop) / len(prop); out['proportional+global'] = [m / a for m in prop]
    br, run = [], 0
    for i, (w, m, end) in enumerate(tk):
        base = prop[i]
        if base == 1.0:
            run += 1
            nxt = words[i + 1].strip('“"‘\'').lower() if i + 1 < len(words) else ''
            if (run >= 8 and nxt in CONJ) or run >= 14: base, run = 1.4, 0
        else: run = 0
        br.append(base)
    a = sum(br) / len(br); out['proportional+breath+global'] = [m / a for m in br]
    return out

def report(name, p):
    tk = tokens(load(p)); print(f"\n== {name}")
    for label, ms in strategies(tk).items():
        sents, cur = [], []
        for (w, m, end), x in zip(tk, ms):
            cur.append(x)
            if end: sents.append(cur); cur = []
        sr = [len(s) / sum(s) for s in sents if len(s) >= 4]; q = st.quantiles(sr, n=20)
        win = 60; wr = [win / sum(ms[i:i + win]) for i in range(0, len(ms) - win, win)]; qw = st.quantiles(wr, n=20)
        print(f" {label:28s} mean={sum(ms)/len(ms):.2f} sentence p5/p50/p95={q[0]:.2f}/{q[9]:.2f}/{q[18]:.2f} "
              f"60w p5/p95={qw[0]:.2f}/{qw[18]:.2f} burst={1/min(ms):.2f}x")

report("Pride and Prejudice", "pp.txt"); report("Origin of Species", "origin.txt")
```

## Addendum (2026-09-10): re-simulation under the plan's exact rule

Plan #81 fixes the rule more precisely than the script above: the span is
the number of words since the previous clause-or-stronger boundary token
(emphasis words and breath holds do not reset it); a breath hold is a word
class on its own counter (reset by a boundary or by a hold), combined with
the boundary pause by `max`, never a product. Same two books, same
constants (span full at 10 words, breath after 8 plain words at a
conjunction/relative or unconditionally after 14, breath 1.4x), globally
normalized:

| Book | Mean before normalization | Sentences of 4+ words: min / p5 / p50 / p95 / max | 60-word windows: min / p5 / p95 / max | Plain-word burst |
|---|---|---|---|---|
| Pride and Prejudice | 1.143 | 0.71 / 0.91 / 0.99 / 1.03 / 1.07 | 0.91 / 0.96 / 1.04 / 1.08 | 1.14x |
| On the Origin of Species | 1.122 | 0.67 / 0.93 / 1.00 / 1.02 / 1.05 | 0.77 / 0.97 / 1.03 / 1.06 | 1.12x |

The minimum sentences are short ones carrying several emphasis words, which
span-scaling cannot help; a universal per-sentence bound is therefore not a
sensible acceptance criterion, percentiles are.

```python
# sim3.py — plan #81 rule; reuses load/tokens from the script above and CONJ.
def plan_rule(tk, span_full=10, breath_min=8, breath_max=14, breath_mult=1.4):
    words = [w for w, _, _ in tk]; out = []; span = 0; run = 0
    for i, (w, m, end) in enumerate(tk):
        span += 1
        is_boundary = m in (CLAUSE, SENT, PARA); is_emph = (m == EMPH)
        b = 1.0 + (m - 1.0) * min(1.0, span / span_full) if is_boundary else 1.0
        e = EMPH if is_emph else 1.0
        if is_boundary: run = 0
        else:
            run += 1
            nxt = words[i + 1].strip('“"‘\'').lower() if i + 1 < len(words) else ''
            if (run >= breath_min and nxt in CONJ) or run >= breath_max:
                e = max(e, breath_mult); run = 0
        out.append(max(b, e))
        if is_boundary: span = 0
    avg = sum(out) / len(out)
    return [x / avg for x in out], avg
```
