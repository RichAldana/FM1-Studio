#!/bin/sh
# The flasher's protocol against felucca-bsp's real update loader (OtaPipeTest.kt).
#   KOTLINC=.../kotlinc tools/ota_check/run.sh BSP_DIR [PACKAGE.fwsc ...]
# BSP_DIR: felucca-bsp (github.com/hugelton/felucca-bsp), as fm1-base's bsp submodule.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
"${KOTLINC:-kotlinc}" -jvm-target 17 -d "$out/ota.jar" "$here/../../app/src/main/java/app/fm1mix/Fm1Ota.kt" "$here/OtaPipeTest.kt" 2>&1 | grep -v "^warning" || true
java -cp "$out/ota.jar:$(dirname "$(readlink -f "${KOTLINC:-$(command -v kotlinc)}")")/../lib/kotlin-stdlib.jar" OtaPipeTestKt "$@"
