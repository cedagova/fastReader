# Increment 004 device evidence (`Phone_Mid_API36`)

What the emulator settled for effort #109, and — just as important — what it
could not. Nothing here contains a token, an account value or a secret.

## The run

`Phone_Mid_API36`, Android 16 (API 36), 1080 x 2400. Installed APK verified
against the build: `md5 056205b44e9823930b49f248f9a0391e` on both sides
(`adb shell md5sum $(adb shell pm path com.cedagova.fastreader)` and the local
`app-debug.apk`). Version on screen: **1.7.0 (build 9)** — AD-28, unbumped.

| File | What it shows |
| --- | --- |
| `leaf-121-01-signed-out-shelf-phone-mid-api36.png` | The shelf signed out: one device row, status line `0% read`. One number, no account half — D4's "the signed-out shelf is v1.6.0's" and `accountPercentAhead == null`. |
| `leaf-121-02-reader-no-offer-phone-mid-api36.png` | The same book open in the reader: **no resume banner**. A device book with no account behind it is offered nothing, which is REQ-512's gate seen on a real device rather than in a fake. |
| `leaf-121-03-privacy-sentence-4-phone-mid-api36.png` | Settings → What stays on this device, sentence 4 as the app actually renders it: "…how far through it you are and which chapter you are in … it also asks for the place another device left in those books, so it can offer to take you there." |
| `leaf-121-04-privacy-sentence-7-phone-mid-api36.png` | Sentence 7: "…your place in a book leaves this device only for a book your account holds and only as the chapter and how far through it you are, and the exact word you are on, your reading speed, your other settings and any crash report stay on this device and are never sent." |
| `leaf-121-05-privacy-spanish-sentence-4-phone-mid-api36.png` | The Spanish twin of sentence 4, with the app's per-app locale set to `es-ES`. |
| `leaf-121-06-privacy-spanish-sentence-7-phone-mid-api36.png` | The Spanish twin of sentence 7. |
| `leaf-121-device-run-phone-mid-api36.txt` | `adb logcat -d -s AndroidRuntime:E` for the whole session — **empty** — and the tail of the app's own process, which includes `No session found in storage` (the run really was signed out). |

The English and Spanish statement captures are here rather than left to
`PrivacyStatementTest` because that test compares *strings and files*. These
are the only proof that the words the four held-equal copies agree on are the
words the shipped app puts on a screen, in both languages.

## What this run could NOT settle

The resume offer's own device behaviour needs a signed-in stage account and
reader-web as the second device. The owner was not available for this run and
signing in is theirs to do, so **no stage claim is made here**. The offer is
proven by goldens (`app/screenshots/reader_resume_offer*.png`), by
`ReaderResumeOfferTest` at the state machine, by `ReaderResumeOfferNoticeTest`
for the accessibility tree, and by `CatalogPositionsTest` for which positions
are worth offering at all. The stage half is in the PR's pending-owner-test
list and in `.delivery/owner-test-list-109.md`.
