#!/usr/bin/env bash
# Build the watch face APK, and install it if a watch is connected over adb.
#
#   ./build.sh              build only
#   ./build.sh --install    build, then install to the connected watch
#
# Run setup_assets.py first if you want the Half-Life artwork; the face builds
# and runs fine without it.

set -e
cd "$(dirname "$0")"

# Android Studio ships a JDK, and most people have no system Java. Find one.
if [ -z "$JAVA_HOME" ]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/usr/lib/jvm/default-java" ; do
    [ -x "$candidate/bin/java" ] && export JAVA_HOME="$candidate" && break
  done
fi
if [ -z "$JAVA_HOME" ] && ! command -v java >/dev/null 2>&1; then
  echo "ERROR: No Java found. Install Android Studio, or set JAVA_HOME." >&2
  exit 1
fi

# The SDK path is machine-specific, so it is not committed. Derive it if missing.
if [ ! -f local.properties ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  [ -d "$SDK" ] || SDK="$HOME/Android/Sdk"
  if [ ! -d "$SDK" ]; then
    echo "ERROR: Android SDK not found. Set ANDROID_HOME, or install Android Studio." >&2
    exit 1
  fi
  echo "sdk.dir=$SDK" > local.properties
  echo "Wrote local.properties -> $SDK"
fi

if ls app/src/main/res/drawable/hl_*.png >/dev/null 2>&1; then
  echo "Half-Life artwork: present"
else
  echo "Half-Life artwork: none (face will use fallbacks; see setup_assets.py)"
fi

./gradlew assembleDebug
APK="app/build/outputs/apk/debug/app-debug.apk"
echo
echo "Built: $APK"

if [ "$1" = "--install" ]; then
  DEV=$(adb devices | awk '/\tdevice$/{print $1; exit}')
  if [ -z "$DEV" ]; then
    echo "No watch connected over adb. See the README for pairing." >&2
    exit 1
  fi
  echo "Installing to $DEV ..."
  adb -s "$DEV" install -r "$APK"
  echo "Done. On the watch: long-press the face -> Customize."
fi
