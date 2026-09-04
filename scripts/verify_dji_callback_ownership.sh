#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_root="$repo_root/Sample Code/app/src/main/java/com/dji/sdk/sample"
hub_path="$source_root/djihub/DjiDataHub.java"

shared_video_calls='(addVideoDataListener|removeVideoDataListener|addPhysicalSourceListener|removePhysicalSourceListener)'
shared_video_owners="$(rg -l --glob '*.{java,kt}' "$shared_video_calls" "$source_root" || true)"
shared_video_owners="$(printf '%s\n' "$shared_video_owners" | grep -Fvx "$hub_path" || true)"
if [[ -n "$shared_video_owners" ]]; then
    printf 'Direct shared video callback ownership is forbidden outside DjiDataHub:\n%s\n' \
        "$shared_video_owners" >&2
    exit 1
fi

state_owners="$(rg -l --glob '*.{java,kt}' 'setStateCallback\(' "$source_root" || true)"
while IFS= read -r owner; do
    [[ -z "$owner" ]] && continue
    [[ "$owner" == "$hub_path" ]] && continue
    case "$owner" in
        "$source_root/demo/accessory/AccessoryAggregationView.java")
            allowed_receiver='(accessoryAggregation|speaker|(this\.)?spotlight)'
            ;;
        "$source_root/demo/accessory/AudioFileListManagerView.java")
            allowed_receiver='speaker'
            ;;
        "$source_root/demo/flightcontroller/VirtualStickView.java"|\
        "$source_root/demo/mobileremotecontroller/MobileRemoteControllerView.java")
            allowed_receiver='simulator'
            ;;
        *)
            printf 'Direct shared state callback ownership is forbidden outside DjiDataHub: %s\n' \
                "$owner" >&2
            exit 1
            ;;
    esac
    while IFS= read -r callback_line; do
        [[ -z "$callback_line" ]] && continue
        if ! [[ "$callback_line" =~ $allowed_receiver\.setStateCallback ]]; then
            printf 'Unexpected direct state callback receiver in %s: %s\n' \
                "$owner" "$callback_line" >&2
            exit 1
        fi
    done < <(rg 'setStateCallback\(' "$owner")
done <<< "$state_owners"

hardware_state_owners="$(rg -l --glob '*.{java,kt}' 'setHardwareStateCallback\(' "$source_root" || true)"
hardware_state_owners="$(printf '%s\n' "$hardware_state_owners" | grep -Fvx "$hub_path" || true)"
if [[ -n "$hardware_state_owners" ]]; then
    printf 'Direct remote-controller callback ownership is forbidden outside DjiDataHub:\n%s\n' \
        "$hardware_state_owners" >&2
    exit 1
fi

shared_status_calls='(setChargeRemainingCallback|setDownlinkSignalQualityCallback)'
shared_status_owners="$(rg -l --glob '*.{java,kt}' "$shared_status_calls" "$source_root" || true)"
shared_status_owners="$(printf '%s\n' "$shared_status_owners" | grep -Fvx "$hub_path" || true)"
if [[ -n "$shared_status_owners" ]]; then
    printf 'Direct shared passive-status callback ownership is forbidden outside DjiDataHub:\n%s\n' \
        "$shared_status_owners" >&2
    exit 1
fi

printf 'DJI callback ownership verification passed.\n'
