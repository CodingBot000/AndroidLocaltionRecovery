"""Deterministic trajectory and receiver-fault generation."""

import math
from bisect import bisect_right
from random import Random


def distance_to_segment(point, start, end):
    dx, dy = end[0] - start[0], end[1] - start[1]
    fraction = max(0, min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / (dx * dx + dy * dy)))
    return math.dist(point, (start[0] + fraction * dx, start[1] + fraction * dy))


def inside(x, y, polygon):
    inside_polygon = False
    for (ax, ay), (bx, by) in zip(polygon, polygon[1:] + polygon[:1]):
        if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
            inside_polygon = not inside_polygon
    return inside_polygon


def trajectory(map_data, plan, duration, *, edge_times=None, stops=(), seed):
    by_id = {road["id"]: road["points"] for road in map_data["edges"]}
    polylines = [by_id[road] for road in plan]
    lengths = [sum(math.dist(a, b) for a, b in zip(points, points[1:])) for points in polylines]
    if edge_times is None:
        stops_in_ticks = {tick for start, end in stops for tick in range(start * 5, end * 5)}
        cumulative = [0]
        for tick in range(duration * 5):
            cumulative.append(cumulative[-1] + (0 if tick in stops_in_ticks else 1))
        thresholds = [0]
        for i in range(1, len(lengths)):
            threshold = round(cumulative[-1] * sum(lengths[:i]) / sum(lengths))
            thresholds.append(bisect_right(cumulative, threshold) - 1)
        boundaries = thresholds + [duration * 5]
    else:
        boundaries = [time * 5 for time in edge_times]
    assert len(boundaries) == len(plan) + 1 and boundaries[0] == 0 and boundaries[-1] == duration * 5
    rows, velocities = [], []
    for index, (edge, points, length, start, end) in enumerate(zip(plan, polylines, lengths, boundaries, boundaries[1:])):
        assert end > start
        segments = list(zip(points, points[1:]))
        segment_lengths = [math.dist(a, b) for a, b in segments]
        prefix = [0.0]
        for segment_length in segment_lengths:
            prefix.append(prefix[-1] + segment_length)
        weights = []
        for tick in range(start, end):
            time = tick / 5
            # Cap ramps at 4 s around deliberate stops and at the drive endpoints.
            ramp = min(1, time / 3, (duration - time) / 3)
            for begin, finish in stops:
                since_stop = time - finish
                until_stop = begin - time
                ramp = min(ramp, max(0, since_stop / 4, until_stop / 4))
            weights.append(ramp * (1 + 0.17 * math.sin(time * 0.14 + seed)))
        traveled = 0.0
        meter_per_weight = length / sum(weights)
        for tick, weight in enumerate(weights, start):
            segment = min(bisect_right(prefix, traveled) - 1, len(segments) - 1)
            a, b = segments[segment]
            fraction = (traveled - prefix[segment]) / segment_lengths[segment]
            heading = math.degrees(math.atan2(b[0] - a[0], b[1] - a[1])) % 360
            rows.append(dict(time=tick * 200, x=round(a[0] + fraction * (b[0] - a[0]), 4),
                             y=round(a[1] + fraction * (b[1] - a[1]), 4), road=edge))
            velocities.append((round(weight * meter_per_weight * 5, 4), round(heading, 4) % 360))
            traveled += weight * meter_per_weight
    rows.append(dict(time=duration * 1000, x=polylines[-1][-1][0], y=polylines[-1][-1][1], road=plan[-1]))
    velocities.append((0, velocities[-1][1]))
    assert max(speed for speed, _ in velocities) <= 39
    return rows, velocities


def receiver(truth, velocities, faults, seed):
    random = Random(seed)
    observations = []
    for second, row in enumerate(truth[::5]):
        time = row["time"]
        speed, heading = velocities[second * 5]
        x, y = row["x"], row["y"]
        observation = dict(id=f"o{second:03}", measured=time, received=time,
                           x=round(x + random.uniform(-1.0, 1.0), 4),
                           y=round(y + random.uniform(-1.0, 1.0), 4),
                           accuracy=4.0, speed=speed, heading=heading if speed > 0 else None, label="NORMAL")
        actions = faults.get(second, ())
        if "drop" in actions:
            continue
        for action in actions:
            if action == "jump":
                observation.update(x=x + 480, y=y - 305, label="INVALID")
            elif action == "quality":
                observation.update(accuracy=31, label="LOW_QUALITY")
            elif action == "poor":
                observation.update(accuracy=84, label="INVALID")
            elif action == "bias":
                observation.update(y=round(y + 14, 4), accuracy=18)
            elif action == "shake":
                observation.update(x=round(x + (8 if second % 2 else -8), 4))
            elif action == "heading":
                observation.update(heading=None if second % 2 else (heading + 105) % 360)
            elif action == "parallel":
                observation.update(y=6.0, accuracy=12, heading=None)
            elif action == "side_jump":
                observation.update(y=12.0)
            elif action == "negative_speed":
                observation.update(speed=-1, label="INVALID")
            elif action == "future":
                observation.update(measured=time + 900, label="INVALID")
            elif action == "delay":
                observation.update(measured=time - 3000, received=time + 1000, label="INVALID")
            elif action == "order":
                observation.update(measured=time - 2000, label="INVALID")
            elif action not in ("duplicate", "collision"):
                raise ValueError(f"Unknown action {action}")
        observations.append(observation)
        if "duplicate" in actions:
            observations.append(dict(observation, label="INVALID"))
        if "collision" in actions:
            observations.append(dict(observation, id=f"collision-{second}", label="INVALID"))
    return observations
