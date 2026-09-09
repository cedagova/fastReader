#!/usr/bin/env python3
"""Measures tap-in-Files-to-paused-reader time for one book (REQ-110, external path).

The same interval and the same two host-side endpoints as
`docs/evidence/43/measure-open-time.py`, moved to the entry point this leaf owns:
the tap happens in the Files app, not in FastReader's library, so the measured
open includes accepting the intent, querying the provider for a display name and
looking the URI up in the catalog — everything REQ-110 says must not depend on
the file's image payload.

    t0  the moment the tap is injected in the Files app
    t1  the first screen that is still, and different from the Files listing

"Still" means the screen hash repeats for three consecutive polls; t1 is when the
first of those polls returned. Polling costs about 130 ms on the reference AVD,
so an interval carries up to that much overshoot and cannot resolve anything
faster. An open reported at the floor means "not measurably slower", never "this
fast" — the same caveat, and the same measurement floor, as #43 recorded.

The Files app is brought forward by component so the listing keeps its scroll
position between runs; the tap coordinates therefore stay valid for the whole
session and must not be reused after scrolling it.

Usage:
    measure-external-open-time.py --tap X Y --runs 7 --label illustrated [--out DIR] [--cold]
"""

import argparse
import json
import pathlib
import statistics
import subprocess
import time

PACKAGE = "com.cedagova.fastreader"
FILES_ACTIVITY = "com.google.android.documentsui/com.android.documentsui.files.FilesActivity"

STILL_POLLS = 3
TIMEOUT_SECONDS = 120.0


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=True).stdout


def screen_hash() -> str:
    out = adb("shell", "screencap -p /data/local/tmp/measure44.png; md5sum /data/local/tmp/measure44.png")
    return out.split()[0]


def drop_caches() -> None:
    """Empties the guest page cache so a run reads the file rather than memory.

    Needs `adb root`. Without it every run after the first reads a fully cached
    file, and on an Apple Silicon host the emulator's storage is host RAM
    anyway — see #43's protocol for why that makes this comparison insensitive.
    """
    subprocess.run(
        ["adb", "shell", "sync; echo 3 > /proc/sys/vm/drop_caches"],
        capture_output=True, text=True, check=True,
    )


def one_run(tap_x: int, tap_y: int, cold: bool) -> float:
    # Cold process every run: this measures opening a book from outside the app,
    # never resuming one the process already holds.
    adb("shell", "am", "force-stop", PACKAGE)
    if cold:
        drop_caches()
    adb("shell", "am", "start", "-n", FILES_ACTIVITY)
    time.sleep(3)

    listing = screen_hash()

    t0 = time.monotonic()
    adb("shell", "input", "tap", str(tap_x), str(tap_y))

    seen: list[str] = []
    first_of_still = None
    while time.monotonic() - t0 < TIMEOUT_SECONDS:
        at = time.monotonic()
        current = screen_hash()
        if current == listing:
            seen.clear()
            first_of_still = None
            continue
        if seen and current == seen[-1]:
            seen.append(current)
        else:
            seen = [current]
            first_of_still = at
        if len(seen) >= STILL_POLLS:
            return first_of_still - t0
    raise TimeoutError("the reader never settled on a still screen")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tap", nargs=2, type=int, required=True, metavar=("X", "Y"))
    parser.add_argument("--runs", type=int, default=7)
    parser.add_argument("--label", required=True)
    parser.add_argument("--out", type=pathlib.Path)
    parser.add_argument("--cold", action="store_true")
    args = parser.parse_args()

    intervals = []
    for index in range(args.runs):
        seconds = one_run(args.tap[0], args.tap[1], args.cold)
        intervals.append(seconds)
        print(f"run {index + 1}/{args.runs}: {seconds:.3f}s", flush=True)

    summary = {
        "label": args.label,
        "runs": args.runs,
        "cold": args.cold,
        "intervals": [round(value, 4) for value in intervals],
        "median": round(statistics.median(intervals), 4),
        "min": round(min(intervals), 4),
        "max": round(max(intervals), 4),
    }
    print(json.dumps(summary, indent=2))
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / f"{args.label}.json").write_text(json.dumps(summary, indent=2) + "\n")


if __name__ == "__main__":
    main()
