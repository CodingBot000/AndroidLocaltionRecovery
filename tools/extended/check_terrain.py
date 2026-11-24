"""Check the generated visual geometry and keep the comparison's driving topology fixed."""

import hashlib
import json
import math
from pathlib import Path

from driving import inside
from terrain import crosses_polygon, intersects, near_road, overlaps, pairs, road_segments


def valid_polygon(points):
    assert len(points) >= 3 and len({tuple(point) for point in points}) == len(points)
    assert all(len(point) == 2 and all(math.isfinite(value) for value in point) for point in points)
    segments = list(pairs(points))
    area = sum(a[0] * b[1] - a[1] * b[0] for a, b in segments) / 2
    assert abs(area) > 1
    for first, (a, b) in enumerate(segments):
        for second in range(first + 2, len(segments)):
            if first == 0 and second == len(segments) - 1:
                continue
            assert not intersects(a, b, *segments[second]), (first, second)


def topology_digest(data):
    rows = [{key: value for key, value in edge.items() if key != "name"} for edge in data["edges"]]
    payload = {key: data[key] for key in ("mapId", "schemaVersion", "nodes", "transitions")}
    payload["edges"] = rows
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check(data):
    assert data["visualVersion"] == 2
    buildings = []
    polygons = data["polygons"]
    lots = {p["id"]: p["points"] for p in polygons if "id" in p}
    assert len(lots) == sum("id" in polygon for polygon in polygons)
    assert len(lots) >= 2
    natural = [p["points"] for p in polygons if p["type"] in {"WATER", "PARK"}]
    roads = road_segments(data)
    for i, polygon in enumerate(polygons):
        points = polygon["points"]
        try:
            valid_polygon(points)
            if polygon["type"] != "BUILDING":
                continue
            lot = lots[polygon["parcelId"]]
            assert all(inside(*point, lot) for point in points), "building outside parcel"
            assert not near_road(points, roads), "building inside motor-road clearance"
            assert not any(overlaps(points, shape) for shape in natural), "building inside park/water"
            assert not any(overlaps(points, other) for other in buildings), "overlapping building"
            buildings.append(points)
        except (AssertionError, KeyError) as error:
            raise AssertionError((data["mapId"], i, polygon.get("parcelId"), error)) from error
    assert len(buildings) >= 4
    for water in (p["points"] for p in polygons if p["type"] == "WATER"):
        bridges = [edge for edge in data["edges"] if crosses_polygon(edge["points"], water)]
        assert bridges and all(edge["structure"] == "BRIDGE" for edge in bridges), (
            data["mapId"], "water requires a bridge", [edge["id"] for edge in bridges])
    for walk in data["walkways"]:
        assert len(walk) >= 2 and all(a != b for a, b in zip(walk, walk[1:]))
        for a, b in zip(walk, walk[1:]):
            for polygon in polygons:
                if polygon["type"] in {"WATER", "BUILDING"}:
                    points = polygon["points"]
                    assert not inside(*a, points) and not any(
                        intersects(a, b, c, d) for c, d in pairs(points)
                    ), (data["mapId"], "walkway across water/building", a, b, polygon.get("parcelId"))
    return len(lots), len(buildings)


if __name__ == "__main__":
    reference = dict(line.split()[::-1] for line in Path("docs/map-quality-topology.sha256").read_text().splitlines())
    maps = sorted(Path("fixtures/extended/maps").glob("M*.json"))
    assert {path.stem for path in maps} == set(reference)
    for path in maps:
        data = json.loads(path.read_text())
        assert reference[path.stem] == topology_digest(data), path
        lots, buildings = check(data)
        print(f"{path.stem}: {lots} parcels, {buildings} clear buildings, baseline road graph")
