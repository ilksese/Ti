#!/usr/bin/env bash
set -euo pipefail

serial="${1:-${ANDROID_SERIAL:-emulator-5554}}"
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
    echo "Refusing to test a physical device: $serial" >&2
    exit 2
fi

qemu="$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r\n')"
sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r\n')"
booted="$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r\n')"
if [[ "$qemu" != 1 || "$booted" != 1 || ! "$sdk" =~ ^[0-9]+$ ]] || (( sdk < 33 )); then
    echo "A booted Android 13/API 33 or newer emulator is required." >&2
    exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."
results="$(mktemp -d "${TMPDIR:-/tmp}/ti-emulator-tests.XXXXXX")"
echo "Testing $serial (API $sdk)"
echo "Results: $results"

./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
    --rerun-tasks 2>&1 | tee "$results/gradle.log"

# Run instrumentation directly: no connected-device discovery or uninstall cleanup.
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
arguments=(-w -r)
if [[ -n "${2:-}" ]]; then
    arguments+=(-e class "$2")
fi
adb -s "$serial" shell am instrument "${arguments[@]}" \
    app.ti.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$results/instrumentation.log"

# adb can return zero even when JUnit fails; require a nonempty successful suite.
if ! rg -q '^OK \([1-9][0-9]* tests?\)' "$results/instrumentation.log" ||
    rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|^INSTRUMENTATION_STATUS_CODE: -[12]$' "$results/instrumentation.log"; then
    echo "Instrumentation failed. Results: $results" >&2
    exit 1
fi

adb -s "$serial" shell pm path app.ti
echo "Emulator regression passed; APKs remain installed. Results: $results"
