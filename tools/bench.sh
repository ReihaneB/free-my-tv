#!/usr/bin/env bash
# Drives the home with the remote's arrows and prints the Perf lines + gfxinfo summary.
# Needs a build with Perf.ON = true. Usage: tools/bench.sh [label]
set -u
S="${TV_SERIAL:-192.168.1.13:5555}"
A="adb -s $S"
$A shell input keyevent KEYCODE_HOME
sleep 6
$A logcat -c
$A shell dumpsys gfxinfo io.freemytv:home reset >/dev/null
# one shell, so adb's own round trips do not pace the keys
$A shell "ROUNDS=${ROUNDS:-2}; "'
k() { input keyevent "$1"; sleep 0.25; }
for r in $(seq 1 ${ROUNDS:-2}); do
  for i in 1 2 3 4 5 6; do k KEYCODE_DPAD_RIGHT; done
  for i in 1 2 3; do k KEYCODE_DPAD_DOWN; done
  for i in 1 2 3 4; do k KEYCODE_DPAD_LEFT; done
  for i in 1 2 3; do k KEYCODE_DPAD_RIGHT; done
  for i in 1 2 3; do k KEYCODE_DPAD_UP; done
  k KEYCODE_DPAD_UP
  for i in 1 2 3 4 5 6 7; do k KEYCODE_DPAD_RIGHT; done
  for i in 1 2 3 4 5 6 7; do k KEYCODE_DPAD_LEFT; done
  k KEYCODE_DPAD_DOWN
done'
sleep 1
echo "== ${1:-run}"
$A logcat -d -s FreeMyTV | grep "perf gap"
$A shell dumpsys gfxinfo io.freemytv:home | grep -E "Total frames|Janky|percentile" | grep -v "99th gpu"
