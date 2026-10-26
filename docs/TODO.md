# Deferred work

## Audit — flight-command safety and Primary video fan-out (2026-10-26)

Compared against [the implementation plan](plans/2026-10-25-fix-flight-command-safety-and-video-fanout-plan.md)
at HEAD `92c42c6a`.

| Plan unit | Current result | What remains |
| --- | --- | --- |
| U1 — explicit DJI Home authority | Implemented in source; ownership check rejects Home setters from passive flight-state handling, and the explicit Mission action/readback remains. | Add an automated success/failure test at the hub/Mission boundary; the existing Home test covers the state model only. Props-off tablet smoke remains. |
| U2 — passive Virtual Stick entry | Implemented in source; basic enable gates advanced mode and retains DJI error handling. | Add focused regression coverage for construction and failed-enable ordering; props-off device smoke remains. |
| U3 — bounded Primary fan-out | Implemented for Operate, Transport, local recording, `BaseCameraView`, and Primary `VideoFeedView`; bounded per-consumer workers, overflow discontinuity, and peer isolation have JVM coverage. | **P2:** `PrimaryVideoFanout.close()` joins for at most 2 s. A slower in-flight consumer can outlive close and touch a detached owner. Also add explicit FIFO and drop-counter assertions; current tests cover overflow signaling but not order/counter visibility. |
| U4 — recording STOP contention | Source detaches runtime/subscriptions under lock and closes outside it; generation fences stale state. | **P2:** no recording runtime tests exist. Also, `EncodedVideoAccessUnitPipeline.offer()` and `drain()` share a monitor while `drain()` calls the muxer sink; an in-flight recording `onVideo()` can still wait behind disk/muxer work. Add tests for prompt callback return, slow close, repeated Stop, stale callbacks, and STOP isolation from preview/transport before claiming the full guarantee. |
| U5 — Home session/OcuSync reconciliation | Source fences explicit Home completion/readback by aircraft-session generation; same OcuSync link keeps its quality sample and replacement clears it. | Add tests at the hub/callback boundary for delayed old-session results and same-link reconcile vs. replacement; current tests mainly cover pure state models. Hardware cadence/readback remains unverified. |
| U6 — map/location/endpoint/RTK UI edges | Source includes freshness fallback, explicit map destruction on discarded Mission pages, literal IP validation, and active-page checks for RTK UI results. Freshness and endpoint policies have JVM coverage. | Add lifecycle tests for map hide/discard and delayed RTK results; verify centering and repeated Mission navigation on tablet. |
| U7 — diagnostic rotation failure | Delete/rename failure becomes terminal, and deterministic failure cases verify source evidence is retained. | **P2:** `offer()` checks `running` separately from queue insertion. A producer that already observed `true` can enqueue after the writer has failed, set `running=false`, and cleared the queue; that record then has no worker. Make terminal rejection atomic with enqueue or otherwise drain/reject safely, and add a producer-vs-rotation-failure race test. |
| U8 — verification and shipping | JVM suite, callback-ownership check, repository hygiene, debug APK build/verification, and Android-test source compilation pass. Commit `92c42c6a` is pushed. | `connectedDebugAndroidTest` was compiled but not run on a device; props-off/M210/Cendence and real Primary preview+transport+recording smoke tests remain unverified. The pushed commit contains 128 files / 11,185 insertions across accumulated work, rather than a narrowly scoped corrective commit as the plan requested; do not rewrite the published commit—keep future changes narrowly staged. The plan frontmatter still says `status: proposed`; update its status only when these remaining gates are closed. |

Local checks rerun for this audit on 2026-10-26: `scripts/test_android.sh --offline`,
`scripts/verify_dji_callback_ownership.sh`, `scripts/check_repository_hygiene.py`,
`scripts/build_android.sh :app:assembleDebug :app:compileDebugAndroidTestJavaWithJavac`,
`scripts/verify_apk.sh`, and `git diff --check` all passed. Build/test success is not
hardware evidence. No aircraft command or device test was performed.

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
