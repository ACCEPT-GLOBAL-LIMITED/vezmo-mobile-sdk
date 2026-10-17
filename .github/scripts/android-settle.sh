#!/usr/bin/env bash
# Waits until a just-booted emulator's Google Play services has restarted itself after its first configuration sync
# ("Module config changed, forcing restart", 2 to 4 minutes after every boot). Android kills every app using its
# providers when it does (the example's downloadable fonts), which mid-flow loses the payment under way. Waits up to
# 5 minutes, then 30 s more for the rest of the restarts.
adb logcat -G 16M
if timeout 300 adb logcat -e "Module config changed, forcing restart" -m 1 > /dev/null; then
  echo "Google Play services restarted itself after its first sync; letting it settle."
  sleep 30
else
  echo "::warning::Google Play services didn't restart itself within 5 minutes; going on."
fi
