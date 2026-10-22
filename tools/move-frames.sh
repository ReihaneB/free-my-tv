#!/bin/sh
# One move to the right on the films shelf, every frame of it: the cards' movement, then the picture's cross-fade.
# Prints one line per frame (ms since the first frame, gap, total, gpu). Usage: tools/move-frames.sh
S=${S:-192.168.1.13:5555}
cd "$(dirname "$0")/.."
adb -s $S shell "am force-stop io.freemytv; input keyevent 3; sleep 12; input keyevent 20; sleep 0.6; input keyevent 20; sleep 0.6; \
  input keyevent 22; sleep 4; dumpsys gfxinfo io.freemytv:home reset >/dev/null; \
  input keyevent 22; sleep 1.2; dumpsys gfxinfo io.freemytv:home framestats > /data/local/tmp/fs.txt" 2>/dev/null
adb -s $S shell cat /data/local/tmp/fs.txt | python3 tools/frames.py HomeActivity
