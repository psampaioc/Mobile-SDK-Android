---
title: Plan-Audit Corrections for Primary Video Truth
type: fix
date: 2026-09-21
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
origin: docs/plans/2026-09-19-1029-fix-primary-video-truthful-profile-and-live-metrics-plan.md
---

# Plan-Audit Corrections for Primary Video Truth

## Goal Capsule

- **Objective:** Complete the unimplemented live-feed truth from the 2026-09-19 plan without changing the established M210 installation rule: the configured Zenmuse Primary payload is the fixed left-connector camera.
- **Means:** Keep recording-profile controls and live-feed observations separate; expose valid decoded dimensions, bounded access-unit cadence, Primary-RTP-only Android socket-accepted egress in Settings, and native Primary cadence beside the existing total egress in Transport.
- **Authority:** DJI capability/readback is authoritative only for the left Zenmuse recording profile. `DJICodecManager` is authoritative only for decoded dimensions. Completed H.264 access units are authoritative only for observed cadence. Android UDP acceptance is not Edge receipt.
- **Execution profile:** Reuse the existing hub, Primary `VideoStreamSender`, `TransportRuntime`, `TransportEgressMetrics`, and decoder. No new DJI callback owner, source-routing command, RTP framing change, reencode, frame dropping, Edge change, or automatic camera command.
- **Stop conditions:** If the transport runtime is inactive, show `UNOBSERVED`, never a guessed FPS or bitrate. If decoder dimensions are null or non-positive, show `UNOBSERVED`; do not substitute the recording tuple.

---

## Product Contract

### Audit findings addressed

- R1/R2 of the source plan required a dynamic `LEFT_CAM`/`RIGHT_CAM` resolver. That requirement is superseded for this installed aircraft by the later settled decision: Settings configures the fixed left Zenmuse. The UI must label that target and must not claim its recording setting is the current live-feed format.
- R3/R6/R7/R8/R10 were incomplete: Settings currently has recording controls only. It must distinguish the fixed recording payload from observed live dimensions, access-unit cadence, and Primary-RTP egress.
- R9 was partial: Transport correctly shows total Android-local accepted egress and category totals, but it does not show native Primary access-unit cadence.
- U5 documentation and connected-payload/Edge evidence are absent. Code work can complete locally, but hardware proof remains a separate validation boundary.

### Requirements

- R1. Rename the recording section to `PAYLOAD RECORDING PROFILE` and identify `LEFT CONNECTOR · CAMERA 0 · <DJI display name when available>` before its controls. If the hub reports a non-left Primary physical source, show it as a route diagnostic without changing the fixed configuration target or issuing any route command.
- R2. Add a read-only `PRIMARY LIVE FEED` section below the recording controls. It displays only valid decoded width/height, observed access-unit FPS, and Primary-RTP-only egress. It explicitly labels egress as `Android UDP socket accepted`.
- R3. Measured FPS comes from a bounded, monotonic window of completed Primary access units. Fewer than two samples, stale samples, counter discontinuities, and an inactive runtime produce `UNOBSERVED` rather than `0`, `UNKNOWN`, or a camera recording FPS.
- R4. Transport keeps its existing total egress breakdown and adds the native Primary access-unit cadence with the same `UNOBSERVED` semantics. It must not offer an artificial transport-FPS selector.
- R5. Profile Save/Apply stays explicit. Loading preferences, opening Settings, reading decoder observations, and reading transport metrics never call a DJI setter.
- R6. Preserve the existing single `DjiDataHub` callback owner, Primary RTP port 5600/payload type 96, packetization, local recording isolation, and all mission/manual-flight behavior.

### Acceptance examples

- AE1. With a left Zenmuse connected and a decoded 1280 x 720 feed, a successful recording Apply can read `3840 × 2160 · 25 fps (APPLIED)` while `PRIMARY LIVE FEED` remains `1280 × 720 · 29.97 fps observed`; this is not presented as an error.
- AE2. With the transport inactive, valid decoder dimensions may display, but FPS and RTP egress read `UNOBSERVED (transport inactive)`.
- AE3. With completed access units 33.37 ms apart, the measured cadence is approximately 29.97 FPS. A callback-chunk rate is never displayed as FPS.
- AE4. With a legacy or mismatched saved profile, it remains visible and requires an explicit supported selection before Apply; no DJI call occurs on entry.
- AE5. Transport displays `TOTAL EGRESS → EDGE` separately from Primary RTP-only egress and labels both as local socket acceptance, not Edge receipt.

### Scope boundaries

- No dynamic right-payload profile resolver is added. The 2026-09-19 dynamic mapping requirement is intentionally superseded by the fixed-left installed-payload decision.
- Do not remove or alter the existing Sources routing controls in this correction; only expose the observed route as diagnostic context.
- Do not change Edge code, GStreamer, ROS, local recording behavior, camera source assignment, H.264 packetization, or the Android-to-Edge wire contract.
- Do not claim a live resolution/FPS or Edge decoding result until verified on the connected M210/Cendence/Zenmuse path.

---

## Planning Contract

### Key technical decisions

- KTD1. **Fixed configuration target, observed route.** Settings uses `PrimaryCameraBinding` for the left component-index camera. `DjiDataHub.getSource(PRIMARY)` is read-only context, not a reason to select another camera by list order.
- KTD2. **One bounded live-observation model.** A pure `PrimaryLiveFeedMetrics` owns completion timestamps and stale/discontinuity rules. `VideoStreamSender` records only completed Primary access units into that model on its worker thread; it does not change callback or packet paths.
- KTD3. **Process-owned egress snapshots.** `TransportRuntime` exposes the existing `TransportEgressMetrics` snapshot and the new live-feed snapshot to any view. A view neither owns counters nor resets sampling when opened.
- KTD4. **Decoder facts stay local to Operate.** `OperateMvpView` reads `DJICodecManager.getVideoWidth()` and `getVideoHeight()` only when both are positive. Recording readback never supplies a fallback live size.

### Sequencing

| Order | Unit | Dependency reason |
| --- | --- | --- |
| 1 | U1 | Define and test bounded FPS/staleness semantics before wiring a UI. |
| 2 | U2 | Connect metrics to the existing worker/runtime and keep egress semantics process-owned. |
| 3 | U3 | Render the distinct recording/live/egress truths from tested snapshots. |
| 4 | U4 | Add regression coverage, documentation, and perform build/static checks. |
| 5 | U5 | Execute the connected-payload and Edge validation checklist without treating it as build proof. |

---

## Implementation Units

### U1. Add bounded Primary live-feed metrics

- **Files:** Create `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/video/PrimaryLiveFeedMetrics.java`; create `Sample Code/app/src/test/java/com/dji/sdk/sample/transport/video/PrimaryLiveFeedMetricsTest.java`; extend `Sample Code/app/src/test/java/com/dji/sdk/sample/transport/video/TransportPrimitivesTest.java` or its suite entrypoint.
- **Approach:** Store a fixed-size window of monotonic completed-AU timestamps. `snapshot(now)` reports `UNOBSERVED` until two monotonic samples exist, calculates FPS from completed-AU intervals, and expires/reset samples after a bounded stale interval or explicit discontinuity. The model contains no Android/DJI dependency.
- **Acceptance checks:** 33.37 ms reports about 29.97 FPS; 40 ms reports 25 FPS; startup/stale/discontinuity are unobserved; no callback rate is an input.

### U2. Wire existing worker and runtime snapshots

- **Files:** Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/network/VideoStreamSender.java`; modify `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/TransportRuntime.java`; modify `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/ui/TransportView.java`; extend focused transport tests.
- **Approach:** Record an AU only when the existing RTP worker reaches its complete-AU marker. Reset the metric on existing worker/send discontinuities. Expose a read-only runtime snapshot alongside the existing egress snapshot. Transport shows the observed native Primary cadence and keeps `TOTAL EGRESS → EDGE` as the sum of accepted RTP, metadata, telemetry/health, and clock pongs.
- **Acceptance checks:** The Primary worker, RTP packet bytes, metadata ordering, ports, payload type, and bounded queue behavior are unchanged. Counter resets cannot fabricate an FPS or bitrate.

### U3. Render recording and live truths in Settings

- **Files:** Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`; extend `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/PrimaryVideoProfileSelectionTest.java` for target/legacy semantics.
- **Approach:** Replace the current ambiguous `PRIMARY PAYLOAD VIDEO` label with the recording-profile label and fixed-left target identity, including camera display name when DJI supplies one. Add a read-only live-feed block refreshed from decoder and runtime snapshots. Show current observed hub route separately if it differs. Retain resolution/FPS dependent selectors, local Save, and the one explicit Apply/readback route.
- **Acceptance checks:** An Apply/readback never overwrites live observations; no live value falls back to the recording tuple; transport-inactive values are explicit; legacy/mismatched preferences stay non-automatic; no Settings entry action calls a camera setter.

### U4. Regression proof and operator documentation

- **Files:** Modify `README.md`, `docs/PLAN.md`, and `docs/TODO.md`; extend `Sample Code/app/src/test/java/com/dji/sdk/sample/transport/TransportEgressMetricsTest.java`; update the existing unit-suite registrations if necessary.
- **Approach:** Document recording-vs-live-vs-Edge truth and the connected-payload checklist. Add tests for the existing total egress breakdown and new Primary-only egress presentation model where it is exposed. Record the legacy LookAt list-position assumption as a deferred demo-only task.
- **Acceptance checks:** Android unit suite, `scripts/test_android.sh --offline`, `scripts/build_android.sh :app:assembleDebug`, `scripts/verify_apk.sh`, `scripts/verify_dji_callback_ownership.sh`, and `git diff --check` pass. No unrelated worktree files are removed or rewritten.

### U5. Hardware and Edge validation

- **Files:** No code changes required unless a reproduced result contradicts U1-U4.
- **Approach:** On the connected M210/Cendence/Zenmuse, capture fixed-left component presence/display name, hub Primary route, capabilities, readback, decoded live dimensions, observed FPS, Primary RTP-only egress, total egress, and Edge decoded dimensions in one timed session. Apply one supported recording tuple only through the visible button.
- **Acceptance checks:** Apply readback is from the fixed left camera; changing recording profile does not falsely promise a live-feed change; Edge remains on UDP 5600/PT 96; Android and Edge values are recorded as distinct boundaries; a ten-minute run checks local callback drops, parser resets, and socket errors.

---

## Verification Contract

| Gate | Evidence |
| --- | --- |
| Pure live-metrics tests | Completion-based FPS, startup, stale, and discontinuity semantics. |
| Profile tests | Fixed-left scope, visible legacy intent, and no auto-apply. |
| Existing transport tests | Parser, IDR recovery, packetization, and accepted-egress totals remain correct. |
| Callback ownership gate | `DjiDataHub` remains the only recurring DJI callback owner. |
| Android build/APK gates | Compilation and packaging remain valid. |
| Tablet visual smoke | Recording, live feed, Primary egress, and total egress are visually distinct; Settings entry issues no command. |
| Connected-payload/Edge check | Physical mapping, readback, decoded facts, and unchanged Edge Primary decode are recorded. |

---

## Definition of Done

- Settings clearly distinguishes the fixed-left Zenmuse recording profile from the observed Primary live feed.
- Live dimensions are decoder-derived, FPS is access-unit-derived, and Primary egress is Android socket acceptance only.
- Transport shows native Primary cadence plus its existing total accepted-egress breakdown without inventing an FPS control.
- Explicit Save/Apply behavior, DjiDataHub ownership, ports, packetization, local recording, and mission/manual-flight behavior are unchanged.
- Local tests/build/static checks pass, with connected-payload and Edge validation explicitly remaining unproven until run on hardware.
