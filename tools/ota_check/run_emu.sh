#!/bin/sh
# The firmware manager's backup and restore against the app's emulator (BackupEmuTest.kt).
#   (cd native && cargo build --release); KOTLINC=.../kotlinc tools/ota_check/run_emu.sh LOOM-8.fwsc
set -e
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../../app/src/main/java/app/fm1mix"
out=$(mktemp -d)
"${KOTLINC:-kotlinc}" -jvm-target 17 -d "$out/t.jar" "$src/Fm1Ota.kt" "$src/Fm1Backup.kt" "$src/LoomSamples.kt" "$src/Lang.kt" "$src/Native.kt" "$here/BackupEmuTest.kt" 2>&1 | grep -v "^warning" || true
java -Djava.library.path="$here/../../native/target/release" -cp "$out/t.jar:$(dirname "$(readlink -f "${KOTLINC:-$(command -v kotlinc)}")")/../lib/kotlin-stdlib.jar" BackupEmuTestKt "$@"
