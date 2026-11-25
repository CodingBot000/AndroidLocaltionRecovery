"""Exercise Live permissions, notification stop and process recovery on a disposable API 33+ emulator."""
import argparse
import os
import re
import subprocess
import time
from xml.etree import ElementTree

ADB = os.environ.get("ADB", "adb")
PACKAGE = "lab.recovery.live"
ACTIVITY = PACKAGE + "/.LiveActivity"
WINDOW = "/data/local/tmp/location-live-window.xml"


def adb(*args):
    return subprocess.check_output([ADB, *args], text=True, stderr=subprocess.STDOUT, timeout=20).strip()


def shell(*args):
    return adb("shell", *args)


def await_condition(check, description, seconds=20):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        result = check()
        if result is not None and result is not False:
            return result
        time.sleep(.15)
    raise AssertionError(description)


def window():
    try:
        shell("uiautomator", "dump", WINDOW)
        return list(ElementTree.fromstring(shell("cat", WINDOW)).iter("node"))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ElementTree.ParseError):
        return []


def find(*, resource=None, text=None):
    def matching(node):
        name = node.get("resource-id", "").split("/")[-1]
        contents = node.get("text", "")
        return (resource is None or name == resource) and (text is None or text in contents)

    return next((node for node in window() if matching(node)), None)


def node(**criteria):
    return await_condition(lambda: find(**criteria), f"Missing View {criteria}")


def click(**criteria):
    bounds = [int(part) for part in re.findall(r"\d+", node(**criteria).get("bounds", ""))]
    assert len(bounds) == 4, bounds
    x1, y1, x2, y2 = bounds
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def is_foreground():
    return "isForeground=true" in shell("dumpsys", "activity", "services", PACKAGE)


def wait_foreground(expected):
    await_condition(lambda: is_foreground() == expected, f"Foreground did not become {expected}")


def launch():
    shell("am", "start", "-W", "-n", ACTIVITY)
    node(resource="live-map")


def select():
    if node(resource="live-start").get("enabled") != "true":
        click(resource="live-map")
        click(text="Yeouido")


def start():
    click(resource="live-start")


def check_permissions():
    launch()
    select()
    assert not is_foreground()
    start()
    click(resource="permission_deny_button")
    node(text="Location permission denied")
    assert not is_foreground()

    start()
    click(resource="permission_location_accuracy_radio_coarse")
    click(resource="permission_allow_foreground_only_button")
    node(text="Approximate permission granted")
    start()
    wait_foreground(True)
    try:
        node(resource="live-precision", text="Approximate location")
    except AssertionError as error:
        visible = [item.get("text", "") for item in window() if item.get("text")]
        raise AssertionError(f"Approximate diagnostics unavailable: {visible}") from error
    click(resource="live-stop")
    wait_foreground(False)
    print("PASS explicit location request, denial, approximate permission, and raw-only FGS", flush=True)

    shell("pm", "grant", PACKAGE, "android.permission.ACCESS_FINE_LOCATION")
    shell("pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    launch()
    select()
    start()
    wait_foreground(True)
    click(resource="live-stop")
    wait_foreground(False)
    print("PASS FGS and in-app Stop with notification permission denied", flush=True)


def check_interruption():
    for method in ("task-manager", "force-stop", "kill"):
        launch()
        select()
        start()
        wait_foreground(True)
        node(resource="live-phase", text="Active")
        old_pid = shell("pidof", PACKAGE)
        if method == "task-manager":
            shell("cmd", "activity", "stop-app", PACKAGE)
        elif method == "force-stop":
            shell("am", "force-stop", PACKAGE)
        else:
            shell("run-as", PACKAGE, "kill", "-9", old_pid)
        wait_foreground(False)
        launch()
        node(text="Previous session interrupted")
        assert shell("pidof", PACKAGE) != old_pid
        assert not is_foreground()
        print(f"PASS {method}: same session stayed stopped after relaunch", flush=True)
    select()
    start()
    wait_foreground(True)
    click(resource="live-stop")
    wait_foreground(False)
    print("PASS explicit new session after interrupted process", flush=True)


def check_location_policy():
    original = shell("cmd", "location", "is-location-enabled")
    launch()
    select()
    start()
    wait_foreground(True)
    node(resource="live-phase", text="Active")
    try:
        shell("cmd", "location", "set-location-enabled", "false")
        wait_foreground(False)
        try:
            node(text="Location disabled")
        except AssertionError as error:
            visible = [item.get("text", "") for item in window() if item.get("text")]
            raise AssertionError(f"Location switch UI: {visible}") from error
    finally:
        shell("cmd", "location", "set-location-enabled", original)
    assert not is_foreground()

    start()
    wait_foreground(True)
    node(resource="live-phase", text="Active")
    shell("pm", "revoke", PACKAGE, "android.permission.ACCESS_FINE_LOCATION")
    wait_foreground(False)
    launch()
    assert not is_foreground()
    select()
    start()
    wait_foreground(True)
    node(resource="live-precision", text="Approximate location")
    click(resource="live-stop")
    wait_foreground(False)
    shell("pm", "grant", PACKAGE, "android.permission.ACCESS_FINE_LOCATION")
    print("PASS location switch and precise-to-approximate change stop the old session", flush=True)


def check_notification_stop():
    shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    launch()
    select()
    start()
    wait_foreground(True)
    shell("cmd", "statusbar", "expand-notifications")
    entry = node(text="Live location session active")
    if find(text="Stop tracking") is None:
        bounds = [int(part) for part in re.findall(r"\d+", entry.get("bounds", ""))]
        assert len(bounds) == 4, bounds
        x = (bounds[0] + bounds[2]) // 2
        y = (bounds[1] + bounds[3]) // 2
        shell("input", "swipe", str(x), str(y), str(x), str(y + 200), "400")
    click(text="Stop tracking")
    wait_foreground(False)
    shell("cmd", "statusbar", "collapse")
    print("PASS notification Stop action released foreground service", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("all", "permissions", "interruption", "location", "notification"), default="all")
    phase = parser.parse_args().phase
    assert adb("get-serialno").startswith("emulator-"), "Use a disposable emulator."
    assert int(shell("getprop", "ro.build.version.sdk")) >= 33
    assert shell("pm", "clear", PACKAGE) == "Success"
    try:
        if phase not in {"all", "permissions"}:
            shell("pm", "grant", PACKAGE, "android.permission.ACCESS_COARSE_LOCATION")
            shell("pm", "grant", PACKAGE, "android.permission.ACCESS_FINE_LOCATION")
        checks = {
            "permissions": check_permissions,
            "interruption": check_interruption,
            "location": check_location_policy,
            "notification": check_notification_stop,
        }
        for name, check in checks.items():
            if phase in {"all", name}:
                check()
    finally:
        shell("cmd", "statusbar", "collapse")
        shell("am", "force-stop", PACKAGE)
        shell("pm", "revoke", PACKAGE, "android.permission.ACCESS_FINE_LOCATION")
        shell("pm", "revoke", PACKAGE, "android.permission.ACCESS_COARSE_LOCATION")
        shell("pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        shell("rm", "-f", WINDOW)


if __name__ == "__main__":
    main()
