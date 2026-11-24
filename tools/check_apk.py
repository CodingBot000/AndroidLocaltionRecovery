"""Verify that both APKs package the audited fixture bytes and use the offline portrait manifest."""
import argparse
import os
from pathlib import Path
import subprocess
from xml.etree import ElementTree
from zipfile import ZipFile

ANDROID = "{http://schemas.android.com/apk/res/android}"
FORBIDDEN = {
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
    "ACCESS_MEDIA_LOCATION", "INTERNET", "CAMERA", "RECORD_AUDIO",
}


def verify(apk, apkanalyzer, fixtures):
    manifest_text = subprocess.check_output([apkanalyzer, "manifest", "print", apk], text=True)
    manifest = ElementTree.fromstring(manifest_text)
    assert manifest.get("package") == "lab.recovery.app", apk
    assert not (FORBIDDEN & {
        element.get(ANDROID + "name", "").removeprefix("android.permission.")
        for element in manifest if element.tag.startswith("uses-permission")
    }), apk
    activities = manifest.findall("./application/activity")
    main = next(activity for activity in activities if activity.get(ANDROID + "name", "").endswith(".MainActivity"))
    assert main.get(ANDROID + "screenOrientation") in {"portrait", "1"}, apk

    with ZipFile(apk) as archive:
        files = {str(path.relative_to(fixtures)): path.read_bytes() for path in fixtures.rglob("*.json")}
        packaged = {name.removeprefix("assets/") for name in archive.namelist()
                    if name.startswith("assets/") and name.endswith(".json")}
        assert packaged == files.keys(), (apk, packaged ^ files.keys())
        for name, data in files.items():
            assert archive.read(f"assets/{name}") == data, (apk, name)
    print(f"{apk}: portrait, offline permissions, {len(files)} byte-identical fixtures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apkanalyzer", default=os.environ.get("APKANALYZER", "apkanalyzer"))
    parser.add_argument("apks", type=Path, nargs="+")
    args = parser.parse_args()
    for apk in args.apks:
        verify(apk, args.apkanalyzer, Path("fixtures"))
