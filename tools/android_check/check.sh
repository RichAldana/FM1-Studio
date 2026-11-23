#!/bin/sh
# Type-checks every Kotlin file of the app against Android 35 (android.jar) and stand-ins for AndroidX (stubs/).
#   KOTLINC=/path/to/kotlinc ANDROID_JAR=/path/to/android.jar tools/android_check/check.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../app/src/main/java/app/fm1mix"
OUT=${TMPDIR:-/tmp}/fm1mix-android-check
rm -rf "$OUT"; mkdir -p "$OUT"
"${KOTLINC:-kotlinc}" -jvm-target 17 -classpath "$ANDROID_JAR" -d "$OUT" "$HERE"/stubs/*.kt "$SRC"/*.kt 2>&1 \
  | grep -v "^warning: \|^Picked up" || true
ls "$OUT"/app/fm1mix/MainActivity.class >/dev/null 2>&1 && echo "OK: the app's Kotlin compiles" || { echo "FAILED"; exit 1; }
