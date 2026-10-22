#!/bin/sh
# Browsing the home as a person does: a move, a rest of ~1 s, along two shelves and back (each rest lets the
# spotlight change its text and its picture). Prints gfxinfo's summary for the home window over the whole walk.
# Usage: tools/browse-bench.sh [label]
S=${S:-192.168.1.13:5555}
adb -s $S shell "am force-stop io.freemytv; input keyevent 3; sleep 12; input keyevent 20; sleep 0.6; input keyevent 20; sleep 3; \
  dumpsys gfxinfo io.freemytv:home reset >/dev/null; \
  for k in 22 22 22 22 22 22 20 22 22 22 21 21 20 22 22 22 19 21 19 22; do input keyevent \$k; sleep 1.1; done; \
  dumpsys gfxinfo io.freemytv:home > /data/local/tmp/gfx.txt" 2>/dev/null
echo "== ${1:-run}"
adb -s $S shell cat /data/local/tmp/gfx.txt | grep -E "Total frames|Janky|50th perc|90th perc|95th perc|99th perc|Missed Vsync|Slow UI|Slow bitmap|Slow issue|deadline missed" | grep -v "gpu" | head -12
