# Matrice M210 RTK V2 operator app

Android application for a DJI Matrice 210 RTK V2 with Cendence and a Galaxy Tab S9, based on DJI Mobile SDK Android V4.18. It provides the validated DJI connection path, an operator-first video screen, local mission drafting, and an opt-in transport path to the Ubuntu edge computer.

```text
M210 RTK V2 -> OcuSync -> Cendence -> USB -> Galaxy Tab S9 -> private LAN -> Ubuntu edge
```

## What is in scope

- Primary and FPV video display, source state, no-signal state, and a movable/collapsible FPV monitor.
- Aircraft, RTK/GPS, gimbal, Cendence, OcuSync, and battery status collected once through `DjiDataHub` and safely shared with Operate, Mission, demos, and transport.
- Local WGS84 waypoint drafts, a MapLibre planner, numbered route points, saved-mission library, and explicit `Load`, `Upload`, `Start`, `Pause`, and `Cancel` actions.
- Explicit DJI Home Point selection from a waypoint. The operator must confirm the dialog; a successful DJI API response updates Mission immediately and does not require saving or uploading a draft.
- Bounded H.264/RTP and telemetry/NDJSON transport, active whenever an approved edge endpoint is marked **IN USE**; it persists across app screens and while the tablet is backgrounded.

The app never arms, takes off, lands, changes flight mode, moves the gimbal, starts Return-to-Home, or starts a mission automatically. Every DJI command remains a visible operator action. Cendence controls and DJI landing protection remain authoritative.

## Build and install

The legacy DJI V4.18 stack requires Java 11. The project defaults to the local Android SDK in `.android-sdk/`; set `ANDROID_SDK_ROOT` if it lives elsewhere.

```bash
scripts/test_android.sh
scripts/build_android.sh :app:assembleDebug
scripts/verify_apk.sh
adb -s TABLET_SERIAL install -r 'Sample Code/app/build/outputs/apk/debug/app-debug.apk'
```

On a freshly launched app, tap **Register APP** before **Open** becomes available. This only performs SDK registration/navigation; it authorizes no aircraft command. The DJI App Key remains local in `DJI_API_KEY` or ignored `Sample Code/local.properties`.

## Operator guide

1. Connect the Cendence to the Tab S9 over USB and open the app after registration.
2. Use **Operate** for video and passive status. `SOURCES` changes the visible camera route; status chevrons expand read-only group detail.
3. In **Mission**, center the map on the tablet/aircraft, choose **ADD WAYPOINT**, then tap the map. Each numbered marker is the actual WGS84 coordinate stored in route order.
4. Tap a waypoint to delete it or choose **SET DJI HOME**. Confirming this command changes the real DJI Home Point immediately; it is separate from saving a mission.
5. Save a draft locally, reopen it with **SAVED MISSIONS**, edit it if needed, then use the separate `LOAD`, `UPLOAD`, and `START` actions only after the preflight state is ready.
6. In **Transport**, enter the approved edge host and mark it **IN USE**. Transport stays active until that endpoint is removed or replaced; stop it explicitly before changing the network destination.

## Current evidence and limits

The app builds and installs on the Tab S9; DJI registration, Cendence/M210 connection, video decoding, Operate, Mission map rendering, and manual UI navigation have been exercised. Primary video reached the Ubuntu transport bench. The secondary/FPV byte callback is present but still needs a physical end-to-end RTP validation after parser changes. RTK FIX, OcuSync quality callback behavior, DJI Home confirmation, mission load/upload, mission execution, Return-to-Home, and landing behavior remain hardware tests, not claims from a successful build.

## Implementation history

- Established the V4.18/Java 11 Android 16 compatibility harness and the Tab S9/Cendence connection path.
- Reworked the sample into Operate, Mission, Transport, settings, and separate Sample/Demos navigation.
- Centralized shared DJI video, flight, RTK, gimbal, remote-controller, battery, and OcuSync callbacks in `DjiDataHub` so UI and transport do not compete for setter-based callbacks.
- Added compact Operate telemetry/status, independent video feed health, and the floating FPV behavior.
- Added bounded transport parsing, RTP packetization, telemetry health, and local diagnostics; fixed a health-datagram overflow crash.
- Added the map-first Mission draft model, local persistence/library, Home Point confirmation, mission controls, preflight gates, and numbered waypoint rendering.

## Repository map

- `Sample Code/` — active DJI V4.18 application and all production source/tests.
- `scripts/` — repeatable Java/SDK, test, build, ownership, and APK verification commands.
- `.android-sdk/` — local Android SDK used by the scripts; it is intentionally untracked.
- [`docs/PLAN.md`](docs/PLAN.md) — current design decisions, work plan, acceptance checks, and unverified hardware gates.

## Provenance

This fork starts from DJI `Mobile-SDK-Android` V4.18, pinned at commit `37afa622a1e95f179e2d5431f0afe4203ee5ca88`. DJI SDK binaries, DJI sample code, and bundled third-party libraries have distinct licensing terms; see [LICENSE.txt](LICENSE.txt). V4.18 is retained because it is the compatible legacy SDK family for the M210 RTK V2.
