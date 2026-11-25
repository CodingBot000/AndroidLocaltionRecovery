#!/usr/bin/env bash
set -euo pipefail

# Run only with one disposable emulator connected or ANDROID_SERIAL set to its emulator serial.
adb="${ADB:-adb}"
serial="$("$adb" get-serialno)"
case "$serial" in
  emulator-*) ;;
  *) echo "Live instrumentation requires a disposable Android emulator." >&2; exit 1 ;;
esac

"$adb" install -r live/build/outputs/apk/debug/live-debug.apk
"$adb" install -r live/build/outputs/apk/androidTest/debug/live-debug-androidTest.apk

restore() {
  "$adb" shell am force-stop lab.recovery.live
  "$adb" shell pm revoke lab.recovery.live android.permission.ACCESS_FINE_LOCATION
  "$adb" shell pm revoke lab.recovery.live android.permission.ACCESS_COARSE_LOCATION
}
trap restore EXIT

"$adb" shell pm grant lab.recovery.live android.permission.ACCESS_COARSE_LOCATION
"$adb" shell pm grant lab.recovery.live android.permission.ACCESS_FINE_LOCATION

if [[ -n "${LIVE_TEST_REPORT:-}" ]]; then
  output="$LIVE_TEST_REPORT"
  mkdir -p "$(dirname "$output")"
else
  output="$(mktemp)"
  trap 'rm -f "$output"; restore' EXIT
fi
set --
if [[ -n "${LIVE_EXPECTED_HEAP_MIB:-}" ]]; then
  set -- -e expectedHeapMiB "$LIVE_EXPECTED_HEAP_MIB"
fi
"$adb" shell am instrument -w -r -e class lab.recovery.live.LiveLifecycleTest "$@" \
  lab.recovery.live.test/androidx.test.runner.AndroidJUnitRunner | tee "$output"
grep -q '^OK (' "$output"
