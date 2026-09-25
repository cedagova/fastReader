# Pacing corpus (test-only)

Two public-domain novels, one per language the app supports, used by
`PacingCorpusTest` to measure the timing engine over book-sized real prose:
per-sentence and per-window speed percentiles, breath-hold placement, and the
whole-book budget identity (#81, `docs/product-definitions/cedagova-fastReader-1/research-pacing.md`).

| File | Work | Source | Words |
| --- | --- | --- | --- |
| `en-pride-and-prejudice.txt` | *Pride and Prejudice*, Jane Austen (1813) | Project Gutenberg eBook #1342, https://www.gutenberg.org/ebooks/1342 | ~127,000 |
| `es-misericordia.txt` | *Misericordia*, Benito Pérez Galdós (1897) | Project Gutenberg eBook #21831, https://www.gutenberg.org/ebooks/21831 | ~84,000 |

Both texts are in the public domain in the United States. They were
downloaded as Project Gutenberg plain text and reduced to the body of the
work: the Project Gutenberg header and footer (the license text and
distribution notes) were removed and runs of blank lines collapsed, nothing
else was edited. The Project Gutenberg License applies to the trademark, not
to the underlying public-domain works: see
https://www.gutenberg.org/policy/license.html.

These files are **test resources only**. They live under `app/src/test` and
are never packaged into the APK; the shipped app bundles no books (the
sample texts were removed in 1.3.0).
