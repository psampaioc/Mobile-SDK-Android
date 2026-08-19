# Matrice Transport App Plan

## Objective

Build a minimal Android transport and mission application for the DJI Matrice 210 RTK V2 using DJI Mobile SDK V4. The Galaxy Tab S9 is the preferred Android device. Ubuntu/ROS 2 Jazzy will receive video and telemetry later.

## Safety boundaries

- No automatic arming, takeoff, landing, or mission execution.
- Mission upload, start, pause, and cancel must remain explicit manual actions.
- First mission testing is simulator, stationary, props-off, or otherwise harmless.
- Do not connect to or fly the aircraft until the bench-test gate is explicitly approved.
- DJI API keys and credentials stay local and are never committed or pasted into chat.
- Network forwarding is disabled until local video and telemetry are verified.
- The CrystalSky remains a backup DJI Pilot display and is not a development dependency.

## Current environment

- Device: Samsung Galaxy Tab S9 Wi-Fi, model SM-X710.
- Android: 16, API 36.
- ABI: arm64-v8a with armeabi-v7a/armeabi compatibility.
- Display: 1600x2560, density 340.
- Storage: approximately 193 GB available.
- USB state during DJI bench test: Android Open Accessory (`DJI`, model `T600`) + wireless ADB.
- Host: Ubuntu Linux, Java 11 for the legacy DJI build stack; Java 21 remains installed.
- Local Android SDK: `.android-sdk/`.
- DJI SDK baseline: MSDK V4.18.
- Gradle: wrapper 6.7.1; Android Gradle Plugin 4.2.2.

## Completed

1. Recorded project-local instructions in `AGENTS.md`.
2. Installed host-side `adb`.
3. Authorized the Tab S9 for this computer.
4. Collected read-only device diagnostics.
5. Installed local Google Android command-line tools and Android SDK platforms 33/34.
6. Installed JDK 11 for the MSDK V4.18 toolchain.
7. Cloned and published a public fork of DJI's official Android MSDK V4 sample.
8. Built `app-debug.apk` successfully from the official V4.18 sample.
9. Installed/launched the sample on Android 16 and fixed its Tab S9 runtime compatibility issues.
10. Proved SDK registration, Cendence/M210 connection, and live FPV decoding.

The former experimental `com.matrice.transport` APK was removed from the tablet. It is not part of this repository or the active solution.

## Current milestone

The active branch is the official DJI MSDK V4.18 sample baseline, adapted only for the Tab S9 compatibility issues observed during a props-off bench test. The app now registers, opens the Cendence USB accessory, and identifies the M210 RTK V2 as `PM420PRO_RTK`. Its firmware was reported as `01.00.0710`.

The FPV video feed is visibly decoded and displayed by the Tab S9. In the official Video Feeder sample, the active secondary source is `FPV_CAM`; the primary source remains `UNKNOWN`, which is expected until a payload camera/source is selected and detected.

Compatibility fixes required for Android 16/API 36:

- target SDK 33 instead of 34, because the legacy DJI V4.18 USB-permission flow uses a mutable implicit `PendingIntent` that Android blocks for target SDK 34+;
- remove the obsolete Gradle `MaxPermSize` JVM argument so the legacy build stack runs on JDK 11;
- guard the official sample's delayed `FlyZoneManager` access, which otherwise crashes while the aircraft components are still initializing.

The official sample reads `DJI_API_KEY` from the environment or the ignored `Sample Code/local.properties` file and injects it as a manifest placeholder. The key is not committed.

APK:

`app/build/outputs/apk/debug/app-debug.apk`

Build command:

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export ANDROID_SDK_ROOT="$PWD/.android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
./gradlew :app:assembleDebug
```

## Implementation sequence

### Phase 1: Android/device baseline

- Confirm ADB authorization, Android version, ABI, USB mode, storage, and logs.
- Install and launch a no-aircraft debug build.
- Record Android 16/MSDK V4.18 compatibility issues.

Current result: the official sample APK builds, installs, launches, registers, connects to the Cendence/M210 RTK V2, and displays the FPV video feed on Android 16. The old `com.matrice.transport` prototype was removed from the tablet to avoid confusion; it is not the active baseline.

### Phase 2: Local SDK connection

- Add a local-only API-key injection mechanism.
- Keep the key out of Git, APK source, and chat.
- Register with DJI SDK.
- Report registration, product connect/disconnect, component changes, and product model.
- Test only with the aircraft disconnected first, then with a powered bench setup when approved.

### Phase 3: Modular local data path

- `DjiConnectionManager`: SDK registration and product lifecycle.
- `VideoManager`: camera source selection and H.264/video callback handling.
- `TelemetryManager`: aircraft GPS, altitude, heading, velocity, flight state, and timestamps.
- `GimbalManager`: pitch, roll, yaw, source and timestamps.
- `MissionManager`: WGS84 waypoint validation and explicit upload/start/pause/cancel controls only.
- `NetworkTransport`: disabled until local validation; documented UDP/TCP/WebSocket interface later.
- `DiagnosticLogger`: structured logs and local export.

Current result: product connection and video are proven. RTK, gimbal, flight-state callbacks, and NDJSON export have **not** been implemented or verified in the active official-sample baseline.

## Product and UI blueprint

### Product decision: extend the validated sample first

Do not rename, copy, or replace the entire DJI sample now. It is the known-good hardware compatibility harness: it already registers the provisioned DJI application identity, opens the Cendence accessory, identifies `PM420PRO_RTK`, and decodes video on this exact tablet.

The next implementation is a new, small **Transport** entry inside the existing `app` module. It will have a separate package and screen hierarchy, while leaving the upstream sample demos available for diagnosis. This is intentionally not a second Android application yet: a different application ID and signing certificate require their own matching DJI developer-console registration. Once the Transport screen passes the local-data acceptance gate, it can be extracted into its own clean module/application without guessing about the device compatibility path.

### Intended landscape screen

The operator is a bench/field user whose single primary job is to see an unobstructed camera image while checking the pose and camera attitude needed to correlate video with RTK/GPS data.

```
┌ Camera source ▾ ─ Settings ▾ ─ Input mode ▾ ─ connection/record state ┐
│                                                                         │
│                                                                         │
│                  VIDEO PIXELS ONLY — no labels or overlays             │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│ PRIMARY GIMBAL  pitch  roll  yaw  │ RTK / GPS │ position │ velocity    │
│ altitude │ heading │ flight state │ source │ timestamps │ log state    │
└─────────────────────────────────────────────────────────────────────────┘
```

- The video surface has no persistent text, reticle, telemetry, or controls painted over it. Opening a system dropdown may temporarily cover part of the screen; it closes immediately after selection.
- **Camera source** lists only source/camera routes that the connected product exposes. It reports both the selected logical source and the active decoder source; it never guesses a payload camera.
- **Settings** contains recording/log export, stream destination, units, and diagnostic level. It contains no hidden flight action.
- **Input mode** is a safety state, not an aircraft command. The initial options are `Observe` (enabled), `Virtual stick` (present but disabled until a dedicated safety review), and `Mission` (planner only, no upload/start control in the first data milestone).
- The lower data rail makes primary-gimbal pitch/roll/yaw visually dominant. Every gimbal value is labelled with its gimbal/camera source, because a dual-gimbal aircraft must not silently associate the wrong attitude with a video frame.

The visual character is deliberately instrument-like rather than DJI Pilot-like: neutral dark graphite background, high-legibility off-white labels, restrained amber for degraded data, and cyan only for a confirmed RTK fixed state. The distinctive element is the uninterrupted "camera window" bounded by an explicit telemetry rail, so visual data is never confused with a HUD annotation.

### Mission planner scope

The application can eventually provide both a stored WGS84 waypoint list and a map-based editor. The map will be our own planner view (for example, MapLibre with an approved tile source), not an import or recreation of DJI Pilot's mission UI/files.

Initial sequence:

1. Import or enter a validated WGS84 waypoint list; show it as a read-only planned mission.
2. Add a map editor with explicit altitude/speed/action validation.
3. Add manual `Upload`, `Start`, `Pause`, and `Cancel` only after simulator/stationary bench tests and a separate review.

Virtual-stick control is also deferred. Merely selecting a menu item will never enable virtual stick or send aircraft control data. Cendence physical controls, RC override, and RTH remain authoritative.

## Android data architecture

There is a frontend and a backend-like data layer, but not a web backend:

- **Frontend:** one Android activity/screen, video surface, three dropdowns, and the telemetry rail.
- **Android service/data layer:** DJI callbacks are normalized into immutable data records; it owns logging and later network publication.
- **DJI SDK API:** the local Java/Kotlin API between the app and Cendence/aircraft. It is not an internet service.
- **Ubuntu receiver:** a separate process on the edge computer receives the documented stream, validates it, and is the place that publishes ROS 2 topics.

The first Transport implementation will use these modules, with read-only behavior until the mission phase:

- `DjiConnectionManager` — registration, product/component lifecycle, explicit connection state.
- `VideoManager` — raw video-feed subscription, decoder surface, source inventory/selection, stream metadata.
- `TelemetryManager` — flight-controller and RTK state callbacks; no flight-control calls.
- `GimbalManager` — gimbal state callbacks and camera/gimbal association; no gimbal-control calls.
- `DiagnosticLogger` — append-only local NDJSON and export.
- `NetworkTransport` — initially disabled; later publishes the documented local protocol.
- `MissionManager` — initially validates/stores plans only; no operator calls until its gated phase.

### Timestamp and odometry contract

Latency must be measured, not inferred from a screen image. Each record will contain an Android wall-clock receive time, an Android monotonic receive time, a sequence number, source/camera/gimbal identifiers, and a DJI/source timestamp only when that particular DJI callback supplies one. Missing source time remains `null`; Android receive time will never be labelled as aircraft capture time.

For video, the receiver will also see encoded-stream PTS/access-unit sequence information when available. That is useful for ordering and delay measurement but is not automatically a camera-exposure timestamp. Establishing camera-frame-to-gimbal synchronization for visual odometry is a separate calibration/measurement task; flight-controller and gimbal callbacks are asynchronous and must not be presented as frame-synchronous without evidence.

## Edge/ROS 2 transport decision

Do **not** run ROS 2/DDS directly on the Android tablet in the first implementation. It would require maintaining native ROS/DDS builds for Android, multicast/discovery behavior on the field network, and a much harder debugging path while the Cendence already owns the tablet USB port.

Instead, the tablet is a small publisher over the existing Wi-Fi/Ethernet network and Ubuntu owns ROS 2:

| Data | Tablet publication | Ubuntu responsibility |
| --- | --- | --- |
| Telemetry | Versioned NDJSON over a local WebSocket/TCP endpoint, one record per line/message | Receive, validate schema/sequence/timestamps, publish ROS 2 telemetry topics |
| Video | H.264 Annex-B packetized as RTP/UDP, with negotiated destination and periodic codec configuration/keyframes | Depacketize/decode or pass H.264 to the detection pipeline; publish image/compressed-video topics as appropriate |
| Diagnostics | Local export first; optional pull over ADB or explicit file export | Archive/replay for latency analysis |

The first network endpoint will bind only to the configured private-LAN destination, not a public interface. It will carry a protocol version, stream ID, sequence number, and timestamp fields. We will implement the Ubuntu receiver/ROS 2 bridge after the Android-local NDJSON data and video source selection are proven.

### Phase 4: Video and telemetry

- Add live video display using DJI video callbacks and the V4.18-compatible decoding path.
- Add telemetry callbacks with source timestamps when DJI exposes them.
- Record Android receive time separately from DJI/source time.
- Add newline-delimited JSON export with latitude, longitude, RTK data/status, GPS status, altitude, heading, velocity, flight state, gimbal angles, camera source, receive timestamp, and source timestamp.

### Phase 5: Bench validation

- Install APK through ADB.
- Verify registration with the user’s local key. **Passed:** DJI callback/log reported `API Key successfully registered`.
- Detect Cendence/M210 RTK V2. **Passed:** Cendence reports as DJI `T600`; SDK product model is `PM420PRO_RTK`.
- Verify video, RTK telemetry, gimbal angles, and diagnostic export. **Video passed:** Tab S9 decodes and displays `FPV_CAM`; telemetry/gimbal/export remain open.
- Do not issue flight commands automatically.

### Phase 6: Ubuntu forwarding

- Define and document telemetry JSON schema.
- Forward telemetry over a local authenticated/isolated channel.
- Forward H.264 or JPEG video with explicit timestamps and frame-drop behavior.
- Add a ROS 2 bridge only after Android-local data is stable.
- Do not copy the existing `video_ws` OCR or mapper implementation into this repository.

### Phase 7: Mission support

- Validate WGS84 waypoint input and safety constraints.
- Implement upload as a manual action.
- Implement manual start, pause, and cancel controls.
- Preserve RC override and RTH behavior.
- Test simulator/stationary/props-off scenarios first.
- Never assume DJI Pilot mission files can be imported directly.

## Acceptance gates

### Gate A: build

- Debug APK builds with the committed Gradle wrapper.
- No credentials are committed.

### Gate B: tablet runtime

- APK installs through ADB.
- App launches on the Tab S9 Android 16 environment.
- Logs can be collected without an aircraft.

### Gate C: DJI connection

- User supplies the key locally.
- SDK registers successfully.
- Cendence and M210 RTK V2 are identified.

### Gate D: local data

- Live camera video appears.
- RTK telemetry and gimbal angles appear in logs.
- NDJSON diagnostics export succeeds.

### Gate E: network

- Only after Gate D passes, Ubuntu forwarding is enabled and tested.

### Gate F: missions

- Only after simulator/bench review, explicit manual mission controls are enabled.

## Tablet control model

The Ubuntu computer normally reaches the Tab S9 through wireless ADB on the shared local network. Initial pairing/installation uses USB; the tablet then uses its USB-C connection exclusively for the Cendence while Ubuntu retains ADB access over Wi-Fi.

This is not SSH, Wi-Fi remote desktop, scrcpy, Appium, or uiautomator2. We currently use:

- `adb devices -l` for connection state.
- `adb shell getprop`, `settings`, `df`, and `dumpsys` for read-only diagnostics.
- `adb install` for explicitly approved APK installation.
- `adb logcat` for application logs.
- limited `adb shell input` and `uiautomator` navigation for explicitly approved, non-flight sample screens.

No takeoff, arming, landing, mission, virtual-stick, or gimbal-control command is part of this workflow.

## Useful commands

```bash
adb devices -l
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell df -h /data /storage/emulated/0
adb logcat -c
adb logcat -s MatriceTransport:D DJI:D AndroidRuntime:E '*:S'
```

## Known limitations

- MSDK V4.18 is legacy software and its official sample targets older Android tooling.
- The Tab S9 is running Android 16/API 36, newer than the SDK sample’s target API.
- Live DJI data cannot be validated until a local API key is configured and the Cendence/aircraft bench setup is explicitly approved.
- No person detection is part of this project.
