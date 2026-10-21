#!/usr/bin/env bash
# The home drives itself (Perf.AUTO) for SECS seconds; prints the Perf lines and the gfxinfo GPU summary.
# Needs a build with Perf.ON = true. Usage: tools/bench-auto.sh [label] [extra skip bits]
set -u
S="${TV_SERIAL:-192.168.1.13:5555}"
A="adb -s $S"
$A shell settings put global freemytv_skip $((128 + ${2:-0}))
$A shell am force-stop io.freemytv
$A shell input keyevent KEYCODE_HOME
sleep 8
$A logcat -c
$A shell dumpsys gfxinfo io.freemytv:home reset >/dev/null
sleep "${SECS:-40}"
$A shell settings put global freemytv_skip 0
$A shell input keyevent KEYCODE_HOME
echo "== ${1:-run}"
$A logcat -d -s FreeMyTV | grep "perf gap" | sed 's/.*perf gap/gap/'
$A shell dumpsys gfxinfo io.freemytv:home | grep -E "Janky|percentile|Slow|Missed|deadline" | grep -v "99th gpu"
