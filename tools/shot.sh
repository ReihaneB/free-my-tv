#!/usr/bin/env bash
# Screenshot of the TV into $1 (path). `pkill -x`, not `-f`: `-f screencap` matches the shell running this very
# command and kills it before the capture, leaving the previous file to be pulled.
set -u
S="${TV_SERIAL:-192.168.1.13:5555}"
adb -s "$S" shell 'pkill -x screencap; rm -f /sdcard/x.png; screencap -p /sdcard/x.png' >/dev/null 2>&1
adb -s "$S" pull /sdcard/x.png "$1" >/dev/null && md5 -q "$1"
