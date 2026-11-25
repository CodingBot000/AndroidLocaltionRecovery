#!/usr/bin/env bash
set -euo pipefail
out=build/reports/compose-rca
raw="$RUNNER_TEMP/compose-rca-raw.txt"
mkdir -p "$out"
{
  adb shell getprop ro.build.fingerprint
  adb shell getprop dalvik.vm.heapgrowthlimit
  adb shell wm size
  adb shell wm density
} > "$out/environment.txt"
adb logcat -b all -v threadtime -T 1 > "$raw" 2>&1 &
log_pid=$!
close_log() {
  kill "$log_pid" 2>/dev/null || true
  wait "$log_pid" 2>/dev/null || true
}
trap close_log EXIT
result=0
LIVE_TEST_REPORT=build/reports/platform-live-api36.txt tools/run_live_instrumentation.sh || result=$?
close_log
trap - EXIT
awk '/ [IWEF] (LiveRca|AndroidRuntime|TestRunner|LiveVerification) *:/ {print}' "$raw" > "$out/timeline.txt"
exit "$result"
