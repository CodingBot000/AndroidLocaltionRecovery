"""Regenerate public EPSG:5179 controls from the audited map bounds and road geometry."""
import json
from pathlib import Path

from pyproj import Transformer

root = Path(__file__).resolve().parents[2]
osm = root / "fixtures/osm"
forward = Transformer.from_crs(4326, 5179, always_xy=True)
inverse = Transformer.from_crs(5179, 4326, always_xy=True)
controls = {}
for region in json.loads((osm / "manifest.json").read_text())["regions"]:
    west, south, east, north = region["bbox"]
    ox, oy = region["origin"]
    definition = json.loads((osm / region["file"]).read_text())
    mid_road = definition["edges"][len(definition["edges"]) // 2]["points"][0]
    road_lon, road_lat = inverse.transform(ox + mid_road[0], oy + mid_road[1])
    points = [(west, south), (west, north), (east, south), (east, north),
              ((west + east) / 2, (south + north) / 2), (road_lon, road_lat)]
    samples = []
    for lon, lat in points:
        x, y = forward.transform(lon, lat)
        samples.append([round(lon, 11), round(lat, 11), round(x - ox, 5), round(y - oy, 5)])
    controls[region["id"]] = dict(checksum=region["outputSha256"], bbox=region["bbox"], samples=samples)
output = root / "live/src/main/assets/projection-controls.json"
output.write_text(json.dumps(controls, separators=(",", ":")) + "\n")
