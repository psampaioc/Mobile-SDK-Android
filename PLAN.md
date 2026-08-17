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
- USB state: MTP + ADB.
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
7. Created the clean Android project and Gradle wrapper.
8. Added the DJI MSDK V4.18 dependency.
9. Added a first `DjiConnectionManager` registration/product-status slice.
10. Built `app-debug.apk` successfully.
11. Installed/launched the APK on Android 16 and fixed the official DJI V4.18 loader/runtime dependencies.
12. Added read-only flight, RTK, and gimbal callback plumbing with app-private `telemetry.ndjson` export.

## Current milestone

The active branch is the official DJI MSDK V4.18 sample baseline. It is built from DJI’s `v4.18` tag, installed as `com.dji.sdk.sample`, and launched on the Tab S9 without an aircraft connected. This validates the vendor sample’s Android 16/API 36 install and startup path. With the user-provided local key, the sample registered successfully; the UI remains `Status: No Product Connected` because the aircraft and Cendence are not connected.

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

Current result: the official sample APK builds, installs, launches, and registers successfully on Android 16. No fatal runtime crash was observed. The app remains alive with no aircraft connected. The custom prototype is preserved in the earlier `main` commit and is not the active baseline.

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

Current result: the flight, RTK, and gimbal subscriptions compile and are attached only after an aircraft product callback. The export file is app-private and is not created during no-aircraft testing.

### Phase 4: Video and telemetry

- Add live video display using DJI video callbacks and the V4.18-compatible decoding path.
- Add telemetry callbacks with source timestamps when DJI exposes them.
- Record Android receive time separately from DJI/source time.
- Add newline-delimited JSON export with latitude, longitude, RTK data/status, GPS status, altitude, heading, velocity, flight state, gimbal angles, camera source, receive timestamp, and source timestamp.

### Phase 5: Bench validation

- Install APK through ADB.
- Verify registration with the user’s local key. **Passed:** DJI callback/log reported `API Key successfully registered`.
- Detect Cendence/M210 RTK V2.
- Verify video, RTK telemetry, gimbal angles, and diagnostic export.
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

The Ubuntu computer is connected to the Tab S9 through a physical USB cable. `adb` starts a local ADB server on Ubuntu and communicates with the Android Debug Bridge daemon (`adbd`) on the tablet over the authorized USB connection.

This is not SSH, Wi-Fi remote desktop, scrcpy, Appium, or uiautomator2. We currently use only:

- `adb devices -l` for connection state.
- `adb shell getprop`, `settings`, `df`, and `dumpsys` for read-only diagnostics.
- `adb install` for explicitly approved APK installation.
- `adb logcat` for application logs.

We are not sending taps, swipes, typed input, key events, screenshots, or arbitrary control commands. ADB authorization allows those capabilities in principle, but they are outside this project’s current workflow.

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
