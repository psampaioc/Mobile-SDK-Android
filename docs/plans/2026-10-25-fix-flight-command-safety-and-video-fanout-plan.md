---
title: Fix flight-command safety, video fan-out, and pre-push correctness gaps
date: 2026-10-25
status: proposed
---

# Fix flight-command safety, video fan-out, and pre-push correctness gaps

## Goal

Make the current Android working tree safe to commit and push without changing
the DJI/Edge wire contract or expanding product scope. The work restores the
existing authority boundary—aircraft-affecting DJI commands happen only after a
distinct, visible operator action—and removes concrete callback blocking paths
that can interrupt preview, recording, or Primary transport.

This plan is derived from the five-part source review on 2026-10-25. It
supersedes no product plan; it is a corrective implementation plan for the
current uncommitted tree.

## Problem frame

The app has a strong intended architecture: `DjiDataHub` owns recurrent DJI
setters/callbacks and fans their observations out to Operate, Mission,
Transport, RTK, and recording. The review found deviations:

1. a flight-state callback can issue a real DJI Home command automatically;
2. opening a legacy demo changes aircraft control modes without an operator
   command;
3. synchronous video fan-out means one slow consumer can delay DJI callbacks
   and the transport ingress;
4. several state/lifecycle paths can present stale or false operational state;
5. the current large staging scope needs a verification and commit boundary.

## Scope and boundaries

### In scope

- Remove non-UI-triggered Home-setting behavior and preserve explicit Mission
  Home actions.
- Ensure legacy Virtual Stick configuration is passive until an explicit
  operator action succeeds.
- Isolate Primary video consumers behind bounded, per-consumer delivery so a
  slow preview, recorder, or transport path cannot block DJI's callback.
- Correct the reviewed Home-session, OcuSync, recording-stop, map-lifecycle,
  location freshness, endpoint-input, diagnostics-retention, and stale-UI
  defects.
- Add targeted unit tests; run Android unit, build, callback-ownership, APK,
  hygiene, and device instrumentation gates before commit.

### Explicitly out of scope

- Flight, takeoff, landing, RTH, gimbal, camera, RTK/NTRIP, mission-start, or
  virtual-stick command redesign.
- Changing ports, RTP payload type, MTU, H.264 packetization, Edge protocol,
  TCP migration, or FPV transport.
- Altering DJI Pilot/Cendence/OcuSync radio configuration.
- Redesigning the entire app or deleting unrelated sample demos.

## Non-negotiable invariants

1. `DjiDataHub` remains the sole owner of DJI setter callbacks and recurrent
   listeners. No view, transport, recorder, or Mission class registers a direct
   `VideoFeeder`, Flight Controller, RTK, or AirLink setter callback.
2. Every DJI command that can affect aircraft state originates from a visible
   UI action, with its existing confirmation/error treatment retained.
3. Primary preview, local recording, and Edge transport remain independent:
   stopping or failing one must not stop the others.
4. Video queues stay bounded and use latest-data semantics; no component may
   accumulate unbounded latency or memory.
5. Source timestamp and Android receive timestamp remain untouched; no
   diagnostic change alters the Android-to-Edge wire contract.
6. A build or JVM test pass is not proof of aircraft, Cendence, Wi-Fi, map, or
   DJI SDK runtime behavior; these remain explicit hardware gates.

## Requirements traceability

| Review finding | Required outcome | Primary code areas |
| --- | --- | --- |
| Passive flight-state Home auto-seed | No flight-state callback can invoke a DJI Home setter. | `djihub/DjiDataHub.java`, `djihub/DjiHomeAutoSeedPolicy.java` |
| Legacy Virtual Stick side effect | Opening a sample page is passive; only an explicit control action can configure/enable Virtual Stick. | `demo/flightcontroller/VirtualStickView.java`, sample navigation tests/checks |
| Synchronous Primary fan-out | Slow consumer cannot block other Primary consumers or the DJI callback longer than bounded copy/enqueue work. | `djihub/DjiDataHub.java`, `djihub/CallbackMulticaster.java`, new video-delivery class, Operate/Transport/recording callers |
| Recording STOP lock | Stop does not hold a callback-contended lock while joining a worker. | `recording/LocalPrimaryRecordingManager.java` |
| Stale Home completion | A completion from an older hub generation cannot render Home as confirmed in a newer session. | `operate/MissionMvpView.java` |
| OcuSync reset | Reconcile preserves a healthy quality sample; disconnection/object replacement clears it. | `djihub/DjiDataHub.java`, status-model tests |
| Field UI defects | Map location has freshness; endpoint validation does not block UI; map resources are released; obsolete RTK UI updates are ignored. | `MissionMvpView.java`, `MapLibreMissionMapView.java`, `TransportView.java`, `OperateMvpView.java` |
| Retention silent failure | Diagnostic rotation reports/terminates safely when filesystem rotation fails. | `transport/diagnostics/AsyncNdjsonDiagnosticWriter.java` |

## Implementation units

### U1 — Restore explicit DJI Home authority

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiDataHub.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiHomeAutoSeedPolicy.java`
- `Sample Code/app/src/test/java/com/dji/sdk/sample/djihub/DjiHomeAutoSeedPolicyTest.java`
- `Sample Code/app/src/test/java/com/dji/sdk/sample/djihub/DjiHomeStateTest.java`
- `scripts/verify_dji_callback_ownership.sh` only if its policy list requires
  an explicit assertion of this rule

**Change:** Remove the `noteFlightState` path that derives `shouldSeedHome`
from passive telemetry and calls `setDjiHomeUsingAircraftCurrentLocation`.
Delete `DjiHomeAutoSeedPolicy` and its test if no explicit user action needs
that policy afterwards. Keep the existing explicit `SET DJI HOME` Mission path
and its readback/notification flow unchanged.

**Why:** The existing global-Home plan defines the Flight Controller as
authoritative but forbids automatically setting Home. Passive callback delivery
is not an operator action, regardless of whether a previous field test intended
it.

**Tests and acceptance:**

- A source/ownership test rejects any Home setter reachable from a flight-state
  callback.
- Existing explicit Mission `SET DJI HOME` success/failure tests still pass.
- Manual props-off smoke: connection and valid aircraft telemetry do not issue
  a Home update; tapping the explicit Mission control does.

### U2 — Make legacy Virtual Stick page entry passive

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/demo/flightcontroller/VirtualStickView.java`
- Existing Virtual Stick tests if present; otherwise add a narrow JVM seam/test
  around the command ordering if the class has testable collaborators.

**Change:** Move control-mode configuration out of `initParams()` into the
existing visible enable/configure action. Enable advanced mode only after
`setVirtualStickModeEnabled(true)` returns success. Preserve the existing DJI
error dialog and disable path.

**Why:** Entering a sample screen must never change aircraft-control modes.
This is a small behavioral correction, not a Virtual Stick feature redesign.

**Tests and acceptance:**

- Construction/attachment causes no Flight Controller setter calls.
- Failed basic enable causes no advanced-mode enable.
- Props-off device smoke: opening SAMPLE -> Virtual Stick changes no control
  mode; pressing its explicit enable action retains current error handling.

### U3 — Introduce bounded asynchronous Primary delivery at the hub boundary

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiDataHub.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/CallbackMulticaster.java`
- New focused hub-private delivery class under
  `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/TransportRuntime.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/recording/LocalPrimaryRecordingManager.java`
- `Sample Code/app/src/test/java/com/dji/sdk/sample/djihub/CallbackMulticasterTest.java`
- New focused hub-delivery tests under the same test package

**Design:**

1. Keep a single DJI Primary callback in `DjiDataHub`.
2. On that callback, validate the reported size, make one bounded defensive
   packet copy, enqueue references to independent subscriber queues, then
   return. The callback must not decode, packetize RTP, mux MP4, wait on a
   worker, perform disk I/O, or send UDP.
3. Each registered Primary consumer owns a small bounded worker/queue. On
   pressure, discard old queued packets in favor of the newest complete input;
   increment that consumer's own drop metric. Never allow one queue to delay
   another queue.
4. Delivery order remains FIFO inside an individual consumer until it falls
   behind; after a deliberate latest-only drop, preserve the existing H.264
   discontinuity/reset contract before the consumer resumes.
5. Listener unsubscribe and hub teardown must stop/join only that consumer
   outside the DJI callback and without holding a callback-contended global
   lock.

**Why this boundary:** Moving all listeners to one common executor would still
let a slow decoder block transport. Copying separately inside each current
listener still makes the DJI callback cost scale with consumers. One shared
hub packet plus independent bounded consumers is the smallest design that
preserves the central ownership model and isolates stalls.

**Tests and acceptance:**

- A deliberately blocking preview listener does not prevent a transport-like
  listener from receiving later packets.
- A saturated consumer records bounded drops and does not grow its queue.
- Unsubscribe while work is pending stops only the selected consumer and does
  not deliver after close.
- Existing H.264/transport primitive tests retain packetization and recovery
  behavior.
- Hardware bench with preview + Primary transport + recording: each remains
  responsive, transport counters advance, and no direct DJI callbacks appear
  outside the hub.

### U4 — Remove recording STOP contention

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/recording/LocalPrimaryRecordingManager.java`
- New or existing recording unit tests under
  `Sample Code/app/src/test/java/com/dji/sdk/sample/recording/`

**Change:** Under synchronization, atomically detach the current recording
runtime and close the hub subscription. Release the lock before joining/closing
the recording worker and files. Publish final state only if that detached
runtime is still the stopped session.

**Why:** `stop()` currently holds the same monitor used by `onVideo()` while a
two-second worker join may occur. This remains harmful even after U3 because
telemetry and lifecycle callbacks also touch the recording manager.

**Tests and acceptance:**

- A blocked/slow close does not prevent concurrent `onVideo()` from returning
  promptly.
- Repeated Stop is idempotent; a late runtime state cannot overwrite a newer
  recording state.
- STOP changes recording only; preview and Primary transport continue.

### U5 — Make Home completion session-safe and reconcile OcuSync truthfully

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/MissionMvpView.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiDataHub.java`
- `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/OperateStatusModelTest.java`
- `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/FlightReadinessSessionTest.java`
- New/extended Mission and hub state tests in their existing packages

**Change:**

1. Capture the current request/session generation before an explicit Set DJI
   Home request. Ignore a completion when the generation no longer matches;
   request/rely on current hub readback rather than rendering stale coordinates.
2. In `attachAirLinkLocked`, preserve the latest OcuSync quality and timestamp
   when the same `OcuSyncLink` is reconciled. Clear them only when the component
   becomes unavailable or the actual link object changes.

**Tests and acceptance:**

- A delayed old-session Home success cannot produce `HOME READY` after a new
  session begins.
- Explicit current-session success still updates Mission, Operate and readiness
  immediately.
- Repeated hub reconciliation with the same link preserves quality; true
  disconnect/replacement clears it; no missing-quality state is falsely
  presented as an aircraft disconnect.

### U6 — Correct field-facing UI/lifecycle edges without redesign

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/MissionMvpView.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/missionplanner/map/MapLibreMissionMapView.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/ui/TransportView.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/network/TransportConfig.java`
- `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`
- Matching tests under `missionplanner`, `transport`, and `operate`

**Change:**

1. Treat tablet last-known location as valid only within a named freshness
   window; otherwise center on fresh valid aircraft location and label the
   chosen source in the existing presentation.
2. Add an idempotent destroy method to the MapLibre wrapper and invoke it only
   when the Mission view is genuinely discarded—not ordinary hide/show
   navigation—so navigation state is not accidentally reset.
3. Validate Edge endpoint input as a literal IPv4/IPv6 address before UI-side
   persistence/connection. Reject hostnames with a local message instead of
   synchronous DNS on the main thread. Preserve the existing endpoint/port
   wire contract.
4. Guard delayed RTK connect/disconnect UI posts with the active page/view
   generation so removed Settings content cannot be updated.

**Tests and acceptance:**

- Stale tablet location is rejected and aircraft fallback is selected.
- Map destroy is idempotent and ordinary Mission visibility transitions retain
  the map state.
- Invalid hostname is rejected without constructing a sender; valid literal IP
  still persists/selects.
- Delayed RTK completion after navigation cannot modify detached UI.

### U7 — Make diagnostic rotation failure explicit

**Files:**

- `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/diagnostics/AsyncNdjsonDiagnosticWriter.java`
- `Sample Code/app/src/test/java/com/dji/sdk/sample/transport/diagnostics/AsyncNdjsonDiagnosticWriterTest.java`

**Change:** Check each `delete()` and `renameTo()` outcome during rotation. On
failure, use the writer's existing terminal-failure path, stop accepting new
records safely, and preserve the original file instead of pretending retention
succeeded.

**Tests and acceptance:**

- Failed rename/delete produces the expected terminal diagnostic state.
- Successful rotation retains the existing bounded-file behavior.
- The failure path never throws from the DJI callback or blocks transport.

### U8 — Validate, stage, commit, and push by intent

**Files/process:** all changed implementation/tests/resources; review
documentation under `docs/plans/`; no broad cleanup beyond the present tree.

**Sequence:**

1. Run the focused JVM tests after each unit and the full existing suite after
   U7: `scripts/test_android.sh`, `scripts/verify_dji_callback_ownership.sh`,
   `git diff --check`, and `scripts/check_repository_hygiene.py`.
2. Build and verify the debug APK with `scripts/build_android.sh :app:assembleDebug`
   and `scripts/verify_apk.sh`.
3. With the Tab S9 available, run the existing `connectedDebugAndroidTest`
   instrumentation suite. This is a release gate for the new preference,
   recording-folder, Mission JSON, and clock responder coverage, not a
   substitute for aircraft validation.
4. Perform props-off/manual smoke checks: no automatic Home setter at connect;
   no control-mode setter on opening Virtual Stick; explicit Home success and
   reconnect handling; preview + transport + recording concurrency; OcuSync
   status across normal reconcile; Mission open/close map lifecycle.
5. Re-run a focused independent code review over the final diff.
6. Review `git status` and `git diff --cached --stat` by intent. Stage source,
   tests, resources, and only documentation that belongs to this body of work;
   do not assume every historical plan must share the implementation commit.
7. Confirm the remote publication range because the branch already contains
   commits ahead of its remote. Commit with a single message describing the
   shipped corrective behavior, then push only after the staged diff and remote
   target are inspected.

## Verification contract

| Layer | Required evidence | Does not prove |
| --- | --- | --- |
| Source/unit | New targeted tests plus `scripts/test_android.sh` | DJI hardware behavior or RF performance |
| Build/package | Debug assemble and APK verification | Runtime UI/flight behavior |
| Callback ownership | `scripts/verify_dji_callback_ownership.sh` passes | Absence of blocking work inside a valid hub callback |
| Instrumented | `connectedDebugAndroidTest` passes on Tab S9 | Cendence/M210 behavior |
| Props-off hardware | Manual explicit-action smoke checks | Safe airborne operation or real network reliability |
| Final review | Independent final-diff review has no P0/P1 | Future firmware/API behavior |

## Definition of done

- No passive callback or screen construction can issue a Home or flight-control
  configuration command.
- Explicit Mission Home remains functional and generation-safe.
- The DJI Primary callback performs only bounded packet preparation/enqueue;
  slow preview/recording/transport paths are isolated from one another.
- Recording Stop cannot hold a video callback lock while joining a worker.
- OcuSync, map location, endpoint input, MapLibre and RTK UI state satisfy the
  specific acceptance checks above.
- Diagnostic rotation failure is explicit and bounded.
- All verification gates pass, with hardware-dependent results recorded as
  observed evidence rather than inferred from compilation.
- Staging excludes unrelated historical material, the commit is reviewed, and
  the intended remote publication range is explicit before push.

## Risks and rollback

| Risk | Mitigation / rollback |
| --- | --- |
| Asynchronous delivery changes H.264 consumer ordering | Keep per-consumer FIFO until bounded latest-only eviction; reuse the existing discontinuity/IDR recovery behavior; revert U3 alone if primitive tests regress. |
| Extra copy raises allocation pressure | Keep one bounded shared hub packet and small queues; measure callback duration/drop counters before increasing capacity. |
| Removing auto-seed leaves Home unavailable until operator action | Intended behavior. The Mission UI retains explicit Set DJI Home and readback. |
| Virtual Stick demo compatibility changes | Opening it becomes passive; explicit enable remains the compatibility point and retains DJI error reporting. |
| Map destroy accidentally loses navigation state | Restrict destroy to permanent view disposal, not visibility changes. |

## Open hardware checks

These do not block code implementation but do block any claim of flight-ready
release: M210/Cendence Home readback, OcuSync quality cadence, Virtual Stick
mode behavior, MapLibre memory behavior over repeated navigation, and Primary
transport/recording/preview pressure under a real source.
