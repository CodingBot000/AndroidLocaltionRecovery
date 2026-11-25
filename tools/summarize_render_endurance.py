#!/usr/bin/env python3
"""Summarize the existing PhysicalOsmEnduranceTest log without PIDs or timestamps."""

import argparse
import json
from pathlib import Path
import re
from statistics import median


SAMPLE = re.compile(
    r"\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+I\s+PhysicalOsmEndurance\s*:\s+cycle=(\d+) phase=(\w+) (.+)"
)
MEMORY_FIELDS = ("totalPssKiB", "javaPssKiB", "nativePssKiB", "javaUsedKiB", "nativeAllocatedKiB", "graphicsKiB")
REPLAY_FIELDS = ("virtualMs", "backlogMs", "logs", "trail")


def sample_from_line(line):
    match = SAMPLE.fullmatch(line)
    if not match:
        return None
    timestamp, pid, cycle, phase, message = match.groups()
    source = dict(re.findall(r"(\w+)=([^\s]+)", message))
    sample = {"cycle": int(cycle), "phase": phase}
    for field in MEMORY_FIELDS + REPLAY_FIELDS:
        value = source.get(field, "")
        sample[field] = int(value) if value.isdecimal() else None
    if sample["nativeAllocatedKiB"] == 0 and sample["nativePssKiB"]:
        sample["nativeAllocatedKiB"] = None
        sample["nativeAllocatedStatus"] = "UNAVAILABLE_REPORTED_ZERO"
    return float(timestamp), pid, sample


def phase_statistics(samples, field):
    values = [(sample["cycle"], sample[field]) for sample in samples if sample[field] is not None]
    if not values:
        return None
    slopes = [(value - earlier) / (cycle - earlier_cycle)
              for index, (cycle, value) in enumerate(values)
              for earlier_cycle, earlier in values[:index]]
    series = [value for _, value in values]
    return {
        "n": len(series), "min": min(series), "median": median(series), "max": max(series),
        "lastThreeRange": [min(series[-3:]), max(series[-3:])],
        "theilSenSlopeKiBPerCycle": median(slopes) if slopes else None,
    }


def summarize(content):
    # A logcat stream with -T 1 can start with a sample from the previous invocation.
    # The first ready sample identifies the measured PID and begins the current run.
    entries = [entry for line in content.splitlines() if (entry := sample_from_line(line))]
    ready = [index for index, (_, _, sample) in enumerate(entries) if (sample["cycle"], sample["phase"]) == (1, "ready")]
    if len(ready) != 1:
        raise ValueError("Expected one cycle 1 ready marker in the isolated log")
    entries = entries[ready[0]:]
    start, pid, _ = entries[0]
    if {entry[1] for entry in entries} != {pid}:
        raise ValueError("The endurance test changed process")
    samples = [{**sample, "offsetSeconds": round(timestamp - start, 3)} for timestamp, _, sample in entries]
    expected = [(cycle, phase) for cycle in range(1, 11) for phase in ("ready", "completed", "reset")]
    expected.extend([(10, "idle60s"), (10, "idle120s")])
    observed = [(sample["cycle"], sample["phase"]) for sample in samples if sample["phase"] != "playing"]
    if observed != expected:
        raise ValueError("The endurance log is missing or reorders measured phases")
    late_completed = [sample for sample in samples if sample["phase"] == "completed" and sample["cycle"] >= 2]
    last_reset = next(sample for sample in samples if sample["cycle"] == 10 and sample["phase"] == "reset")
    idle_120 = samples[-1]
    return {
        "schemaVersion": 1, "source": "Debug.getMemoryInfo / Runtime / Debug.getNativeHeapAllocatedSize",
        "phaseSamples": samples,
        "sampleCount": len(samples),
        "peakTotalPssKiB": max(sample["totalPssKiB"] for sample in samples),
        "lastResetToIdle120sPssRecoveryKiB": last_reset["totalPssKiB"] - idle_120["totalPssKiB"],
        "completedCycles2To10": {field: phase_statistics(late_completed, field) for field in MEMORY_FIELDS},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path, help="Private logcat -v epoch output of the PhysicalOsmEndurance tag")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = summarize(args.log.read_text())
    args.output.write_text(json.dumps(report, allow_nan=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
