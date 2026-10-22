#!/bin/sh
# Opens a series' page through the search (default: "diplomate", a series with seasons), goes down to the episodes,
# along them, through the seasons and back up, and prints where the frames of the home window went (the page only:
# the measure stops before Back). Usage: tools/detail-bench.sh [query]   (frame by frame: tools/frames.py)
S=${S:-192.168.1.13:5555}
Q=${1:-diplomate}
cd "$(dirname "$0")/.."
adb -s $S shell "am force-stop io.freemytv; input keyevent 3; sleep 11; input keyevent 19; sleep 0.5; \
  input keyevent 21 21 21 21 21; sleep 0.5; input keyevent 23; sleep 1.5; input text $Q; sleep 0.5; input keyevent 66; \
  sleep 2; input keyevent 20; sleep 0.6; input keyevent 23; sleep 4; \
  dumpsys gfxinfo io.freemytv:home reset >/dev/null; \
  input keyevent 20; sleep 0.8; input keyevent 20; sleep 1.2; input keyevent 22; sleep 0.7; input keyevent 22; sleep 0.7; \
  input keyevent 22; sleep 0.7; input keyevent 21; sleep 0.7; input keyevent 19; sleep 0.8; input keyevent 22; sleep 0.8; \
  input keyevent 22; sleep 0.8; input keyevent 20; sleep 0.8; input keyevent 22; sleep 0.7; input keyevent 19; sleep 0.7; \
  input keyevent 19; sleep 1.2; \
  dumpsys gfxinfo io.freemytv:home framestats > /data/local/tmp/fs.txt; input keyevent 4" 2>/dev/null
adb -s $S shell cat /data/local/tmp/fs.txt | python3 tools/framestats.py HomeActivity
