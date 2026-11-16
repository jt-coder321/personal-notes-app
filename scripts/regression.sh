#!/usr/bin/env bash
# Regression run on an emulator:
#   1. Upgrade test: install the older build, add a note to its database,
#      install the new optimized build over it, and confirm the note survived.
#   2. Smoke test of the optimized build with screenshots (light, dark, unfolded).
#   3. The instrumented test suite (data logic and real screen flows).
set -uo pipefail

OUT=regression-output
mkdir -p "$OUT"
PKG=com.jt.notes
ACT=$PKG/.MainActivity
NEW_APK=app/build/outputs/apk/release/app-release.apk
RESULT=0

say() { echo; echo "===== $* ====="; }
record() { echo "$1" | tee -a "$OUT/summary.txt"; }
# The slow test emulator sometimes shows its own "System UI isn't responding" box
# over the app. That box belongs to the emulator, so it is closed before looking.
dismiss() {
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
  sleep 1
}
shot() { dismiss; adb exec-out screencap -p > "$OUT/$1.png"; }
DUMP=0
screen_has() {
  DUMP=$((DUMP + 1))
  for attempt in 1 2 3; do
    dismiss
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml 2>&1 | tail -1
    adb shell cat /sdcard/ui.xml > "$OUT/ui-$DUMP.xml" 2> /dev/null
    if grep -q "isn't responding" "$OUT/ui-$DUMP.xml"; then
      adb shell input keyevent KEYCODE_ESCAPE
      sleep 3
      continue
    fi
    grep -q "$1" "$OUT/ui-$DUMP.xml" && return 0
    sleep 2
  done
  return 1
}
check_text() { # name, text
  if screen_has "$2"; then record "PASS  $1"; else record "FAIL  $1 (did not find: $2)"; RESULT=1; fi
}
check_alive() { # name
  if adb shell pidof "$PKG" > /dev/null 2>&1 && ! adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
    record "PASS  $1"
  else
    record "FAIL  $1 (the app crashed)"; RESULT=1
    adb logcat -d -s AndroidRuntime:E | tail -40 | tee -a "$OUT/crash.txt"
  fi
}

# Leaves whatever is open (keyboard, then editor) and shows the note list.
go_home() {
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  adb shell am start -W -n "$ACT" > /dev/null
  sleep 3
}

# Give the freshly booted emulator time to settle.
sleep 45
dismiss
adb logcat -c
adb shell settings put secure show_ime_with_hard_keyboard 0 > /dev/null 2>&1 || true

say "1. Upgrade from the previous build"
if [ -f old/personal-notes.apk ]; then
  adb install old/personal-notes.apk
  adb shell am start -W -n "$ACT" > /dev/null
  sleep 8
  adb shell am force-stop "$PKG"
  echo "INSERT INTO notes (uuid, title, body, isChecklist, color, pinned, archived, createdAt, updatedAt) VALUES ('upgrade-1', 'UpgradeSurvivor', 'written before the update', 0, 0, 0, 0, 1700000000000, 1700000000000);" \
    | adb shell run-as "$PKG" sqlite3 databases/notes.db
  SEEDED=$(echo "SELECT count(*) FROM notes WHERE uuid='upgrade-1';" | adb shell run-as "$PKG" sqlite3 databases/notes.db | tr -d '\r')
  echo "rows seeded in the old database: $SEEDED"
  adb install -r "$NEW_APK"
  adb shell am start -W -n "$ACT" > /dev/null
  sleep 8
  shot 01-after-upgrade
  check_alive "upgrade: new build opens on top of the old data"
  if [ "$SEEDED" = "1" ]; then
    check_text "upgrade: note written before the update is still there" "UpgradeSurvivor"
  else
    record "SKIP  upgrade: could not add a note to the old database, so only the launch was checked"
  fi
else
  record "SKIP  upgrade: the older build was not available to download"
  adb install -r "$NEW_APK"
  adb shell am start -W -n "$ACT" > /dev/null
  sleep 8
fi

say "2. Smoke test of the optimized build"
adb shell "am start -W -n $ACT -a android.intent.action.SEND -t text/plain --es android.intent.extra.SUBJECT 'SharedTitle' --es android.intent.extra.TEXT 'Shared body https://example.com'" > /dev/null
sleep 5
shot 02-editor-shared-note
check_alive "share: text shared from another app opens as a note"
check_text "share: the shared title is in the editor" "SharedTitle"
go_home
shot 03-home
check_text "home: the shared note is listed" "SharedTitle"

adb shell am start -W -n "$ACT" -a com.jt.notes.NEW_CHECKLIST > /dev/null
sleep 4
adb shell input text "Milk"
sleep 2
shot 04-checklist
check_alive "widget action: new checklist opens"
check_text "checklist: typed item shows" "Milk"
go_home

adb shell cmd uimode night yes
sleep 4
shot 05-home-dark
check_alive "dark mode: home"
adb shell cmd uimode night no
sleep 3

adb shell wm size 2000x2200
sleep 5
shot 06-unfolded-list
check_alive "unfolded size: list"
adb shell "am start -W -n $ACT -a android.intent.action.SEND -t text/plain --es android.intent.extra.SUBJECT 'WideTitle' --es android.intent.extra.TEXT 'Opened beside the list'" > /dev/null
sleep 5
shot 07-unfolded-two-panes
check_alive "unfolded size: note beside the list"
check_text "unfolded size: list and note are both on screen" "SharedTitle"
check_text "unfolded size: the open note is on screen" "Opened beside the list"
adb shell wm size reset
sleep 4
shot 08-folded-again
check_alive "folding back keeps the app running"

adb logcat -d > "$OUT/logcat-smoke.txt"
adb uninstall "$PKG" > /dev/null 2>&1 || true

say "3. Instrumented tests"
adb logcat -c
if ./gradlew connectedDebugAndroidTest --stacktrace > "$OUT/gradle-tests.txt" 2>&1; then
  record "PASS  instrumented tests (data logic and screen flows)"
else
  record "FAIL  instrumented tests"; RESULT=1
fi
tail -60 "$OUT/gradle-tests.txt"
grep -E "FAILED|AssertionError|ComposeTimeoutException|Test .* failed" "$OUT/gradle-tests.txt" | cut -c1-300 | head -20
adb logcat -d > "$OUT/logcat-tests.txt"
cp -r app/build/reports/androidTests "$OUT/test-report" 2> /dev/null || true
cp -r app/build/outputs/androidTest-results "$OUT/test-results" 2> /dev/null || true

say "Summary"
cat "$OUT/summary.txt"
# The workflow reads summary.txt to decide pass or fail.
exit 0
