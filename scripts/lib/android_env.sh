#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sample_root="$repo_root/Sample Code"

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

resolve_java_11() {
    local candidate="${JAVA11_HOME:-}"

    if [[ -z "$candidate" && -x /usr/lib/jvm/java-11-openjdk-amd64/bin/java ]]; then
        candidate=/usr/lib/jvm/java-11-openjdk-amd64
    fi
    if [[ -z "$candidate" && -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        candidate="$JAVA_HOME"
    fi
    [[ -n "$candidate" && -x "$candidate/bin/java" ]] || \
        die "Java 11 not found; set JAVA11_HOME to an existing JDK 11"

    local java_version
    java_version="$($candidate/bin/java -version 2>&1 | sed -n '1p')"
    [[ "$java_version" == *'version "11.'* ]] || \
        die "JAVA11_HOME must select Java 11 (found: $java_version)"

    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
}

resolve_android_sdk() {
    local candidate="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

    if [[ -z "$candidate" && -d "$repo_root/.android-sdk" ]]; then
        candidate="$repo_root/.android-sdk"
    fi
    [[ -d "$candidate" ]] || \
        die "Android SDK not found; set ANDROID_SDK_ROOT or restore $repo_root/.android-sdk"
    [[ -d "$candidate/platforms/android-33" ]] || \
        die "Android SDK platform 33 is missing from $candidate"
    [[ -d "$candidate/build-tools/30.0.2" || -d "$candidate/build-tools/34.0.0" ]] || \
        die "no compatible Android build-tools installation found in $candidate"

    export ANDROID_SDK_ROOT="$candidate"
    export ANDROID_HOME="$candidate"
}

prepare_android_build_env() {
    [[ -x "$sample_root/gradlew" ]] || die "Gradle wrapper is missing or not executable"
    resolve_java_11
    resolve_android_sdk
    printf 'Android build environment: Java 11, SDK %s\n' "$ANDROID_SDK_ROOT"
}
