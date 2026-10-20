#!/usr/bin/env bash
# Install Free My TV on a Google TV / Android TV.
#   ./install_tv.sh 192.168.1.50      -> install on that TV
#   ./install_tv.sh                   -> scan the LAN for an Android TV with ADB on
set -uo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$PATH"

IP="${1:-}"

if [ -z "$IP" ]; then
  echo "Scanning the LAN for a TV with ADB on (port 5555)…"
  IP="$(python3 - <<'PY'
import socket, concurrent.futures
def chk(ip):
    s=socket.socket(); s.settimeout(0.4)
    try: s.connect((ip,5555)); s.close(); return ip
    except Exception: return None
with concurrent.futures.ThreadPoolExecutor(max_workers=150) as ex:
    for r in ex.map(chk,[f"192.168.1.{i}" for i in range(1,255)]):
        if r: print(r); break
PY
)"
  [ -z "$IP" ] && { echo "No TV found. USB/network debugging is probably off on the TV."; exit 1; }
fi

echo "== connecting to $IP:5555 =="
adb connect "$IP:5555" || exit 1
sleep 2
adb -s "$IP:5555" shell echo ok >/dev/null 2>&1 || {
  echo "Connected but not authorised: accept the 'Allow USB debugging?' prompt on the TV (tick Always allow), then re-run."
  exit 1
}

echo "== TV =="
adb -s "$IP:5555" shell 'getprop ro.product.brand; getprop ro.product.model; getprop ro.build.version.release' | paste -sd' ' -
echo "== installing =="
adb -s "$IP:5555" install -r FreeMyTV.apk || exit 1

BRAND="$(adb -s "$IP:5555" shell getprop ro.product.brand | tr -d '\r')"
case "$BRAND" in
  *TCL*|*tcl*) echo "== TCL: allowing background start ==";
    adb -s "$IP:5555" shell 'appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow' ;;
esac

echo
echo "Installed. On the TV: Free My TV -> Home Screen -> Free My TV."
echo "Back to stock any time: Free My TV -> Home Screen -> Original."
