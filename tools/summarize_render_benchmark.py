#!/usr/bin/env python3
"""Summarize local RefactorMapBenchmark sets without copying device identifiers."""

import argparse
import hashlib
import json
import math
from pathlib import Path
from statistics import median


STARTUP_METRICS = ("timeToInitialDisplayMs", "timeToFullDisplayMs")
FRAME_METRICS = ("frameDurationCpuMs", "frameOverrunMs")
SCENARIOS = ("coldO10", "switchO10ToO07", "rideO10")


def distribution(values):
    if not values or any(not math.isfinite(value) for value in values):
        raise ValueError("A distribution requires finite samples")
    ordered = sorted(values)
    return {
        "n": len(values),
        "min": ordered[0],
        "median": median(ordered),
        "max": ordered[-1],
        **{f"p{percent}": ordered[math.ceil(len(values) * percent / 100) - 1]
           for percent in (50, 90, 95, 99)},
    }


def read_set(folder):
    source, = folder.glob("*benchmarkData.json")
    raw = source.read_bytes()
    cases = {case["name"]: case for case in json.loads(raw)["benchmarks"]}
    if not cases or cases.keys() - set(SCENARIOS):
        raise ValueError("Only original RefactorMapBenchmark cases are supported")
    result = {}
    for name, case in cases.items():
        count = case["repeatIterations"]
        if name == "coldO10":
            result[name] = {key: case["metrics"][key]["runs"] for key in STARTUP_METRICS}
        else:
            result[name] = {key: case["sampledMetrics"][key]["runs"] for key in FRAME_METRICS}
            if [len(values) for values in result[name][FRAME_METRICS[0]]] != [
                len(values) for values in result[name][FRAME_METRICS[1]]
            ]:
                raise ValueError(f"{name}: CPU and overrun sample counts differ")
        if name == "switchO10ToO07":
            result[name]["mapReadyMs"] = json.loads((folder / "switch-ready-ms.json").read_text())
        if any(len(values) != count for values in result[name].values()):
            raise ValueError(f"{name}: expected {count} iterations for every metric")
    return {"id": folder.name, "benchmarkResultSha256": hashlib.sha256(raw).hexdigest(), "scenarios": result}


def summarize(folders):
    sets = [read_set(folder) for folder in folders]
    summary = {}
    for name in SCENARIOS:
        scenarios = [item["scenarios"][name] for item in sets if name in item["scenarios"]]
        if not scenarios:
            continue
        summary[name] = {}
        for metric in scenarios[0]:
            runs = [run for scenario in scenarios for run in scenario[metric]]
            if metric in FRAME_METRICS:
                values = [value for iteration in runs for value in iteration]
                per_iteration = [distribution(iteration) for iteration in runs]
                summary[name][metric] = {
                    "pooled": distribution(values),
                    "perIteration": per_iteration,
                    "medianIterationP95Ms": median(item["p95"] for item in per_iteration),
                }
                if metric == "frameOverrunMs":
                    summary[name][metric]["positiveSamples"] = sum(value > 0 for value in values)
            else:
                summary[name][metric] = distribution(runs)
    return {
        "schemaVersion": 1,
        "aggregation": "P50/P90/P95/P99 nearest rank. Median averages the middle pair when n is even.",
        "unit": "ms",
        "sets": sets,
        "summary": summary,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sets", nargs="+", type=Path, help="Directories for validated benchmark sets")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = summarize(args.sets)
    args.output.write_text(json.dumps(report, allow_nan=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
