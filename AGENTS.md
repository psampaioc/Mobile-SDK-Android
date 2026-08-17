# Local project instructions

We are building a minimal Android transport/mission app for a DJI Matrice 210 RTK V2.

Hardware:
- DJI Matrice 210 RTK V2
- DJI Cendence remote controller
- DJI CrystalSky available but old/glitchy
- Samsung Galaxy Tab S9 is the preferred Android development/device target
- Ubuntu/Linux development computer
- ROS 2 Jazzy on the Ubuntu computer

Goal:
Build a small Android app using DJI Mobile SDK V4 that connects to the Cendence through USB and provides:

1. Live camera video from the drone.
2. Aircraft GPS and RTK telemetry.
3. Aircraft altitude, heading, velocity, and flight state.
4. Gimbal pitch/roll/yaw angles.
5. Waypoint mission upload and execution.
6. Network forwarding of video and telemetry from Android to Ubuntu.
7. A simple connection/status screen and logging.

The Ubuntu computer will later run ROS 2 and the detection pipeline. Do not implement person detection yet.

Important architecture:

Drone → OcuSync → Cendence → USB → Galaxy Tab S9 Android app
Galaxy Tab S9 → Wi-Fi/Ethernet/USB network → Ubuntu ROS 2 computer

Use DJI Mobile SDK V4, not Mobile SDK V5, because the M210 RTK V2 is a legacy aircraft. Start from DJI’s official repositories and samples:

- https://github.com/dji-sdk/Mobile-SDK-Android
- https://github.com/DJI-Mobile-SDK-Tutorials/Android-VideoStreamDecodingSample

Initial work must be read-only and diagnostic:
- Check whether adb is installed.
- Check whether the Galaxy Tab S9 is connected and authorized.
- Inspect Android version, ABI, USB mode, and available storage.
- Do not install packages or modify the tablet until explicitly approved.
- Do not connect to or fly the drone yet.
- Do not enter or request DJI API keys, passwords, or credentials in chat.
- Do not perform takeoff, arming, mission execution, or other flight commands.
- Use simulator/bench testing and props-off testing first.

First deliverables:
1. Confirm whether the Tab S9 is visible through adb.
2. Confirm its Android version and architecture.
3. Confirm whether the official DJI MSDK V4 sample can be built for the current Android environment.
4. Identify the exact MSDK V4 version to use, preferably 4.18 unless compatibility requires another supported version.
5. Create a clean Android Studio project in this new repository.
6. Add DJI SDK registration and product-connection status.
7. Add live video display.
8. Add telemetry logging.
9. Add RTK and gimbal-angle logging.
10. Add a local test export format for telemetry, such as newline-delimited JSON.
11. Only after those work, add network forwarding to Ubuntu.

Use a modular design:
- DjiConnectionManager
- VideoManager
- TelemetryManager
- GimbalManager
- MissionManager
- NetworkTransport
- DiagnosticLogger

Telemetry should include at least:
- latitude
- longitude
- RTK latitude/longitude/altitude
- RTK fix/status
- GPS status
- altitude
- heading
- velocity
- aircraft flight state
- gimbal pitch
- gimbal roll
- gimbal yaw
- camera source
- Android receive timestamp
- DJI/source timestamp when available

Mission requirements:
- The first mission must be a harmless simulator or tiny stationary test.
- Support uploading a list of WGS84 waypoints.
- Do not automatically arm or take off.
- Keep mission upload, start, pause, and cancel as explicit manual actions.
- Preserve RC override and RTH behavior.
- Do not assume DJI Pilot mission files can be imported directly.
- Missions may later be recreated through the SDK.

Device strategy:
- Prefer the Galaxy Tab S9 for development and the final SDK app.
- Keep the CrystalSky as a backup DJI Pilot display.
- Do not depend on CrystalSky for application development unless the Tab S9 cannot run MSDK V4.
- If the Tab S9 is incompatible with MSDK V4, evaluate a dedicated older Android 10/11 device before modifying the aircraft.

Acceptance criteria for the first milestone:
- APK builds successfully.
- APK installs through adb.
- App registers with DJI using a user-provided key.
- App detects the Cendence and M210 RTK V2.
- Live camera video appears.
- RTK telemetry appears in logs.
- Gimbal angles appear in logs.
- No flight command is issued automatically.
- Diagnostics can be exported for review.

Work carefully:
- Keep this repository independent from the existing video_ws project.
- Do not copy its imperfect OCR or mapper implementation yet.
- Keep a clear README with setup, SDK version, device assumptions, and known limitations.
- Use Git commits for independently working milestones.
- Report exact commands, build errors, SDK limitations, and observed device behavior.
