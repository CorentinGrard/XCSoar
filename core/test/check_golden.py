#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright The XCSoar Project

"""Golden replay tests (L2) for the headless core.

Replays each flight with xcs-replay into a fresh data directory and
compares the JSON lines with core/test/golden/<flight>.jsonl.  Numbers
are compared with per-field tolerances, because floating point results
differ slightly between compilers and CPUs; everything else must match
exactly.

  check_golden.py --replay output/X/bin/xcs-replay            # compare
  check_golden.py --replay output/X/bin/xcs-replay --update   # regenerate

Only regenerate after checking that the change in behaviour is intended,
and say why in the commit message.
"""

import argparse
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
GOLDEN_DIR = ROOT / "core" / "test" / "golden"

FLIGHTS = [
    "test/data/01lz1hq1.igc",
    "test/data/0asljd01.igc",
    "test/data/9crx3101.igc",
    "test/data/apf-bug554.igc",
]

# absolute tolerance per snapshot field; fields not listed must be equal
TOLERANCE = {
    "flight_time": 1,
    "lat": 1e-4,
    "lon": 1e-4,
    "track": 2,
    "ground_speed": 0.3,
    "tas": 0.3,
    "gps_alt": 1,
    "baro_alt": 1,
    "nav_alt": 1,
    "agl": 2,
    "vario": 0.2,
    "avg_vario": 0.2,
    "netto": 0.2,
    "wind_speed": 0.5,
    "wind_bearing": 5,
    "mc": 0.05,
    "next_distance": 5,
    "final_glide_alt_diff": 5,
}

ANGLES = {"track", "wind_bearing"}


def run_replay(replay, flight, work_dir):
    data_dir = work_dir / pathlib.Path(flight).stem
    shutil.rmtree(data_dir, ignore_errors=True)
    data_dir.mkdir(parents=True)

    result = subprocess.run([str(replay), str(data_dir), str(ROOT / flight)],
                            cwd=ROOT, capture_output=True, text=True,
                            timeout=600)
    if result.returncode != 0:
        sys.stderr.write(result.stderr[-2000:])
        raise RuntimeError(f"xcs-replay failed for {flight}")
    return [line for line in result.stdout.splitlines() if line.strip()]


def differs(field, expected, actual):
    if expected is None or actual is None or \
       isinstance(expected, bool) or field not in TOLERANCE:
        return expected != actual

    delta = abs(expected - actual)
    if field in ANGLES:
        delta = min(delta, 360 - delta)
    return delta > TOLERANCE[field]


def compare(expected_lines, actual_lines, max_report=10):
    problems = []
    if len(expected_lines) != len(actual_lines):
        problems.append(f"{len(actual_lines)} lines instead of "
                        f"{len(expected_lines)}")

    for n, (e_line, a_line) in enumerate(zip(expected_lines, actual_lines), 1):
        e, a = json.loads(e_line), json.loads(a_line)
        if e.keys() != a.keys():
            problems.append(f"line {n}: fields {sorted(a)} instead of "
                            f"{sorted(e)}")
            continue
        for field in e:
            if differs(field, e[field], a[field]):
                problems.append(f"line {n}: {field} is {a[field]}, "
                                f"expected {e[field]}")
        if len(problems) >= max_report:
            problems.append("...")
            break

    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--replay", required=True, type=pathlib.Path,
                        help="the xcs-replay binary")
    parser.add_argument("--work-dir", type=pathlib.Path,
                        default=ROOT / "output" / "test" / "golden",
                        help="scratch directory for the data directories")
    parser.add_argument("--update", action="store_true",
                        help="write the golden files instead of comparing")
    args = parser.parse_args()

    failed = 0
    for flight in FLIGHTS:
        golden = GOLDEN_DIR / (pathlib.Path(flight).stem + ".jsonl")
        lines = run_replay(args.replay.resolve(), flight, args.work_dir)

        if args.update:
            GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
            golden.write_text("\n".join(lines) + "\n")
            print(f"updated {golden.relative_to(ROOT)} ({len(lines)} lines)")
            continue

        if not golden.exists():
            print(f"FAIL {flight}: no golden file, run with --update")
            failed += 1
            continue

        problems = compare(golden.read_text().splitlines(), lines)
        if problems:
            failed += 1
            print(f"FAIL {flight}")
            for p in problems:
                print(f"  {p}")
        else:
            print(f"ok   {flight} ({len(lines)} lines)")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
