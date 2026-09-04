#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${1:-$repo_root/Sample Code/app/build/outputs/apk/debug/app-debug.apk}"

[[ -f "$apk" ]] || { printf 'error: APK not found: %s\n' "$apk" >&2; exit 1; }
command -v unzip >/dev/null || { printf 'error: unzip is required\n' >&2; exit 1; }

unzip -tq "$apk" >/dev/null
entries="$(unzip -Z1 "$apk")"

required=(
    AndroidManifest.xml
    classes.dex
    lib/arm64-v8a/libdjivideo.so
    lib/arm64-v8a/libSDKRelativeJNI.so
    lib/armeabi-v7a/libdjivideo.so
    lib/armeabi-v7a/libSDKRelativeJNI.so
)
for entry in "${required[@]}"; do
    if ! grep -Fxq "$entry" <<< "$entries"; then
        printf 'error: APK lacks required entry %s\n' "$entry" >&2
        exit 1
    fi
done

apk_bytes="$(stat -c %s "$apk")"
printf 'APK verification passed: %s bytes, ARM64/ARMv7 DJI video native libraries present.\n' "$apk_bytes"
