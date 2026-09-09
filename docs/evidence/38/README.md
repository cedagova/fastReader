# Effort #38 — combined smoke on the final collector candidate

One emulator session on `Phone_Mid_API36` (Android 16, 1080p, locale `en-US`),
against a debug build of collector head
`fbe77bfa4fbc40039bd6126e8613f157bbe8b195` — every leaf #41–#49 integrated.
The app was **uninstalled first**, so this is a stranger's fresh install, not an
update. `adb logcat -d -s AndroidRuntime:E` was empty at the end of the session.

These captures exist to prove the leaves work *together* on one build. They do
not repeat the per-leaf acceptance evidence already committed under
`docs/evidence/41/` … `docs/evidence/49/`.

| Capture | What it proves | Leaves |
| --- | --- | --- |
| `first-launch-empty-library-phone-mid-api36.png` | First frame on a fresh install: correct light theme, both add paths explained, the sample offer present | #42, #45, #48 |
| `two-taps-to-a-playing-sample-phone-mid-api36.png` | Two taps from that empty library to a playing stream at 250 WPM | #48, #43 |
| `focused-mode-speed-375wpm-phone-mid-api36.png` | Long press hides the chrome; a vertical drag moved 250 → 375 WPM | #47 |
| `files-app-open-session-only-notice-phone-mid-api36.png` | An EPUB tapped in the Files app opens in FastReader with the session-only notice | #44, #43 |
| `library-empty-after-external-open-phone-mid-api36.png` | The library is still empty afterwards — the REQ-103 reality | #44 |
| `folder-removal-confirm-phone-mid-api36.png` | Folder removal names the count and promises no file is touched | #45 |
| `settings-version-updates-privacy-phone-mid-api36.png` | One screen carrying four leaves at once: sample entry, `Version 1.1.0 (build 3)`, Check for updates, and the five-sentence privacy statement including the session-only clause | #48, #49, #46, #44 |
| `uri-grants-after-external-open.txt` | `dumpsys activity permissions` taken live during that external open | #44 |

## The grant dump, in one line

The URI `com.android.externalstorage` handed to FastReader for
`Download/the-lighthouse-keeper.epub` is recorded as:

```
mode=0x3 owned=0x3 global=0x0 persistable=0x0 persisted=0x0
```

`persistable=0x0` is why the book cannot be kept. Reproduced here on the final
combined candidate; #44 established it first in `docs/evidence/44/`.
