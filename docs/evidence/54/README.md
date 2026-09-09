# LEAF605 (#54) — crash report offered on the next launch

Everything here comes from one session on `Phone_Mid_API36` (Android 16 / API
36, `sdk_gphone64_arm64`, 1080x2400), running the debug APK built from
`leaf/54-crash-report` at **9e5f0f5** — the commit that adds the feature, which
is this file's parent. Wiped device, empty library, no books added.

The crash was induced with the debug-only activity:

```
adb shell am start -n com.cedagova.fastreader/com.cedagova.fastreader.debug.CrashInducerActivity
```

It throws `IllegalStateException("induced crash: could not read
/storage/emulated/0/Download/Rayuela - Julio Cortazar.epub")` caused by a
`FileNotFoundException` whose message is that same path. So the crash carries a
folder, a title, an author and a file name through both a message and a cause —
which is the leak REQ-207 is about, and the reason this run proves the redaction
rather than only the plumbing.

## The files

| File | What it shows |
| --- | --- |
| `01-offer-on-next-launch-phone-mid-api36.png` | The launch after the crash: the offer over the library, naming what the report holds, what it cannot hold, and what each answer does. Shown once. |
| `02-share-sheet-carries-the-report-phone-mid-api36.png` | "Share report" tapped. The system share sheet, with the platform's own preview of the text in the intent. Nothing has been sent: the reader has not picked an app. |
| `03-not-offered-again-after-declining-phone-mid-api36.png` | A cold launch after "Delete report" on a second induced crash. No offer, and `files/crash/` is empty. |
| `shared-report-phone-mid-api36.txt` | The complete text the share sheet says it is sharing, read back out of the system chooser's own view, not out of the app. |
| `androidruntime-fatal-phone-mid-api36.txt` | The platform's `FATAL EXCEPTION` for the induced crash. |

## What the run establishes

- **The offer appears once, and either answer ends it.** Sharing and declining
  both delete `files/crash/report.txt`; a cold relaunch after each shows the
  library with no dialog. The device's own `run-as ... ls files/crash` is empty
  in both cases.
- **The process still terminates.** `androidruntime-fatal-...txt` is the
  platform handler's log line, which only exists because the app's handler
  passed the throwable on instead of swallowing it. `pidof
  com.cedagova.fastreader` was empty afterwards, and a second induced crash in
  quick succession made the system show its own "FastReader keeps stopping"
  dialog — which it only does for a process that actually died.
- **The shared text is the stored report, byte for byte.** 3217 bytes in
  `files/crash/report.txt`; 3217 identical bytes in the chooser's preview of
  `EXTRA_TEXT`. `diff` between the two is empty.
- **The report carries the four facts and nothing of the reader's.** It names
  `Version: 1.1.0 (3)`, `Device: sdk_gphone64_arm64`, `Android: 16 (API 36)` and
  the full call chain down to `CrashInducerActivity.onCreate:37`. It contains
  none of `Rayuela`, `Julio`, `Cortazar`, `.epub`, `Download`, `storage`,
  `emulated`, `induced crash`, `No such file` — and no `/` or `\` at all, so it
  cannot spell a path of any kind. All three exception types survive; all three
  messages are gone.
- **A crash costs the reader nothing else.** After both crashes,
  `files/catalog/catalog.json` and `shared_prefs/launch-theme.xml` were intact
  and `files/crash/` held only the report.

## Reproducing it

```bash
adb shell am start -n com.cedagova.fastreader/com.cedagova.fastreader.debug.CrashInducerActivity
adb logcat -d -s AndroidRuntime:E                       # the crash was delivered
adb shell run-as com.cedagova.fastreader cat files/crash/report.txt
adb shell am start -n com.cedagova.fastreader/.MainActivity   # the offer
```
