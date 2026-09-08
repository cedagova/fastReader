# The bundled samples: what they are, and why they are legal to ship

FastReader ships two short texts inside the APK so that someone who has just
installed it has something to read before they have found an EPUB of their own
(REQ-109). This directory holds their sources; `scripts/build-sample-epubs.py`
turns them into the two committed assets under
`app/src/main/assets/samples/`.

## Provenance

| Sample | Asset | Title | Language | Origin |
| --- | --- | --- | --- | --- |
| English | `sample-en.epub` | *A Word at a Time* | `en` | Written for FastReader (2026). Not adapted from, translated from, or based on any existing work. |
| Spanish | `sample-es.epub` | *Una palabra a la vez* | `es` | The same story, written for FastReader in Spanish (2026). |

Neither text is an excerpt of a published book. Both were written for this
repository, which is why their provenance can be stated exactly rather than
traced through an intermediate transcription.

## Licence

Both texts are dedicated to the public domain under
[Creative Commons CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/):

> To the extent possible under law, the FastReader project has waived all
> copyright and related or neighbouring rights to *A Word at a Time* and
> *Una palabra a la vez*. These works are published from Spain.

CC0 is compatible with the repository's MIT licence and imposes no attribution
requirement on the app or on anyone who redistributes it. The dedication is
also stated inside each sample, as its second and final chapter ("About this
text" / "Sobre este texto"), so it travels with the text rather than only with
the repository — which is the requirement that the source be named in the UI.

### Why not a classic instead

A public-domain classic — an opening chapter of *Alice's Adventures in
Wonderland* or *Don Quijote* — was the obvious first choice and was rejected
deliberately. Shipping one means shipping a *transcription*, and a transcription
whose exact bytes could not be verified against an authoritative source in this
environment would have meant claiming a provenance that could not be proved.
"Do not ship text whose licence you cannot name" extends to text whose
transcription you cannot vouch for. An original text has no such gap: the
repository is the source.

## Rebuilding

```sh
python3 scripts/build-sample-epubs.py
```

The build is deterministic — fixed entry timestamps, fixed compression level —
so unchanged sources reproduce the committed assets byte for byte. The assets'
SHA-256 digests **are** the samples' book identities, pinned in
`app/src/main/java/com/cedagova/fastreader/content/BundledSample.kt`; nothing on
the open path ever computes them (AD-8). If you change a source file, rebuild,
run

```sh
./gradlew testDebugUnitTest --tests '*SampleAssetsTest'
```

and paste the digest the failure reports into `BundledSample.kt`. That test is
the reason a stale digest cannot reach `main`.

## Packaging

`app/build.gradle.kts` marks `epub` as a `noCompress` extension, so the two
assets are **stored** rather than deflated inside the APK. An EPUB is already a
deflated zip, so this costs essentially nothing in APK size, and it is what lets
`AssetManager.openFd` hand the reader a seekable view of the file — which keeps
the sample on the same central-directory read path as every other book (REQ-110)
instead of the whole-file streaming fallback.
