#!/bin/sh
# A/B of one move (tools/move-frames.sh) between APKs, interleaved: the TV's own load and refresh rate change from
# minute to minute, so only alternating runs compare. Prints, per run: the vsync, the cards' movement (first 250 ms)
# and what follows (the picture), as median total / median gpu / late frames.
# Usage: tools/ab-move.sh a.apk b.apk [rounds]
S=${S:-192.168.1.13:5555}
cd "$(dirname "$0")/.."
i=0
while [ $i -lt "${3:-3}" ]; do
  for apk in "$1" "$2"; do
    adb -s $S install -r -d "$apk" >/dev/null 2>&1
    tools/move-frames.sh | python3 -c '
import sys, statistics as st
rows = []
for l in sys.stdin:
    p = l.split()
    if len(p) >= 10 and p[1] == "ms":
        rows.append((float(p[0]), float(p[3]), float(p[5]), float(p[9])))
if not rows: sys.exit()
gaps = [g for t, g, tot, gpu in rows[1:] if g > 0]
v = st.median(gaps)
def part(a, b):
    r = [x for x in rows if a <= x[0] < b]
    if not r: return "-"
    late = sum(1 for t, g, tot, gpu in r if g > 1.5 * v)
    return "total %5.1f gpu %5.1f late %d/%d" % (st.median([x[2] for x in r]), st.median([x[3] for x in r if x[3] > 0] or [0]), late, len(r))
print("%-28s vsync %4.1f | move %s | after %s" % (sys.argv[1].split("/")[-1], v, part(0, 250), part(250, 1200)))
' "$apk"
  done
  i=$((i + 1))
done
