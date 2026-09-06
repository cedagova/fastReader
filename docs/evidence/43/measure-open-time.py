#!/usr/bin/env python3
"""Measures tap-to-paused-reader time for one book on a connected device.

Both ends of the interval are taken on the host, and nothing depends on the
build under test emitting anything — which is what lets the same script measure
the published v1.0.1 APK and a development build identically (REQ-110).

    t0  the moment the tap is injected
    t1  the first screen that is still, and different from the library

"Still" means the screen hash repeats for three consecutive polls; t1 is when the
first of those polls returned, not the third. Polling costs about 130 ms on the
reference AVD, so an interval carries up to that much overshoot and cannot
resolve anything faster — identically for both books and both builds, which is
what the comparison needs. An open reported at the floor means "not measurably
slower", not "this fast".

Screen recording was tried first and rejected: the emulator's `screenrecord`
only emits a frame when the screen changes, so a whole open can fall between two
frames and the timings in the container are not wall clock.

Usage:
    measure-open-time.py --tap X Y --runs 7 --label illustrated-v1.1.0 [--out DIR]
"""

import argparse
import hashlib
import pathlib
import statistics
import subprocess
import time

PACKAGE = "com.cedagova.fastreader"
ACTIVITY = f"{PACKAGE}/.MainActivity"

POLL_SECONDS = 0.0        # poll as fast as screencap allows (~130 ms)
STILL_POLLS = 3           # consecutive identical screens that count as settled
TIMEOUT_SECONDS = 120.0


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=True).stdout


def screen_hash() -> str:
    out = adb("shell", "screencap -p /data/local/tmp/measure.png; md5sum /data/local/tmp/measure.png")
    return out.split()[0]


def drop_caches() -> None:
    """Empties the guest page cache, so a run reads the file rather than memory.

    Needs `adb root`, which the emulator images in this repository's matrix
    allow. Without it, every run after the first reads a fully cached file and
    the measurement says nothing about a book that is actually on storage — on
    an Apple Silicon host the reference AVD hashes half a gigabyte from cache in
    about 150 ms, which is below the measurement floor.
    """
    subprocess.run(
        ["adb", "shell", "sync; echo 3 > /proc/sys/vm/drop_caches"],
        capture_output=True, text=True, check=True,
    )


def one_run(tap_x: int, tap_y: int, cold: bool) -> float:
    # Cold process every run: this measures opening a book, not resuming one.
    adb("shell", "am", "force-stop", PACKAGE)
    if cold:
        drop_caches()
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    time.sleep(4)
    # A cold launch resumes into the last-read book (REQ-009), so back out to the
    # library first. Both books must already have been opened once; see the
    # protocol's warm-up step.
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(3)

    library = screen_hash()

    t0 = time.monotonic()
    adb("shell", "input", "tap", str(tap_x), str(tap_y))

    seen: list[tuple[str, float]] = []
    while time.monotonic() - t0 < TIMEOUT_SECONDS:
        current = screen_hash()
        # Stamped after the capture, not before it: a screencap takes about
        # 130 ms, and the pixels it returns are the ones on screen when it
        # finished. Stamping first would credit the build with time it had not
        # yet spent and can report an interval shorter than one poll.
        seen.append((current, time.monotonic()))
        if current == library:
            continue
        tail = seen[-STILL_POLLS:]
        if len(tail) == STILL_POLLS and len({h for h, _ in tail}) == 1:
            return tail[0][1] - t0
        time.sleep(POLL_SECONDS)
    raise TimeoutError("the screen never settled on anything other than the library")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tap", nargs=2, type=int, required=True, metavar=("X", "Y"))
    parser.add_argument("--runs", type=int, default=7)
    parser.add_argument("--label", required=True)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("runs"))
    parser.add_argument(
        "--cold",
        action="store_true",
        help="drop the guest page cache before each run (needs `adb root`)",
    )
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    record = args.out / f"{args.label}.txt"
    results = []
    for run in range(1, args.runs + 1):
        elapsed = one_run(*args.tap, cold=args.cold)
        results.append(elapsed)
        print(f"{args.label} run {run}: {elapsed:.3f}s", flush=True)

    summary = (
        f"{args.label} ({'cold cache' if args.cold else 'warm cache'}): "
        f"runs={len(results)} median={statistics.median(results):.3f} "
        f"min={min(results):.3f} max={max(results):.3f}\n"
        + "".join(f"  run {i}: {v:.3f}\n" for i, v in enumerate(results, 1))
    )
    record.write_text(summary)
    print(summary, end="")


if __name__ == "__main__":
    main()
