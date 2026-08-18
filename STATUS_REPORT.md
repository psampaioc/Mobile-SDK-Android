# DJI MSDK V4 Project Status Report

Date: 2026-08-18

## Executive summary

The repository currently contains DJI's official Mobile SDK for Android V4.18 sample application, not yet the final minimal transport/mission application.

The official sample has been:

- forked to the project's public GitHub repository;
- based locally on DJI's `v4.18` tag;
- built successfully on Ubuntu with the legacy Gradle/Android toolchain;
- installed on the Samsung Galaxy Tab S9;
- launched successfully on Android 16/API 36;
- registered successfully with DJI using the locally supplied DJI Mobile SDK App Key;
- connected to Ubuntu through wireless ADB.

The tablet currently reports no connected aircraft. We have not connected the Cendence or M210 RTK V2, and we have not sent any flight, arming, takeoff, landing, mission, or virtual-stick command.

## Current device connection

Wireless ADB is currently working:

```text
Tablet model:       SM-X710 (Galaxy Tab S9 Wi-Fi)
Android release:    16
Android API level:  36
ABIs:               arm64-v8a, armeabi-v7a, armeabi
ADB endpoint:       tablet Wi-Fi address on the private project LAN
Ubuntu endpoint:    Ubuntu host on the same private LAN
Transport:          ADB over Wi-Fi
```

The tablet was previously connected by USB for setup. The Ubuntu USB cable can now be removed from the tablet and used for the Cendence, provided the tablet remains on the same reachable Wi-Fi network. The wireless endpoint may change if DHCP assigns a different tablet address.

ADB is a development/control channel. It is not SSH, does not install a server on the tablet, and does not give the tablet flight capability by itself. The current workflow is Ubuntu `adb` client → Wi-Fi → Android `adbd` → tablet.

## What was installed on the tablet

The installed package is:

```text
Package:       com.dji.sdk.sample
Version code:  1
Target SDK:    34
Minimum SDK:   23
APK size:      approximately 79.3 MB
```

Only the debug APK was installed. The tablet did not receive:

- the Git repository;
- Android Studio;
- Gradle;
- the Ubuntu Android SDK;
- ROS 2;
- the existing `video_ws` project;
- a ROS bridge;
- the final transport application;
- a DJI Pilot mission file;
- aircraft firmware.

The APK contains DJI MSDK V4.18 application code, DJI native libraries, the vendor sample UI, and its Android dependencies. The app also creates normal private Android application data during startup and registration. No aircraft firmware update was requested.

## What the installed app is

It is DJI's broad official SDK demonstration application. It is useful as a compatibility and hardware-validation tool because it exposes many SDK examples in one app. It is not yet our clean production architecture.

The active Gradle configuration uses:

```text
com.dji:dji-sdk:4.18
com.dji:dji-sdk-provided:4.18 (compile-only)
applicationId: com.dji.sdk.sample
compile SDK: 33
target SDK: 34
min SDK: 23
ABIs: armeabi-v7a and arm64-v8a
```

The DJI App Key is injected at build time from either the `DJI_API_KEY` environment variable or the ignored `Sample Code/local.properties` file. It is not committed to Git and has not been placed in this report.

## Can this app fly the drone?

Technically, yes: the vendor sample contains SDK examples that can issue flight-related commands when a compatible aircraft is connected and a human navigates to those screens and presses the controls. The source includes:

- takeoff;
- automatic landing;
- force/confirm landing;
- virtual-stick control;
- waypoint mission load, upload, start, stop, pause, resume, and download;
- other mission examples.

The relevant source is visible in the official sample at:

- [DJI registration and product callbacks](Sample%20Code/app/src/main/java/com/dji/sdk/sample/internal/view/MainContent.java:502)
- [takeoff and landing controls](Sample%20Code/app/src/main/java/com/dji/sdk/sample/demo/mobileremotecontroller/MobileRemoteControllerView.java:193)
- [waypoint mission controls](Sample%20Code/app/src/main/java/com/dji/sdk/sample/demo/missionoperator/WaypointMissionOperatorView.java:101)
- [mission and flight-controller demo navigation](Sample%20Code/app/src/main/java/com/dji/sdk/sample/internal/view/DemoListView.java:143)

This does not mean the app has flown the drone. No aircraft was connected during our tests, and no flight command was executed. The sample is therefore suitable for compatibility testing only if we keep it under strict props-off/simulator/bench procedures. Before connecting the aircraft, we should not open or exercise takeoff, virtual-stick, or mission execution screens except as a source review.

## What has actually been tested

### Host and repository

- Installed and verified `adb` on Ubuntu.
- Installed local Android command-line tools in the ignored `.android-sdk` directory.
- Installed JDK 11 for the old DJI/Gradle toolchain.
- Confirmed the Tab S9 was authorized for ADB.
- Forked DJI's repository and configured `origin` and `upstream` remotes.
- Created the `codex/msdk-v4.18-baseline` branch from DJI tag `v4.18`.

### Build

The official sample builds with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export ANDROID_SDK_ROOT="$PWD/.android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
cd "Sample Code"
./gradlew :app:assembleDebug
```

The output is:

```text
Sample Code/app/build/outputs/apk/debug/app-debug.apk
```

Build result: successful.

### Tablet runtime

- APK installed successfully through ADB.
- App launched successfully on Android 16/API 36.
- No fatal Android runtime crash was observed.
- Current UI state with no aircraft: `Status: No Product Connected`.
- Wireless ADB was configured and verified after moving the tablet and Ubuntu onto the same network.

### DJI registration

The user-provided DJI Mobile SDK App Key was read locally from the protected credentials area and passed into the build as a temporary environment variable. It was not printed or committed.

The official sample then reported:

```text
API Key successfully registered
```

This proves that the DJI registration path, package identifier, App Key, network access, and MSDK V4.18 runtime are working together on the Tab S9. It does not yet prove product detection or aircraft compatibility.

### Android permissions encountered

The old sample requested runtime permissions for location, microphone, and phone-state/device identity. Location and microphone were granted for the sample's startup flow. `READ_PHONE_STATE` was granted because the sample source documents it as being used for the device UUID during DJI registration. The sample's UI wording was broad, but this is a device-state permission, not a command to place calls.

The app also declares legacy storage and Bluetooth/Wi-Fi permissions. We did not grant unnecessary storage permissions during this test.

## What has not been tested

These remain open:

- Cendence detection through USB;
- M210 RTK V2 product detection;
- live camera video;
- camera source selection;
- aircraft GPS telemetry;
- RTK status and RTK coordinates;
- gimbal pitch, roll, and yaw;
- aircraft flight state, altitude, heading, and velocity;
- local NDJSON telemetry export from the active official sample;
- Android-to-Ubuntu telemetry forwarding;
- Android-to-Ubuntu video forwarding;
- waypoint upload against the M210;
- simulator/props-off mission validation;
- ROS 2 integration.

The earlier custom prototype contains an initial read-only telemetry/export experiment, but it is preserved on the old `main` branch and is not the active baseline. We intentionally did not mix it into DJI's official sample before proving the vendor baseline.

## Git state

Current branch:

```text
codex/msdk-v4.18-baseline
```

Important commits:

```text
37afa622  DJI v4.18 tag baseline
950d62f8  project plan added to official baseline
69686024  local-only DJI App Key injection
d3357125  successful DJI registration recorded in PLAN.md
```

The current local branch is one commit ahead of its remote because the latest documentation commit has not been pushed yet. No credential file is tracked.

## Relevant local skills and integrations

Used for this audit:

- `graphify`: repository/codebase relationship and project-content analysis.
- `plugin-management`: discovery and assessment of connected plugin capabilities.

Already available and relevant:

- `github`: repository, branch, commit, and future pull-request workflows.
- `github:yeet`: future intentional push/draft-PR workflow.
- `robotics-open-source-reuse`: useful if we later compare or adapt validated upstream robotics implementations.
- ROS 2 skills: useful when the Ubuntu forwarding/ROS 2 bridge phase begins.

The currently connected MCP/plugin inventory contains GitHub and general plugin-management capabilities, but no DJI-specific MCP server. The listed connected MCP resources report no attached external MCP server for the GitHub plugin. No plugin was installed or changed during this audit.

## External MCP candidates reviewed

These are research candidates, not installed components:

- [Android MCP Server by minhalvp](https://github.com/minhalvp/android-mcp-server): ADB-based Android control. Potentially useful, but we already have direct ADB and should prefer a read-only/default-safe server if adopting one.
- [Android MCP Server by us-all](https://github.com/us-all/android-mcp-server): advertises many ADB, UI, logcat, and emulator tools and a read-only default. Requires a security and maintenance review before use.
- [robotmcp/ros-mcp-server](https://github.com/robotmcp/ros-mcp-server): candidate for later ROS/ROS 2 introspection and controlled robot integration.
- [wise-vision ROS 2 MCP](https://github.com/wise-vision/mcp_server_ros_2): another ROS 2 bridge candidate.
- [ranch-hand-robotics ROS 2 MCP](https://github.com/ranch-hand-robotics/rde-mcp-ros-2): ROS 2 CLI introspection candidate.

Recommendation: do not install an Android or ROS MCP server yet. Direct ADB is working and is easier to audit while we validate the aircraft connection. Later, a read-only Android/logcat MCP could reduce command boilerplate, and a ROS 2 MCP could help inspect topics after the Android data path is stable. Neither candidate should be allowed to issue flight commands.

## Known warnings and limitations

The official sample uses an old Android stack. Build output contains expected legacy warnings involving deprecated Kotlin Android Extensions, old AndroidX dependencies, old Google Play Services, R8 optional classes, native-library stripping, and old resource formats.

Runtime logs also showed non-fatal legacy warnings such as missing optional LDM license data and old native/USB decoder components. Registration still succeeded. These warnings must be rechecked when the Cendence and aircraft are connected; they are not proof that video or product connection will work.

## Next controlled milestone

The next test requires explicit hardware approval and should be bench-only:

1. Keep all propellers removed or otherwise follow a safe props-off procedure.
2. Power the Cendence and aircraft without arming or taking off.
3. Connect the tablet to the Cendence by USB.
4. Keep Ubuntu connected to the tablet through wireless ADB.
5. Verify product model and connection callbacks.
6. Inspect video availability, RTK status, telemetry, and gimbal data.
7. Do not open mission execution or flight-control commands until the read-only data path is proven.

The final application will then be extracted from the proven SDK path into the requested modules: `DjiConnectionManager`, `VideoManager`, `TelemetryManager`, `GimbalManager`, `MissionManager`, `NetworkTransport`, and `DiagnosticLogger`.
