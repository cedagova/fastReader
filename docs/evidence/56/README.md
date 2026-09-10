# LEAF607 (#56) — the v1.1.0 → v1.2.0 update proof

Everything here comes from one session on `Phone_Mid_API36` (Android 16 / API
36, `sdk_gphone64_arm64`, 1080x2400), and from **two real APKs**:

| Side | Artifact |
| --- | --- |
| before | **the published v1.1.0**, downloaded from its GitHub Release — `fastReader-1.1.0.apk`, 1,778,275 bytes, sha256 `de9c8af7df620f81c66e2caf7d9ff7dd367052d171d35c0b22fc72c2ba5655d2` |
| after | a **release-signed local build** of this branch's parent — `fastReader-1.2.0.apk`, 2,047,816 bytes, sha256 `e0d1809d3d9ce45a45d1e362555e2f6cb38972fedf9bc593c25c5cb1c5ef0e0d`, produced by `scripts/release.sh` **without** `--publish` |

The after side is not the published v1.2.0, because there is no published
v1.2.0: the collector is unmerged and publishing is the owner's action. It has
to be release-signed rather than debug, because the published v1.1.0 carries
the release certificate and a debug APK cannot install over it
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Same key, same gates, same bytes the
publish would upload — see the PR's "What is not proven yet".

## The files

| File | What it shows |
| --- | --- |
| `01-before-library-v1.1.0-…png` | v1.1.0 seeded: A Second Book 0 %, The Lighthouse Keeper 50 %. **No Sort control** — the library has one fixed order. |
| `02-before-reader-v1.1.0-…png` | Paused on **He**, violet focus letter, shifted off centre, Chapter Two: The Boat 2 of 3, 50 % read, 250 WPM. |
| `03-before-settings-cues-v1.1.0-…png` | Dark, Large, Highlight letter on, **Violet**, **Fixed focus letter on**, Guide marks on. |
| `04-before-settings-rhythm-version-v1.1.0-…png` | Pause strength **Strong**, and `Version 1.1.0 (build 3)`. **No Chapters section.** |
| `05-after-library-v1.2.0-…png` | The same two books at the same 50 % and 0 %, now **`Sort: Recently read`** and in that order. |
| `06-after-reader-v1.2.0-…png` | The same word, letter, chapter, percentage and speed, one minute later. |
| `07-after-settings-cues-v1.2.0-…png` | Every cue setting still where v1.1.0 left it. |
| `08-after-settings-chapters-v1.2.0-…png` | Strong still selected; the new **Chapters → Pause at chapters** row is **on**; `Version 1.2.0 (build 4)`. |
| `09-privacy-english-v1.2.0-…png` | The shipping statement, with the crash-report sentence REQ-303 was missing. |
| `10-privacy-spanish-v1.2.0-…png` | The same statement in Spanish — the copy no gate holds, so it is checked here by eye and by string comparison. |
| `catalog-before-v1.1.0-…json` | The stored catalog under v1.1.0: `schemaVersion` **4**. |
| `catalog-after-v1.2.0-…json` | The same catalog after one v1.2.0 load: `schemaVersion` **7**. |
| `update-in-place-package-state-…txt` | `dumpsys package` either side: unchanged `firstInstallTime`, moved `lastUpdateTime`, new `codePath`, same signer. |

The catalogs were read with `adb root` rather than `run-as`, because both
builds are release builds and neither is debuggable.

## What the run establishes

**It is an update, not a reinstall.** `firstInstallTime` is the same value on
both sides while `lastUpdateTime` moves and `codePath` changes, and the signer
is byte-identical. Nothing was uninstalled and no data was cleared.

**The whole v1.1.0 → v1.2.0 migration chain runs in one load.** The stored
catalog goes from `schemaVersion` **4** straight to **7**, walking 4 → 5
(#51's chapter pause), 5 → 6 (#52's library order) and 6 → 7 (#62's structural
fingerprint) in a single open. #62 proved 4 → 7 resumes; this is the same thing
with a reader looking at it.

**Nothing the reader chose moved.** Every settings field is identical across
the two catalogs — `DARK`, `LARGE`, highlight on, focus alignment on, `VIOLET`,
guide marks on, `STRONG` — as are both book ids, `lastReadBookId`, and the
reading state's `tokenIndex` 1042, `progressFraction` 0.49952108 and `wpm` 250.

The two reader captures make the same point without a JSON file: they differ in
**42 of 2400 pixel rows**, and every one of those rows is either the status bar
(rows 14–47: the clock going 9:25 → 9:26) or the gesture bar (rows 2364–2373).
Every pixel the app itself draws is unchanged.

**The two new settings arrive at their defaults, not as blanks.** The after
catalog gains exactly two fields, `"chapterPauseEnabled": true` and
`"libraryOrder": "RECENTLY_READ"` — which is REQ-208's second clause, and what
the Chapters row and the `Sort: Recently read` button show on screen.

`libraryOrder` is worth one more sentence, because a default can look right by
accident. The books were added Lighthouse-first and only Lighthouse was read,
so title order and recently-added order both put *A Second Book* first — which
is exactly what v1.1.0 shows. After the update the list is *The Lighthouse
Keeper* first. That ordering is reachable only from "recently read", so the
default is doing real work rather than agreeing with the old order by luck.

**#62's fingerprint is written on the first open after the migration, not
during it.** The migrated reading state carries a new
`"structuralFingerprint": "zipdir1:bcd1a5bd…"` beside the unchanged
`bookDigest`, which is AD-18's write-path rule: the migration installs the
default and the first directory open fills it in. A book only ever gains
protection.

## What this evidence does not cover

The publish. There is no `v1.2.0` tag, no GitHub Release and no published
asset, so the four publish-only gates in `scripts/release.sh` — clean tree and
tag target, tag does not exist, highest published version, and the
unauthenticated re-download of the uploaded asset — did not run, and the
"version equals the tag" comparison has nothing to compare against. The PR
lists these line by line.
