#!/usr/bin/env bash
# Run native tests directly so UTP teardown cannot remove evidence before collection.
set -euo pipefail
out=app/build/runtime-checks
mkdir -p "$out"
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
failed=0
for class in AppSmokeTest RepositoryInstrumentedTest; do
  set +e
  timeout 180 adb shell am instrument -w -e class "app.captureinbox.$class" app.captureinbox.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/$class.txt"
  command_status=${PIPESTATUS[0]}
  set -e
  if [ "$command_status" -ne 0 ] || ! grep -Eq '^OK \([1-9][0-9]* tests?\)' "$out/$class.txt"; then failed=1; fi
  adb pull /sdcard/Android/data/app.captureinbox/files/screenshots app/build/runtime-screenshots >/dev/null 2>&1 || true
done
adb logcat -d > "$out/logcat.txt"
# Inspect the data library after an actual process stop and fresh activity launch.
adb shell am force-stop app.captureinbox
adb shell am start -W -n app.captureinbox/.MainActivity | tee "$out/relaunch.txt"
sleep 2
adb exec-out screencap -p > "$out/05-after-process-relaunch.png"
adb shell uiautomator dump /sdcard/capture-window.xml >/dev/null
adb pull /sdcard/capture-window.xml "$out/relaunch-window.xml" >/dev/null
exit "$failed"
