# Increment 002 (v1.2.0) — combined smoke evidence

One device session on the **integrated collector candidate**, exercising the
leaves together rather than one at a time. Every capture here came from a debug
build of collector head **`0c32fd7a7dc3a6c508ebc4a6a5d0a3409e54dfdd`** — the
parent of the commit that adds these files, which contains only Markdown, PNGs
and this text file under `docs/`.

Device: `Phone_Mid_API36` (1080×2400 @420dpi, Android 16, API 36).
Installed build reported `versionCode=4 versionName=1.2.0 minSdk=26
targetSdk=37`. App language set to Spanish with
`cmd locale set-app-locales com.cedagova.fastreader --locales es-ES`.

## `combined-smoke-landscape-spanish-phone-mid-api36.png`

Four leaves visible in one frame: the interface is Spanish (#55), the reader is
in the **landscape layout with the controls beside the stream** and nothing
below it (#53), the stream is **playing across a chapter boundary** with "Pausar
en los capítulos" turned off (#51), and it is all running on the refreshed
toolchain at `targetSdk` 37 (#50). The v1 cue set is intact — the focus letter
is coloured and the guide marks are drawn (REQ-112, D1).

## `combined-smoke-crash-offer-spanish-phone-mid-api36.png`

The crash-report offer (#54) on the next launch, in Spanish (#55), over the
dimmed page that #50's corrected dialog rendering produces. The offer states
what the report contains and that nothing leaves the device unless the reader
shares it.

## `combined-smoke-crash-report.txt`

The report that offer refers to, read out of private storage with `run-as`.

The crash was induced with a message deliberately naming a book and a path:

```text
java.lang.IllegalStateException: induced crash: could not read
/storage/emulated/0/Download/Rayuela - Julio Cortazar.epub
```

The stored report contains the app version, the device model, the Android
version, the exception **types** and the call sites — and none of
`Rayuela`, `Cortazar`, `/storage`, `Download`, `.epub` or `emulated`. That is
REQ-207's privacy line proved against an adversarial payload rather than a
benign one, on the integrated build.

`adb logcat -d -s AndroidRuntime:E` showed the FATAL EXCEPTION and
`pidof com.cedagova.fastreader` was empty afterwards, so the handler wrote the
report and still let the process die. Declining deleted the report file, and a
relaunch showed the library with no offer.
