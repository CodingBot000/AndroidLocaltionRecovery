"""Offline asset/manifest verification suitable for ordinary CI (standard library only)."""

from hashlib import file_digest
import json
import math
from pathlib import Path

ROOT = Path("fixtures/osm")


def checksum(path):
    with path.open("rb") as file:
        return file_digest(file, "sha256").hexdigest()


def load(relative):
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def valid_points(points, minimum):
    return (len(points) >= minimum and all(len(point)==2 and all(math.isfinite(v) for v in point) for point in points)
            and all(a != b for a,b in zip(points,points[1:])))


def verify():
    manifest, catalog = load("manifest.json"), load("catalog.json")
    assert catalog["schemaVersion"] == 3 and len(catalog["maps"]) == 10
    source = manifest["source"]
    assert source["dataset"] == "OSM" and source["license"] == "ODbL-1.0"
    assert source["attribution"] == "© OpenStreetMap contributors"
    assert len(manifest["regions"]) == len({row["normalizedExtractSha256"] for row in manifest["regions"]}) == 10
    assert len(catalog["entries"]) == 28
    assert sum(row["representative"] for row in catalog["entries"]) == 1
    ids = {row["id"] for row in catalog["maps"]}
    assert len(ids) == 10
    assert ids == {row["mapId"] for row in catalog["entries"] if row["evaluation"]}
    assert ids == {row["mapId"] for row in catalog["entries"] if not row["evaluation"]}
    coverage = {"O02": ("roadClass","RAMP"), "O03": ("structure","TUNNEL"),
                "O05": ("structure","BRIDGE"), "O06": ("structure","BRIDGE")}
    for region in manifest["regions"]:
        key = region["id"]
        file = ROOT / region["file"]
        assert key in ids and checksum(file) == region["outputSha256"] and file.stat().st_size == region["bytes"]
        data = load(region["file"])
        assert data["mapId"] == key and data["source"] == source and data["projection"]["bbox"] == region["bbox"]
        nodes = {row["id"]:row["point"] for row in data["nodes"]}
        edges = {row["id"]:row for row in data["edges"]}
        assert len(nodes) == len(data["nodes"]) and len(edges) == len(data["edges"])
        if key in coverage:
            field,value = coverage[key]
            assert any(road[field] == value for road in edges.values()), key
        assert any(p["type"] == "BUILDING" for p in data["polygons"])
        for road in edges.values():
            points = road["points"]
            assert valid_points(points, 2) and nodes[road["start"]] == points[0] and nodes[road["end"]] == points[-1]
            assert isinstance(road["osmWay"], int)
            assert all(edges[to]["start"] == road["end"] and edges[to]["physicalId"] != road["physicalId"]
                       for to in data["transitions"][road["id"]])
        assert all(valid_points(row["points"],3) and all(valid_points(hole,3) for hole in row.get("holes",[]))
                   for row in data["polygons"])
        assert all(valid_points(path,2) for path in data["walkways"])
        for entry in (entry for entry in catalog["entries"] if entry["mapId"] == key):
            folder = "evaluation" if entry["evaluation"] else "scenarios"
            scenario = load(f"{folder}/{entry['id']}.json")
            assert scenario["mapChecksum"] == region["outputSha256"] and scenario["duration"] == entry["duration"]
            assert scenario["scenarioId"] == entry["id"] and scenario["mapId"] == key
            for route in (scenario["plannedRoute"],scenario["actualRoute"]):
                assert len(route) == len({edges[e]["physicalId"] for e in route})
                assert all(following in data["transitions"][edge] for edge,following in zip(route,route[1:]))
            actual = set(scenario["actualRoute"])
            assert scenario["groundTruth"][0]["time"] == 0 and scenario["groundTruth"][-1]["time"] == entry["duration"]
            assert all(row["road"] in actual and math.isfinite(row["x"]) and math.isfinite(row["y"])
                       for row in scenario["groundTruth"])
        print(f"{key} {region['name']} {region['bytes']} bytes {len(edges)} directed roads")


if __name__ == "__main__":
    verify()
