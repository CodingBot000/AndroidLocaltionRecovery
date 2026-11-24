"""Extract privacy-filtered Korean vector districts from one pinned Geofabrik PBF."""

import argparse
from collections import Counter, defaultdict
from hashlib import file_digest, sha256
from importlib.metadata import version
import json
import re
from pathlib import Path
import urllib.request

import osmium
from pyproj import Transformer
from shapely import LineString, Polygon

from regions import REGIONS

SOURCE = "https://download.geofabrik.de/asia/south-korea/south-korea-261101.osm.pbf"
SNAPSHOT = "2026-11-01T21:21:36Z"
# Verified against the actual downloaded payload. This is also checked before every import.
SOURCE_HASH = "055f00dea8bdc13b1805d305b4bd2e2b0c419c4be0a93f0e7b751bd965db5009"
ACQUIRED = "2026-11-25"
CRS = "EPSG:5179"
ROUND_M = 2
CLASSES = {
    "motorway": "EXPRESSWAY", "trunk": "EXPRESSWAY",
    "primary": "ARTERIAL", "secondary": "ARTERIAL",
    "tertiary": "COLLECTOR", "unclassified": "LOCAL", "residential": "LOCAL",
    "living_street": "LOCAL", "service": "LOCAL",
    **{name + "_link": "RAMP" for name in ("motorway", "trunk", "primary", "secondary", "tertiary")},
}
WALK = {"footway", "pedestrian", "path", "steps", "cycleway"}
DENIED = {"private", "no", "agricultural", "forestry", "customers", "destination", "delivery"}
STRUCTURES = {"BRIDGE": "bridge", "TUNNEL": "tunnel"}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    # One record per line for geometry and observations; fields retain canonical insertion order.
    def compact(item):
        return json.dumps(item, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    text = []
    for key, item in value.items():
        encoded = ("[\n    " + ",\n    ".join(map(compact, item)) + "\n  ]"
                   if isinstance(item, list) and item and isinstance(item[0], dict) else compact(item))
        text.append(f"  {compact(key)}:{encoded}")
    path.write_text("{\n" + ",\n".join(text) + "\n}\n", encoding="utf-8")


def digest(path):
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()


def acquire(path):
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        pending = path.with_suffix(".part")
        try:
            with urllib.request.urlopen(SOURCE, timeout=120) as response, pending.open("wb") as file:
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    file.write(chunk)
            if digest(pending) != SOURCE_HASH:
                raise ValueError("The pinned extract has an unexpected checksum")
            pending.replace(path)
        finally:
            pending.unlink(missing_ok=True)
    if digest(path) != SOURCE_HASH:
        raise ValueError("The existing extract is not the pinned PBF")


def road_attributes(tags):
    highway = tags.get("highway")
    if highway not in CLASSES or tags.get("area") == "yes":
        return None
    access = next((tags[key] for key in ("motorcar", "motor_vehicle", "vehicle", "access") if key in tags), "")
    if access in DENIED or tags.get("service") in {"driveway", "parking_aisle", "drive-through", "emergency_access"}:
        return None
    for key in ("motorcar:conditional", "motor_vehicle:conditional", "vehicle:conditional",
                "access:conditional", "oneway:conditional"):
        if key not in tags:
            continue
        # This dataset models ordinary passenger cars. Accept only explicit hazmat-only access clauses.
        hazmat_only = key == "access:conditional" and all(
            re.fullmatch(r"no\s*@\s*\(hazmat:[A-E](?:\s+AND\s+[A-Za-z0-9 :\-]+)?\)", clause.strip())
            for clause in tags[key].split(";")
        )
        if not hazmat_only:
            return None
    direction = tags.get("oneway", "yes" if highway == "motorway" or tags.get("junction") in {"roundabout", "circular"} else "no")
    if direction not in {"yes", "true", "1", "-1", "no", "false", "0"}:
        return None
    direction = -1 if direction == "-1" else 1 if direction in {"yes", "true", "1"} else 0
    structure = next((key for key, tag in STRUCTURES.items()
                      if tags.get(tag, "no") not in {"no", "false", "0", "building_passage"}), "SURFACE")
    try:
        level = int(tags.get("layer", 1 if structure == "BRIDGE" else -1 if structure == "TUNNEL" else 0))
    except ValueError:
        level = 0
    if level > 0 and structure == "SURFACE":
        structure = "ELEVATED"
    # OSM public street names, never addresses, POIs, contributor fields, or building names.
    label = (tags.get("name:ko") or tags.get("name") or tags.get("ref")) if highway != "service" else None
    return CLASSES[highway], structure, max(-8, min(8, level)), direction, label[:80] if label else None


def poi_info(tags):
    amenity = tags.get("amenity")
    leisure = tags.get("leisure")
    railway = tags.get("railway")
    kind = None
    if railway in {"station", "halt"} or (tags.get("public_transport") == "station" and
            (tags.get("train") == "yes" or tags.get("subway") == "yes")):
        kind = "STATION"
    elif amenity in {"school", "university", "college", "parking", "fuel"}:
        kind = {"school": "SCHOOL", "university": "SCHOOL", "college": "SCHOOL",
                "parking": "PARKING", "fuel": "FUEL"}[amenity]
    elif leisure == "park":
        kind = "PARK"
    elif leisure in {"pitch","sports_centre","stadium"}:
        kind = "SPORT"
    if kind is None:
        return None
    # Parking and fuel symbols intentionally omit operator, brand, and commercial names.
    name = (tags.get("name:ko") or tags.get("name")) if kind in {"STATION", "SCHOOL", "PARK", "SPORT"} else None
    return kind, name[:80] if name else None


def area_kind(tags):
    if tags.get("natural") in {"water", "bay"} or tags.get("waterway") == "riverbank":
        return "WATER"
    if tags.get("amenity") in {"school","university","college"} and not tags.get("building"):
        return "SCHOOL"
    if tags.get("leisure") in {"pitch","sports_centre","stadium"} and not tags.get("building"):
        return "SPORT"
    if tags.get("amenity") == "parking" and not tags.get("building"):
        return "PARKING"
    if tags.get("building") not in ("no", None) or tags.get("building:part") not in ("no", None):
        return "BUILDING"
    if tags.get("leisure") in {"park", "garden", "recreation_ground", "nature_reserve"} or tags.get("natural") in {
        "wood", "grassland", "scrub", "heath", "wetland",
    } or tags.get("landuse") in {"forest", "grass", "meadow", "recreation_ground", "village_green"}:
        return "PARK"
    return {"residential":"RESIDENTIAL", "commercial":"COMMERCIAL", "retail":"COMMERCIAL",
            "industrial":"INDUSTRIAL", "railway":"INDUSTRIAL", "port":"INDUSTRIAL"}.get(tags.get("landuse"))


def extent(coords):
    xs, ys = zip(*coords)
    return min(xs), min(ys), max(xs), max(ys)


def overlaps(a, b):
    return a[0] <= b[2] and a[2] >= b[0] and a[1] <= b[3] and a[3] >= b[1]


class Extract(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.regions = REGIONS
        self.ways = defaultdict(list)
        self.walks = defaultdict(list)
        self.rails = defaultdict(list)
        self.pois = defaultdict(list)
        self.areas = defaultdict(list)
        self.restrictions = []
        self.incomplete = Counter()

    def areas_for(self, coords):
        bounds = extent(coords)
        return [region[0] for region in self.regions if overlaps(bounds, region[4])]

    def node(self, node):
        if not node.location.valid() or len(node.tags) == 0:
            return
        lon, lat = node.location.lon, node.location.lat
        regions = self.areas_for([(lon, lat)])
        if not regions:
            return
        info = poi_info(dict(node.tags))
        if info:
            for region in regions:
                self.pois[region].append((info, (lon, lat)))

    def way(self, way):
        tags = dict(way.tags)
        attrs = road_attributes(tags)
        pedestrian = (tags.get("highway") in WALK and tags.get("area") != "yes" and
                      tags.get("footway") not in {"sidewalk", "crossing", "traffic_island"})
        rail = (tags.get("railway") in {"rail", "light_rail", "tram", "narrow_gauge"} and
                tags.get("tunnel", "no") in {"no", "false", "0"} and tags.get("service") not in {"yard", "siding"})
        if not (attrs or pedestrian or rail):
            return
        coords = [(node.location.lon, node.location.lat) for node in way.nodes if node.location.valid()]
        if len(coords) != len(way.nodes):
            if coords and self.areas_for(coords):
                self.incomplete["motorRoad" if attrs else "visualLine"] += 1
            return
        if len(coords) < 2:
            return
        if attrs:
            record = (way.id, [node.ref for node in way.nodes], coords, attrs)
            for region in self.areas_for(coords):
                self.ways[region].append(record)
        else:
            for region in self.areas_for(coords):
                (self.rails if rail else self.walks)[region].append(coords)

    def area(self, area):
        tags = dict(area.tags)
        kind = area_kind(tags)
        info = poi_info(tags)
        if kind is None and info is None:
            return
        for outer in area.outer_rings():
            if any(not node.location.valid() for node in outer):
                self.incomplete["polygon"] += 1
                continue
            boundary = [(node.location.lon, node.location.lat) for node in outer]
            regions = self.areas_for(boundary)
            if not regions:
                continue
            inners = [[(node.location.lon, node.location.lat) for node in ring] for ring in area.inner_rings(outer)]
            for region in regions:
                self.areas[region].append((kind, boundary, inners, info))

    def relation(self, relation):
        tags = dict(relation.tags)
        if tags.get("type") != "restriction" or set(tags.get("except", "").split(";")) & {"motorcar", "motor_vehicle"}:
            return
        members = [(item.role, item.type, item.ref) for item in relation.members]
        source = [ref for role, typ, ref in members if role == "from" and typ == "w"]
        target = [ref for role, typ, ref in members if role == "to" and typ == "w"]
        via = [(typ, ref) for role, typ, ref in members if role == "via"]
        restriction = tags.get("restriction:motorcar") or tags.get("restriction:motor_vehicle") or tags.get("restriction", "")
        uncertain = any("conditional" in key for key in tags) or len(via) != 1 or via[0][0] != "n" or not target
        if source:
            self.restrictions.append((source, target, via[0][1] if via and via[0][0] == "n" else None,
                                      restriction, uncertain))


def graph(rows, transform, restrictions):
    nodes = {}
    edges = []
    shared = Counter(node for _, ids, _, _ in rows for node in set(ids))
    split_nodes = {via for _, _, via, _, _ in restrictions if via is not None}
    for way, ids, coords, (grade, structure, level, direction, name) in sorted(rows):
        anchors = {0, len(ids)-1}
        anchors.update(index for index, node in enumerate(ids) if shared[node] > 1 or node in split_nodes)
        if ids[0] == ids[-1] and len(anchors) < 3:
            anchors.add(len(ids) // 2)
        ordered = sorted(anchors)
        for start, end in zip(ordered, ordered[1:]):
            points = []
            for coord in coords[start:end+1]:
                point = transform(*coord)
                if not points or points[-1] != point:
                    points.append(point)
            if len(points) < 2:
                continue
            first, last = (f"N{ids[i]}" for i in (start, end))
            if first == last:
                continue
            nodes[first] = points[0]
            nodes[last] = points[-1]
            physical = f"W{way}_{start}"
            if direction >= 0:
                edges.append(dict(id=physical+"_F", start=first, end=last, points=points, physicalId=physical,
                                  osmWay=way, roadClass=grade, structure=structure, level=level))
                if name:
                    edges[-1]["name"] = name
            if direction <= 0:
                edges.append(dict(id=physical+"_R", start=last, end=first, points=points[::-1], physicalId=physical,
                                  osmWay=way, roadClass=grade, structure=structure, level=level))
                if name:
                    edges[-1]["name"] = name
    restrictions_by_way = defaultdict(list)
    for sources, targets, via, kind, uncertain in restrictions:
        for source in sources:
            restrictions_by_way[source].append((targets, via, kind, uncertain))
    departures = defaultdict(list)
    for edge in edges:
        departures[edge["start"]].append(edge)
    restricted = set()
    transitions = {}
    for edge in edges:
        node = int(edge["end"][1:])
        choices = []
        for next_edge in departures[edge["end"]]:
            if edge["physicalId"] == next_edge["physicalId"]:
                continue
            for targets, via, kind, uncertain in restrictions_by_way[edge["osmWay"]]:
                if via is not None and node != via:
                    continue
                target = next_edge["osmWay"] in targets
                if uncertain or (kind.startswith("only_") and not target) or (kind.startswith("no_") and target):
                    restricted.add(edge["id"])
                    break
            else:
                choices.append(next_edge["id"])
        transitions[edge["id"]] = choices
    return [dict(id=n, point=nodes[n]) for n in sorted(nodes)], edges, transitions, len(restricted)


def parts(shape, name):
    if shape.geom_type == name and not shape.is_empty:
        yield shape
    elif not shape.is_empty and hasattr(shape, "geoms"):
        for child in shape.geoms:
            yield from parts(child, name)


def ring(points):
    dedup = []
    for x, y in points:
        point = [round(x, ROUND_M), round(y, ROUND_M)]
        if not dedup or point != dedup[-1]:
            dedup.append(point)
    return dedup[:-1] if len(dedup) > 1 and dedup[0] == dedup[-1] else dedup


def visual_geometry(data, region, project, clip):
    polygons, points_of_interest, omitted = [], [], Counter()
    for info, coord in data.pois[region]:
        points_of_interest.append((info, project(*coord)))
    for kind, outer, inners, info in data.areas[region]:
        polygon = Polygon([project(*p) for p in outer], [[project(*p) for p in inner] for inner in inners])
        if not polygon.is_valid:
            omitted["invalidArea"] += 1
            continue
        for shape in parts(polygon.intersection(clip).simplify(0.35 if kind == "BUILDING" else 0.8), "Polygon"):
            if shape.area < (6 if kind == "BUILDING" else 20):
                omitted["belowAreaLOD"] += 1
                continue
            if info and (info[0] in {"PARKING", "FUEL", "STATION"} or shape.area >= 200):
                anchor = shape.representative_point()
                points_of_interest.append((info, [round(anchor.x, ROUND_M), round(anchor.y, ROUND_M)]))
            if not kind:
                continue
            points = ring(shape.exterior.coords)
            holes = [ring(inner.coords) for inner in shape.interiors]
            if len(points) < 3 or any(len(hole) < 3 for hole in holes) or not Polygon(points, holes).is_valid:
                omitted["invalidQuantizedArea"] += 1
                continue
            row = dict(type=kind, points=points)
            if holes:
                row["holes"] = holes
            if kind != "BUILDING":
                for level, tolerance in (("mid", 2.0), ("overview", 7.0)):
                    reduced = shape.simplify(tolerance, preserve_topology=True)
                    contours = [ring(reduced.exterior.coords), *[ring(inner.coords) for inner in reduced.interiors]]
                    if sum(map(len, contours)) < len(points) + sum(map(len, holes)) and all(len(c) >= 3 for c in contours) and Polygon(contours[0], contours[1:]).is_valid:
                        row[level] = contours
            polygons.append(row)

    def lines(source):
        output = []
        for coords in source:
            for line in parts(LineString([project(*p) for p in coords]).intersection(clip).simplify(0.8), "LineString"):
                if line.length < 9:
                    continue
                points = [[round(x, ROUND_M), round(y, ROUND_M)] for x, y in line.coords]
                points = [p for i,p in enumerate(points) if i == 0 or p != points[i-1]]
                if len(points) > 1:
                    output.append(points)
        return output

    pois, keys = [], set()
    for (kind, name), point in points_of_interest:
        key = (kind, name, round(point[0]/18), round(point[1]/18))
        if key not in keys:
            keys.add(key)
            row = dict(type=kind, point=point)
            if name:
                row["name"] = name
            pois.append(row)
    return polygons, lines(data.walks[region]), lines(data.rails[region]), pois, dict(omitted)


def convert(source, output):
    fingerprint = digest(source)
    if fingerprint != SOURCE_HASH:
        raise ValueError(f"Unexpected source file SHA-256: {fingerprint}")
    header = osmium.io.Reader(str(source))
    try:
        if header.header().get("osmosis_replication_timestamp") != SNAPSHOT:
            raise ValueError("Unexpected replication timestamp in the pinned extract")
    finally:
        header.close()
    data = Extract()
    data.apply_file(str(source), locations=True)
    if data.incomplete["motorRoad"]:
        raise ValueError("The pinned source contains incomplete motor-road node references")
    tools = {name: version(name) for name in ("osmium", "pyproj", "shapely")}
    manifests = []
    projector = Transformer.from_crs("EPSG:4326", CRS, always_xy=True)
    for region, theme, name, ko, bbox, anchor in REGIONS:
        origin = projector.transform((bbox[0]+bbox[2])/2, (bbox[1]+bbox[3])/2)

        def project(lon, lat):
            x, y = projector.transform(lon, lat)
            return [round(x-origin[0], ROUND_M), round(y-origin[1], ROUND_M)]

        polygon = Polygon([project(bbox[0],bbox[1]), project(bbox[2],bbox[1]),
                           project(bbox[2],bbox[3]), project(bbox[0],bbox[3])])
        nodes, edges, links, restricted = graph(data.ways[region], project, data.restrictions)
        polygons, walks, rails, pois, omitted = visual_geometry(data, region, project, polygon)
        origin = list(origin)
        bounds = list(polygon.bounds)
        source_meta = dict(dataset="OSM", url=SOURCE, snapshot=SNAPSHOT, acquired=ACQUIRED, sha256=fingerprint,
                           license="ODbL-1.0", licenseUrl="https://opendatacommons.org/licenses/odbl/1-0/",
                           attribution="© OpenStreetMap contributors")
        model = dict(schemaVersion=3, visualVersion=1, mapId=region, name=name, nameKo=ko,
                     source=source_meta, projection=dict(crs=CRS, origin=origin, bounds=bounds, bbox=bbox),
                     nodes=nodes, edges=edges, transitions=links, polygons=polygons,
                     labels=[dict(text=ko, point=project(*anchor))], walkways=walks, railways=rails, pois=pois)
        path = output / "maps" / f"{region}.json"
        write_json(path, model)
        stats = dict(nodes=len(nodes), directedEdges=len(edges), segments=sum(len(e["points"])-1 for e in edges),
                     polygons=len(polygons), buildings=sum(p["type"]=="BUILDING" for p in polygons),
                     holes=sum(len(p.get("holes", [])) for p in polygons), walkways=len(walks),
                     railways=len(rails), pois=len(pois), koreanRoadNames=len({e["name"] for e in edges
                         if "name" in e and any("가" <= ch <= "힣" for ch in e["name"])}))
        ways = {item[0] for item in data.ways[region]}
        restrictions = [rule for rule in data.restrictions if ways.intersection(rule[0])]
        regional_input = json.dumps([data.ways[region],data.areas[region],data.walks[region],data.rails[region],data.pois[region],restrictions], ensure_ascii=False,
                                    separators=(",", ":")).encode()
        manifests.append(dict(id=region, name=name, nameKo=ko, theme=theme, bbox=bbox, crs=CRS, origin=origin,
                              normalizedExtractSha256=sha256(regional_input).hexdigest(), file=f"maps/{region}.json",
                              outputSha256=digest(path), bytes=path.stat().st_size, counts=stats,
                              restrictedIncomingEdges=restricted, omitted=omitted))
        print(region, stats, path.stat().st_size)
    write_json(output / "manifest.json", dict(schemaVersion=1, source=source_meta, tools=tools,
               parameters=dict(precisionMeters=.01, buildingAreaMinM2=6, walkwayMinMeters=9,
                               areaSimplifyMeters=.8, buildingSimplifyMeters=.35, extract="complete ways by bbox", sidewalkFeatures="excluded", landLodMeters=[2.0,7.0]),
               incompleteSourceWays=dict(data.incomplete), regions=manifests))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("acquire","import"))
    parser.add_argument("--source", type=Path, default=Path("build/osm-source/south-korea-261101.osm.pbf"))
    parser.add_argument("--output", type=Path, default=Path("fixtures/osm"))
    args = parser.parse_args()
    if args.command == "acquire":
        acquire(args.source)
    else:
        convert(args.source, args.output)
