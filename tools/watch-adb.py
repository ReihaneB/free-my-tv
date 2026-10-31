#!/usr/bin/env python3
"""Guette l'ouverture du port adb 5555 sur la TV et installe SmartTube dès qu'il répond.

Borné dans le temps (25 min). Chaque étape est journalisée dans /tmp/tv-adb-watch.log.
"""
import socket
import subprocess
import sys
import time

TV = "192.168.1.13:5555"
IP, PORT = "192.168.1.13", 5555
APK = "/Users/reihane/workspace/free-my-tv/tmp-updates/SmartTube_32.68_universal.apk"
LOG = "/tmp/tv-adb-watch.log"
DEADLINE = time.time() + 25 * 60


def log(msg):
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    print(line, flush=True)
    with open(LOG, "a") as fh:
        fh.write(line + "\n")


def port_open(timeout=3.0):
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((IP, PORT))
        return True
    except Exception:
        return False
    finally:
        s.close()


def run(args, timeout=180):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "").strip(), (p.stderr or "").strip()
    except subprocess.TimeoutExpired:
        return 124, "", "timeout"


log(f"guetteur démarré, APK={APK}")
seen = 0
while time.time() < DEADLINE:
    if port_open():
        seen += 1
        log(f"5555 ouvert (vue {seen})")
        run(["adb", "connect", TV], timeout=30)
        time.sleep(2)
        rc, out, err = run(["adb", "-s", TV, "shell", "getprop ro.product.model"], timeout=90)
        log(f"shell rc={rc} out={out!r} err={err!r}")
        if rc == 0 and out:
            rc, out, err = run(["adb", "-s", TV, "shell",
                                "dumpsys package org.smarttube.stable | grep -m1 versionName"], timeout=90)
            log(f"avant: {out or err}")
            rc, out, err = run(["adb", "-s", TV, "install", "-r", APK], timeout=600)
            log(f"install rc={rc} out={out} err={err}")
            rc, out, err = run(["adb", "-s", TV, "shell",
                                "dumpsys package org.smarttube.stable | grep -m1 versionName"], timeout=90)
            log(f"apres: {out or err}")
            log("DONE")
            sys.exit(0)
    time.sleep(4)

log("TIMEOUT: le port 5555 ne s'est jamais ouvert")
sys.exit(1)
