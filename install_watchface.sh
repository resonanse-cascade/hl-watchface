#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 WATCH_IP ADB_PORT [APK_PATH]"
  echo "Example: $0 192.168.1.80 5555 app/build/outputs/apk/debug/app-debug.apk"
  exit 1
fi

WATCH_IP="$1"
ADB_PORT="$2"
APK_PATH="${3:-app/build/outputs/apk/debug/app-debug.apk}"
TARGET="${WATCH_IP}:${ADB_PORT}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found at: $APK_PATH"
  echo "Build first in Android Studio: Build > Build APK(s)"
  exit 1
fi

echo "Connecting to $TARGET..."
adb connect "$TARGET"

echo "Installing $APK_PATH..."
adb -s "$TARGET" install -r "$APK_PATH"

echo "Done. On the watch, long-press the current face and select Lambda HUD."
