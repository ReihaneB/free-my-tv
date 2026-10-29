#!/usr/bin/env bash
# Builds Free My TV and puts it on the TV, and keeps a way back: every install is a git tag away from undo.
#
#   ./deploy.sh status              what the TV runs now (version, mode, home, screensaver)
#   ./deploy.sh install [IP]        build the working tree and install it (IP defaults to config or 192.168.1.13)
#   ./deploy.sh save "message"      commit + tag "v<date>" + install, i.e. a known-good point to come back to
#   ./deploy.sh rollback <tag>      rebuild that tag and install it (adb -d: a lower version code is allowed)
#   ./deploy.sh tags                the tags you can roll back to
#
# The keystore stays the same for every build: an older version installs over a newer one only because of it.
set -euo pipefail
cd "$(dirname "$0")"

TV="${TV_IP:-192.168.1.13}"
MIN_SDK="${MIN_SDK:-30}"          # this TV is Android 11
export MIN_SDK

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

connect() {
  adb connect "$TV:5555" >/dev/null 2>&1 || true
  adb wait-for-device
  [ -n "$(adb shell getprop ro.product.brand 2>/dev/null | tr -d '\r')" ] || die "TV $TV not answering over adb"
}

install_apk() {   # $1 = apk, $2 = extra adb install flags
  connect
  adb install -r ${2:-} "$1" | tail -1
  # Remettre l'appop qui autorise l'accueil a reprendre l'ecran apres le boot (il tombe parfois a la reinstal).
  adb shell cmd appops set io.freemytv SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  adb shell cmd appops get io.freemytv SYSTEM_ALERT_WINDOW | tr -d '\r'
  adb shell settings get secure screensaver_components | tr -d '\r'
}

# Pushes main and the tags. The token goes in the URL only for this one command: it never lands in .git/config.
push() {
  local url
  url="$(git remote get-url origin)"
  if [ -n "${GITHUB_PAT:-}" ] && [ "${url#https://github.com/}" != "$url" ]; then
    url="https://x-access-token:$GITHUB_PAT@github.com/${url#https://github.com/}"
  fi
  if git push -q "$url" main --follow-tags 2>/dev/null; then say "pushed to origin"; else say "push failed (no credentials)"; fi
}

case "${1:-}" in
  status)
    connect
    say "version   : $(adb shell dumpsys package io.freemytv | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r')"
    say "mode      : $(adb shell dumpsys window | grep -m1 mCurrentFocus | sed 's/.*u0 //;s/}$//' | tr -d '\r')"
    say "veille    : $(adb shell settings get secure screensaver_components | tr -d '\r')"
    say "coupés    : $(adb shell pm list packages -d | wc -l | tr -d ' ')"
    ;;

  install)
    [ -n "${2:-}" ] && TV="$2"
    ./build.sh | tail -1
    install_apk FreeMyTV.apk
    say "installed $(git describe --tags --always --dirty) on $TV"
    ;;

  save)
    [ -n "${2:-}" ] || die "give a message: ./deploy.sh save \"what changed\""
    git add -A
    git commit -q -m "$2" || die "nothing to commit"
    TAG="v$(date +%Y.%m.%d-%H%M)"
    git tag -a "$TAG" -m "$2"
    say "tagged $TAG"
    ./build.sh | tail -1
    install_apk FreeMyTV.apk
    say "installed $TAG. Undo with: ./deploy.sh rollback <previous tag>"
    push
    ;;

  rollback)
    [ -n "${2:-}" ] || die "give a tag: ./deploy.sh tags"
    TAG="$2"
    git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no such tag: $TAG"
    WT="${TMPDIR:-/tmp}/freemytv-$TAG"
    rm -rf "$WT"
    git worktree add -q --detach "$WT" "$TAG"
    cp config.properties freemytv.keystore "$WT"/      # settings and signing key live only here
    ( cd "$WT" && ./build.sh | tail -1 )
    install_apk "$WT/FreeMyTV.apk" "-d"                # -d: an older version code may replace the newer one
    git worktree remove --force "$WT"
    say "rolled back to $TAG on $TV"
    ;;

  tags)
    git tag -n1 --sort=-creatordate | head -20
    ;;

  *)
    grep -v '^#!' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
    ;;
esac
