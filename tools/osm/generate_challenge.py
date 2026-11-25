"""Independent disturbance plans on immutable Korean OSM trajectories."""

import math
from pathlib import Path
import json
import sys

from shapely import LineString, Point, STRtree
from shapely.ops import nearest_points

from import_maps import digest, write_json

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "extended"))
from driving import receiver

CASES = (
    ("BIAS", "OL01", "Sustained urban bias"),
    ("PARALLEL", "OL10", "Repeated parallel-road pull"),
    ("RECOVERY", "OL07", "Interleaved recovery"),
    ("TURN", "OL15", "Heading delay through turns"),
    ("LEVEL", "OL02", "Incorrect grade projection"),
    ("COMPOUND", "OL05", "Compound reception faults"),
    ("CURVE", "OL09", "Heading loss on curved roads"),
    ("REPEATED", "ODEMO_LONG", "Repeated outages"),
)

ROOT = Path("fixtures/osm")


def velocity(truth):
    samples = []
    for i in range(len(truth)):
        first, last = truth[max(0, i - 1)], truth[min(len(truth) - 1, i + 1)]
        dx, dy = last["x"] - first["x"], last["y"] - first["y"]
        seconds = (last["time"] - first["time"]) / 1000
        samples.append((math.hypot(dx, dy) / seconds, math.degrees(math.atan2(dx, dy)) % 360))
    return samples


def perturb(case, base, seed, holdout):
    truth = base["groundTruth"]
    speeds = velocity(truth)
    duration = base["duration"] // 1000
    faults, expected, starts = {}, [], []
    delta = 3 if holdout else 0

    def put(at, value):
        faults.setdefault(at, []).append(value)

    def add(reason, at=None, count=1):
        expected.append(dict(reason=reason, count=count, time=None if at is None else at * 1000))

    def outage(first, last, recovery=None):
        for t in range(first, last):
            put(t, "drop")
        if recovery is not False:
            starts.append((recovery if recovery is not None else last) * 1000)

    if case == "RECOVERY":
        start = 32 + delta
        outage(start, start + 10, recovery=start + 15)
        put(start + 10, "quality")
        put(start + 12, "poor")
        put(start + 14, "quality")
        add("LOW_QUALITY", count=2)
        add("ACCURACY_EXCEEDED", at=start + 12)
        add("SIGNAL_LOST", at=start + 2)
    elif case == "COMPOUND":
        for t, fault, reason in (
            (16, "duplicate", "DUPLICATE_ID"), (28, "order", "MEASUREMENT_ORDER"), (42, "jump", "IMPLAUSIBLE_MOVE"),
        ):
            put(t + delta, fault)
            add(reason, at=t + delta)
        outage(57 + delta, 69 + delta)
        faults[62 + delta] = ["delay"]
        add("DELIVERY_DELAY", 63 + delta)
        for t in range(72 + delta, 77 + delta):
            put(t, "bias")
        add("SIGNAL_LOST", at=59 + delta)
    elif case == "CURVE":
        outage(34 + delta, 45 + delta)
        for t in range(22 + delta, 34 + delta):
            put(t, "heading")
        add("SIGNAL_LOST", at=36 + delta)
    elif case == "REPEATED":
        for start, span in [(26, 8), (77, 11), (132, 16 if holdout else 12)]:
            outage(start + delta, start + span + delta)
            add("SIGNAL_LOST", at=start + delta + 2)

    fixes = receiver(truth, speeds, faults, seed)
    if case == "BIAS":
        for fix in fixes:
            t = fix["received"] // 1000
            if 22 + delta <= t < 57 + delta:
                fix["x"] += 16 if holdout else 12
                fix["y"] += -14 if holdout else 9
                fix["accuracy"] = 18
    if case == "TURN":
        for fix in fixes:
            t = fix["received"] // 1000
            if 9 + delta <= t < 85 + delta:
                fix["heading"] = None if t % 3 == 0 else speeds[max(0, (t - 2) * 5)][1]
    if case in {"PARALLEL", "LEVEL"}:
        pull_toward_neighbor(base, fixes, speeds, case, holdout)
    add("RUN_COMPLETED", at=duration)
    return fixes, expected, starts


def pull_toward_neighbor(base, fixes, speeds, case, holdout):
    model = json.loads((ROOT / "maps" / f"{base['mapId']}.json").read_text())
    roads = model["edges"]
    by_id = {road["id"]: road for road in roads}
    lines = [LineString(road["points"]) for road in roads]
    index = STRtree(lines)
    count = 0
    for fix in fixes:
        t = fix["received"] // 1000
        if not 12 <= t < 105 or (holdout and t % 4 == 0):
            continue
        truth = base["groundTruth"][t * 5]
        center = Point(truth["x"], truth["y"])
        current = by_id[truth["road"]]
        options = []
        for neighbor_index in index.query(center, predicate="dwithin", distance=40):
            road, line = roads[neighbor_index], lines[neighbor_index]
            if road["physicalId"] == current["physicalId"] or line.length < 20:
                continue
            if case == "LEVEL":
                if current["level"] == road["level"] or {road["start"],road["end"]} & {current["start"],current["end"]}:
                    continue
            else:
                if road["level"] != current["level"] or line.distance(center) < 7:
                    continue
                first, last = line.coords[0], line.coords[-1]
                heading = math.degrees(math.atan2(last[0] - first[0], last[1] - first[1]))
                if abs(math.sin(math.radians(heading - speeds[t * 5][1]))) > .30:
                    continue
            options.append((line.distance(center), road["id"], line))
        if not options:
            continue
        gap, _, line = min(options, key=lambda row: (row[0], row[1]))
        point = nearest_points(center, line)[1]
        weight = .81 if holdout else .62
        fix.update(x=round(center.x + (point.x-center.x)*weight, 4),
                   y=round(center.y + (point.y-center.y)*weight, 4), accuracy=17 if gap < 30 else 20)
        count += 1
    if count < (2 if case == "PARALLEL" else 1):
        raise ValueError(f"{base['scenarioId']}: insufficient {case} geometry")


if __name__ == "__main__":
    entries = []
    for independent in (False, True):
        for i, (case, original, title) in enumerate(CASES, 1):
            base = json.loads((ROOT / "scenarios" / f"{original}.json").read_text())
            seed = (92800 if independent else 91500) + i
            id = ("CE" if independent else "CD") + f"{i:02}"
            fixes, expected, starts = perturb(case, base, seed, independent)
            base.update(scenarioId=id, title=title, seed=seed, observations=fixes, expected=expected,
                        expectedRecoveryAtMs=starts)
            path = ROOT / "challenge" / ("evaluation" if independent else "development") / f"{id}.json"
            write_json(path, base)
            entries.append(dict(id=id, mapId=base["mapId"], duration=base["duration"], title=title, titleKo=title,
                                description=title, descriptionKo=title, kind=case, kindKo=case,
                                evaluation=independent, representative=id=="CD08",
                                baseScenario=original, fileSha256=digest(path)))
    maps = json.loads((ROOT / "catalog.json").read_text())["maps"]
    covered = {item["mapId"] for item in entries}
    write_json(ROOT / "challenge" / "catalog.json",
               dict(schemaVersion=3, entries=entries, maps=[m for m in maps if m["id"] in covered]))
