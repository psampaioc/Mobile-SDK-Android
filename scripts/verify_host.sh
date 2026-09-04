#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$script_dir/check_repository_hygiene.py"

if [[ "${1:-}" == "--with-gradle" ]]; then
    "$script_dir/test_android.sh" "${2:-}"
    build_args=()
    if [[ "${2:-}" == "--offline" ]]; then
        build_args+=(--offline)
    fi
    "$script_dir/build_android.sh" "${build_args[@]}" :app:assembleDebug
    "$script_dir/verify_apk.sh"
elif (( $# > 0 )); then
    printf 'usage: scripts/verify_host.sh [--with-gradle [--offline]]\n' >&2
    exit 2
fi
