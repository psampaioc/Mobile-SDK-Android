#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/android_env.sh
source "$script_dir/lib/android_env.sh"

prepare_android_build_env

gradle_args=(--no-daemon --stacktrace)
if [[ "${1:-}" == "--offline" ]]; then
    gradle_args+=(--offline)
    shift
fi

if (( $# == 0 )); then
    set -- :app:assembleDebug
fi

printf 'Running Gradle tasks:'
printf ' %q' "$@"
printf '\n'
(cd "$sample_root" && ./gradlew "${gradle_args[@]}" "$@")
