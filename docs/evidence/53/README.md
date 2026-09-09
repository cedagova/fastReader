# #53 — Tablet and landscape layouts: device evidence

Every capture below came from the **debug build of `0cd2fd8`** ("Place the
reader's controls beside the stream on wide screens") — the commit this
directory's parent. The commit that adds these PNGs cannot have produced them.

Each device was booted headless (`emulator -avd <name> -no-window`), the debug
APK installed with `./gradlew installDebug`, and the same five fixture EPUBs
added as one folder through the system picker. `adb exec-out screencap -p` took
the images; the layout claims under them were also read out of
`adb exec-out uiautomator dump`, so "two columns" is two nodes with the same `y`
and different `x`, not an impression.

## `Tablet_Low_API33` — 1200 x 1920 px at 320 dpi = **exactly 600 x 960 dp**

The boundary device. A breakpoint written `> 600.dp`, or one compared as `Dp`
after a pixel round trip, gets exactly this device wrong and no other.

| Image | What it shows |
| --- | --- |
| `tablet-600dp-library-two-columns.png` | The library at exactly 600 dp: search beside both add buttons, and the books in two columns (`x0` 160 and 760 px). |
| `tablet-600dp-reader-controls-beside.png` | The reader at exactly 600 dp: the stream in 40–598 px, the whole control column in 652–1188 px. Nothing below the stream. |
| `tablet-600dp-reader-largest-font.png` | The same 600 dp with **Text size: Largest**, mid-playback. Chapter row, progress, scrubber, transport and speed are all present and whole. |
| `tablet-960dp-reader-rotated-mid-playback.png` | The same session after rotating to 960 dp **while the stream was running**: still playing (the transport reads *Pause*) and the position had advanced 0% → 1% across the rotation. |

## `Tablet_Mid_API36` — 2560 x 1600 px at 320 dpi = 1280 x 800 dp

| Image | What it shows |
| --- | --- |
| `tablet-1280dp-library-four-columns.png` | Four book columns (`x0` 160, 800, 1440, 2080 px). |
| `tablet-1280dp-reader-controls-beside.png` | The control column clamped to 420 dp (1732–2548 px) with the remaining ~840 dp spent on the stream. |

## `Phone_Mid_API36` — 1080 x 2400 px at 420 dpi (density 2.625) = 411 x 914 dp

The non-integral density, and the two sides of the breakpoint on one device.

| Image | What it shows |
| --- | --- |
| `phone-411dp-library-unchanged.png` | Portrait, 411 dp: the stacked header and the single column, exactly as before this change. |
| `phone-914dp-landscape-library.png` | The same device on its side, 914 dp: one-row header and three columns, with all five books on a 411 dp-tall screen. |
| `phone-914dp-landscape-reader.png` | Landscape reader: stream 53–1336 px, controls 1408–2384 px. |
| `phone-914dp-landscape-focused-speed-readout.png` | Focused mode in the wide window is the same full-bleed stream a phone shows — no divider, no controls — and the focused-mode speed drag still works: this drag took 250 → 325 WPM, and the readout is text on the page's own background. Leaving focused mode afterwards brought the control column back with "31 min left", the new speed's estimate. |
