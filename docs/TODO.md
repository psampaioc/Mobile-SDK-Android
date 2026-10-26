# Deferred work

## Primary live downlink control and recording destination

These are deliberately deferred until the current Primary transport and camera
profile behavior are validated on the M210 RTK V2/Cendence. Do not change the
working RTP path or remove local recording in the same change as an unverified
downlink write.

### Downlink investigation

The official MSDK V4 API documents M200/M210 V2 support for downlink bandwidth
controls, including allocation between the main/left/right camera feeds and FPV,
and OcuSync channel bandwidth. These controls are not direct live-FPS selectors:
they can change the available link data rate, robustness, and physical feed
assignment. The current app does not call them.

Next steps:

1. Add read-only capability/current-value inspection through `DjiDataHub` for
   the exact M210 RTK V2 airlink implementation.
2. Confirm whether the connected product exposes
   `setBandwidthAllocationForMainCamera` / `setBandwidthAllocationForLeftCamera`
   or the equivalent OcuSync API, and verify the legal ranges before enabling
   any write.
3. In a controlled hardware session, record old value, requested value,
   readback, Primary/FPV physical sources, decoded live dimensions, observed
   FPS, and Primary egress. Never assume that a recording profile changed the
   downlink.
4. Only then design a small operator control for downlink allocation. It must
   show the consequence (Primary versus FPV bandwidth and possible source
   change), require explicit confirmation, and preserve the current hub/callback
   ownership and RTP contract.

### Move recording from tablet to payload SD card

The current `LOCAL RECORDING` feature writes a Primary MP4 and telemetry sidecar
on the tablet. The intended future behavior is to control recording on the
Zenmuse payload and store the video on its SD card. This is not implemented in
this step.

Next steps:

1. Verify the fixed-left Zenmuse target and expose read-only SD-card/media
   availability and current camera-recording state.
2. Route explicit `RECORD`/`STOP` actions through the hub-owned camera command
   boundary to `startRecordVideo`/`stopRecordVideo`, with completion results and
   readback visible to the operator.
3. Verify on the aircraft that a file is created on the payload SD card and can
   be listed/downloaded through the DJI media APIs; do not infer success from a
   local UI state.
4. After remote recording is proven, remove or disable the tablet MP4 recorder
   and its local recording controls, while preserving Primary transport,
   telemetry, and background service behavior.

## Legacy LookAt camera-target hardening

`Sample Code/app/src/main/java/com/dji/sdk/sample/demo/lookat/LookAtMissionView.java` still contains a legacy first-camera-list assumption. It is outside the Operate/Transport Primary path and must not be changed incidentally. If that demo is retained, migrate it separately to an explicit verified camera target with its own hardware check.

## FPV transport is intentionally out of scope

**Priority:** not planned for the current Primary-only Edge transport.

### Confirmed finding — 2026-09-08

The DJI secondary feed (`FPV_CAM`) is delivered to Android as Annex-B **HEVC/H.265**, while the current Android-to-Edge video pipeline is H.264-only:

- Android received `164,364` secondary callbacks (`293 MB`) in session
  `cb23e83e-32b4-4791-af50-710f0cffd597`.
- The callback bytes contain HEVC NAL framing, including `00 00 00 01 21` (HEVC IRAP)
  and HEVC SPS/PPS. The H.264 parser therefore observed zero H.264 IDRs.
- Result: `secondary_access_units = 0` and `secondary_rtp_packets = 0`.
- Primary remains proven H.264 on RTP/5600. There were no Android video socket errors;
  UDP/5610, GStreamer, ROS, and the Edge receiver are not the current failure point.

### Future decision only if FPV transport becomes a real requirement

1. Prefer configuring the FPV-producing camera/feed to H.264, if the exact Zenmuse
   capability and DJI SDK expose that setting. This keeps the established Android/Edge
   H.264 RTP contract unchanged.
2. If H.264 cannot be selected, implement HEVC end-to-end in a coordinated Android and
   Edge change: bounded HEVC Annex-B parser/access-unit assembly, RFC 7798 RTP
   packetization, and an HEVC-capable Edge receiver/decoder. Do not disguise HEVC as
   H.264 or alter ports/payload contracts without the coordinated Edge update.

### Acceptance evidence

- Secondary callback, HEVC/H.264 access-unit, and RTP counters all rise.
- Edge receives and decodes FPV independently from Primary.
- The FPV view appears while Primary remains stable.
- Queues remain bounded and socket errors remain zero in a real aircraft session.
