#!/bin/sh
# Home screen: moves along a poster shelf and between shelves (each move changes the spotlight and, once the focus
# rests, the picture behind), and prints where the frames of the home window went. Usage: tools/home-bench.sh
S=${S:-192.168.1.13:5555}
cd "$(dirname "$0")/.."
adb -s $S shell "am force-stop io.freemytv; input keyevent 3; sleep 12; input keyevent 20; sleep 0.6; input keyevent 20; sleep 3; \
  dumpsys gfxinfo io.freemytv:home reset >/dev/null; \
  input keyevent 22; sleep 0.5; input keyevent 22; sleep 0.5; input keyevent 22; sleep 1.2; input keyevent 22; sleep 0.5; \
  input keyevent 20; sleep 1.2; input keyevent 22; sleep 0.5; input keyevent 22; sleep 1.2; input keyevent 20; sleep 1.2; \
  input keyevent 19; sleep 1.2; input keyevent 21; sleep 1.2; \
  dumpsys gfxinfo io.freemytv:home framestats > /data/local/tmp/fs.txt" 2>/dev/null
adb -s $S shell cat /data/local/tmp/fs.txt | python3 tools/framestats.py HomeActivity
