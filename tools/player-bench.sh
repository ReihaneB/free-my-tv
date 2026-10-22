#!/bin/sh
# Opens the first film of the home screen (its page, then Lecture), then goes through the player's controls (show, buttons, a popover,
# scrub, pause, hide) and prints where the frames of the player's window went. Usage: tools/player-bench.sh
S=${S:-192.168.1.13:5555}
cd "$(dirname "$0")/.."
adb -s $S shell "am force-stop io.freemytv; input keyevent 3; sleep 11; input keyevent 23; sleep 3; input keyevent 23; sleep 13; \
  dumpsys gfxinfo io.freemytv:home reset >/dev/null; \
  input keyevent 20; sleep 1.5; input keyevent 19; sleep 1.2; input keyevent 22; sleep 1.2; input keyevent 23; sleep 1.5; \
  input keyevent 20; sleep 1; input keyevent 4; sleep 1.2; input keyevent 20; sleep 1.2; input keyevent 22; sleep 2.5; \
  input keyevent 23; sleep 1.5; input keyevent 23; sleep 1.5; input keyevent 20; sleep 1.5; \
  dumpsys activity activities | grep -m1 mResumedActivity; \
  dumpsys gfxinfo io.freemytv:home framestats > /data/local/tmp/fs.txt" 2>/dev/null
adb -s $S shell cat /data/local/tmp/fs.txt | python3 tools/framestats.py Player
