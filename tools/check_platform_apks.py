"""Verify the Live manifests, trimmed public map assets, and APK isolation."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from xml.etree import ElementTree
from zipfile import ZipFile

from check_apk import ANDROID

PERMISSIONS = {
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
    "lab.recovery.live.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
PLAN_FIELDS = {
    "schemaVersion", "scenarioId", "mapId", "mapChecksum", "routeVersion", "plannedRoute",
}
HTTP_CLASSES = (b"Lokhttp3/", b"Llab/recovery/routelab/", b"Lmockwebserver3/")
FAULT_CLASS = b"Llab/recovery/live/FaultHarnessActivity;"
DEBUG_CLASS = b"Llab/recovery/live/LiveDebugApplication;"


def manifest(apk, analyzer):
    return ElementTree.fromstring(subprocess.check_output([analyzer, "manifest", "print", apk], text=True))


def permissions(root):
    return {element.get(ANDROID + "name") for element in root if element.tag.startswith("uses-permission")}


def dexes(archive):
    return [archive.read(name) for name in archive.namelist() if name.startswith("classes") and name.endswith(".dex")]


def verify_live(apk, analyzer, debug):
    root = manifest(apk, analyzer)
    assert root.get("package") == "lab.recovery.live", apk
    assert permissions(root) == PERMISSIONS, (apk, permissions(root) ^ PERMISSIONS)
    application = root.find("application")
    assert application.get(ANDROID + "usesCleartextTraffic") == "false", apk
    assert application.get(ANDROID + "allowBackup") == "false", apk
    assert (application.get(ANDROID + "debuggable") == "true") == debug, apk
    workers = [item for item in application.findall("service") if item.get(ANDROID + "name", "").startswith("lab.recovery.live.")]
    assert len(workers) == 1, apk
    assert workers[0].get(ANDROID + "name") == "lab.recovery.live.LiveTrackingService", apk
    assert workers[0].get(ANDROID + "foregroundServiceType") in {"location", "8", "0x8", "0x00000008"}, apk
    assert workers[0].get(ANDROID + "exported") == "false", apk
    entries = {entry.get(ANDROID + "name"): entry for entry in application.findall("activity")}
    assert entries["lab.recovery.live.LiveActivity"].get(ANDROID + "screenOrientation") in {"portrait", "1"}, apk
    fault = entries.get("lab.recovery.live.FaultHarnessActivity")
    assert (fault is not None) == debug, apk
    if fault is not None:
        assert fault.get(ANDROID + "permission") == "android.permission.DUMP", apk
        assert fault.get(ANDROID + "process") == ":fault", apk
    with ZipFile(apk) as archive:
        code = dexes(archive)
        assert code and not any(symbol in dex for dex in code for symbol in HTTP_CLASSES), apk
        assert any(FAULT_CLASS in dex for dex in code) == debug, apk
        assert any(DEBUG_CLASS in dex for dex in code) == debug, apk
        osm = Path("fixtures/osm")
        audited = [osm / "catalog.json", osm / "NOTICE.txt", *sorted((osm / "maps").glob("*.json"))]
        expected = {"assets/osm/" + path.relative_to(osm).as_posix() for path in audited}
        for path in audited:
            assert archive.read("assets/osm/" + path.relative_to(osm).as_posix()) == path.read_bytes(), (apk, path)
        for path in (osm / "scenarios").glob("*.json"):
            name = "assets/osm/" + path.relative_to(osm).as_posix()
            expected.add(name)
            original = json.loads(path.read_bytes())
            assert json.loads(archive.read(name)) == {key: original[key] for key in PLAN_FIELDS}, (apk, path)
        expected.add("assets/projection-controls.json")
        assert archive.read("assets/projection-controls.json") == Path("live/src/main/assets/projection-controls.json").read_bytes(), apk
        baseline_profiles = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
        actual = {name for name in archive.namelist() if name.startswith("assets/")}
        assert expected == actual - baseline_profiles, (apk, expected ^ (actual - baseline_profiles))
    print(f"{apk}: location-only FGS, {len(expected)} audited Live assets, fault harness {'present' if debug else 'absent'}")


def verify_offline_isolation(apk, analyzer):
    root = manifest(apk, analyzer)
    assert not permissions(root) & (PERMISSIONS - {"lab.recovery.live.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"})
    with ZipFile(apk) as archive:
        assert not any(symbol in dex for dex in dexes(archive) for symbol in (*HTTP_CLASSES, b"Llab/recovery/live/")), apk
    print(f"{apk}: no Live, fault or HTTP code")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apkanalyzer", default=os.environ.get("APKANALYZER", "apkanalyzer"))
    parser.add_argument("--live-debug", type=Path, required=True)
    parser.add_argument("--live-release", type=Path, required=True)
    parser.add_argument("--offline", type=Path, nargs="+", required=True)
    args = parser.parse_args()
    verify_live(args.live_debug, args.apkanalyzer, debug=True)
    verify_live(args.live_release, args.apkanalyzer, debug=False)
    for offline in args.offline:
        verify_offline_isolation(offline, args.apkanalyzer)
