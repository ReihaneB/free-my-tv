#!/usr/bin/env bash
# Captures the home's surfaces after a build: top, bar focus, lens on a tab and on the gear, a focused card row,
# "Tout voir", the Films library, the search field. Usage: tools/tour.sh OUT_DIR [first step]
set -u
S="${TV_SERIAL:-192.168.1.13:5555}"
A="adb -s $S"
O="${1:?out dir}"
mkdir -p "$O"
key() { $A shell input keyevent "$@"; sleep 0.9; }
rights() { local n=$1 ks=""; for _ in $(seq "$n"); do ks="$ks KEYCODE_DPAD_RIGHT"; done; key $ks; }
shot() { "$(dirname "$0")/shot.sh" "$O/$1.png" >/dev/null 2>&1; echo "$1"; }
$A shell am force-stop io.freemytv
$A shell input keyevent KEYCODE_HOME
sleep 11
shot 1_home
key KEYCODE_DPAD_UP; shot 2_bar_search
rights 2; shot 3_bar_films                      # search 0, Accueil 1, Films 2
rights 6; shot 4_bar_gear                       # Séries, Direct, Apps, apps, sync, gear
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; rights 1; shot 5_row_cards
key KEYCODE_DPAD_DOWN; rights 30; shot 6_see_all
key KEYCODE_HOME; key KEYCODE_DPAD_UP; rights 2; key KEYCODE_DPAD_CENTER; sleep 1.5; shot 7_films
key KEYCODE_DPAD_DOWN; shot 8_films_row2
key KEYCODE_HOME; key KEYCODE_DPAD_UP; key KEYCODE_DPAD_CENTER; sleep 1.5
$A shell input text 'bad'; sleep 2; shot 9_search
key KEYCODE_BACK; key KEYCODE_BACK
