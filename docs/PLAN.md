# Current plan

## Objective

Provide one reliable Android operator application for the M210 RTK V2: passive situational awareness and video, operator-controlled waypoint missions, and low-latency telemetry/video forwarding to an Ubuntu ROS 2 edge system.

## Fixed decisions

- DJI Mobile SDK Android V4.18, Java 11, Galaxy Tab S9, Cendence, and M210 RTK V2 remain the compatibility baseline.
- `DjiDataHub` is the sole owner of shared DJI setter-based callbacks. UI, demos, diagnostics, and transport subscribe to its fan-out.
- Operate is video-first. Primary occupies the screen; FPV is a separate draggable, edge-snapped and collapsible surface. Healthy feeds have no overlay; stale/no-signal feeds use a black background, frozen last frame when available, and a red warning.
- All camera/telemetry transport is bounded and asynchronous. Android preserves encoded H.264; it does not decode/re-encode for transport.
- Mission planning is local until an operator explicitly chooses a labelled DJI command. A planned reference, actual aircraft location, and DJI Home Point are different things.
- The app observes Cendence/aircraft mode and RTH; it does not set them. Mission readiness is P/Position mode with valid flight state, route, and DJI Home. RTK status is truthful and never fabricated.
- DJI landing protection remains enabled. A normal DJI landing proceeds normally; an actual DJI landing-confirmation requirement remains an operator confirmation.

## Current implementation

### Operate

- Compact top status groups for Cendence/aircraft, link, RTK/GPS, and batteries.
- Source picker, stream health, black failure state, telemetry rail, and gimbal value selector.
- Primary/FPV video decoder plumbing and independently tracked stream state.

### Mission

- WGS84 mission draft model, validation, persistence, saved-mission chooser, MapLibre map, ordered route, and numbered waypoint markers.
- Waypoint popup offers delete or a confirmed write of the real DJI Home Point.
- `Load`, `Upload`, `Start`, `Pause`, and `Cancel` are separate controls. Preflight blocks unsafe/missing state but sends no command itself.
- Mission configuration is scrollable and map controls remain on the map.

### Transport

- A selected Edge endpoint is persistent transport intent: once marked `IN USE`, passive transport starts automatically with a connected aircraft and remains independent of Operate/Mission/Transport view lifecycles. Removing or replacing the endpoint is the only normal UI lifecycle change.
- Selecting `IN USE` also starts a persistent Android foreground service. Video, telemetry, health, AU metadata and clock replies therefore continue when the operator accidentally visits the tablet Home screen or backgrounds the Activity. This service owns no DJI callback; it only maintains the existing `DjiDataHub`/transport fan-out. Explicit force-stop, uninstall, device shutdown, or endpoint removal still end the session normally.
- UDP 5500 is limited to Flight fallback pose, RTK fusion/use state, gimbal pitch, and a 1 Hz health record. RTP/ports remain Primary 5600 and FPV 5610; UDP 5501 carries only access-unit timing metadata and UDP 5502 answers Edge clock pings at the sender origin.
- Each Android transport session writes bounded local NDJSON under its own UUID directory. Secondary framing/source/parser evidence stays local; it is not included in network health.
- Local proof on 2026-09-03: callback ownership check, transport/parser/JSON tests, debug build, and APK verification passed. Primary transport was physically proven on a previous Ubuntu bench. Secondary/FPV callback reception is proven, but its access-unit/RTP/Edge-image result remains a required hardware test.

## Approved implementation plan — Flight readiness and safety settings

**Tracked work:** GitHub issue [#2](https://github.com/psampaioc/Mobile-SDK-Android/issues/2)

### Goal

Give the operator one truthful, compact readiness view that explains what is live,
unknown, degraded, blocked, or awaiting physical review before a mission. It must
reuse `DjiDataHub`, preserve the video-first Operate screen, and never imply that
software alone has certified a flight as safe.

### Product contract

#### Requirements

- **R1 — Keep the normal Operate header compact.** Continue showing Cendence,
  aircraft, link, RTK/GPS, Cendence battery, and B1/B2 at a glance. Existing
  chevrons and attention marks remain the entry point; no permanent status card
  may cover video.
- **R2 — Open one Flight Readiness panel from the existing status controls.**
  The panel groups information into Aircraft & link, Navigation & Home, Power,
  and Safety configuration. Each row names its source, current value, and
  freshness (`LIVE`, `STALE`, or `UNAVAILABLE`).
- **R3 — Keep automatic and physical evidence distinct.** DJI-reported values
  are evaluated automatically. The physical checklist is explicit operator
  acknowledgement, never a sensor assertion and never an override for an
  automatic blocker.
- **R4 — Use honest readiness states.** `BLOCKED` prevents this app's Mission
  Start; `ATTENTION` requires review; `REVIEW REQUIRED` means automatic checks
  are acceptable but a physical item remains unchecked; `MISSION READY` means
  no known app blocker for the prepared mission, not permission or instruction
  to arm/take off.
- **R5 — Preserve manual authority.** Readiness never blocks Cendence sticks,
  DJI RTH, DJI landing protection, or manual flight. It gates only this app's
  Mission Start path. Load and Upload remain available when their existing DJI
  prerequisites pass, while clearly showing outstanding readiness review.
- **R6 — Treat unavailable data as unavailable.** Stale or unsupported link,
  positioning, battery, sensing, or configuration values never render healthy.
  RTK FIX is mandatory only for a future mission explicitly marked RTK-required;
  ordinary GPS-capable missions remain possible with a visible advisory.
- **R7 — Separate planned and actual Home.** The panel shows actual DJI Home,
  its confirmation/readback state, and the mission-planned reference as distinct
  values. RTH-active state is always prominent.
- **R8 — Make safety configuration deliberate.** RTH altitude, RC signal-loss
  behavior, maximum altitude/distance, battery-warning settings, and sensing /
  landing-protection state are read-only until support is proven on the exact
  M210 RTK V2/Cendence. A supported write uses select value -> review old/new
  and consequence -> hold-to-confirm -> DJI completion result -> readback.
- **R9 — Keep an audit trail.** Automatic transitions, physical acknowledgements,
  setting-write attempts/results, and Mission Start denials are appended to the
  bounded local session diagnostics with Android receive time.

#### Decisions

- **Mission-only gate.** Physical acknowledgement blocks Mission Start but not
  manual Cendence flight, mission drafting, Load, or Upload. It resets on a new
  aircraft session (app launch or aircraft disconnect/reconnect). This avoids a
  software barrier to manual recovery while making autonomous execution require
  deliberate review.
- **Read before write.** Initial delivery exposes configuration only as
  capability-aware readback. A setting becomes editable only after its getter,
  setter, completion semantics, and real-aircraft readback have been validated.
- **One hub.** All recurring DJI callbacks remain owned by `DjiDataHub`; the
  readiness feature is a subscriber/reducer and never adds competing setter
  callbacks.

#### Readiness state model

```text
DJI snapshot + freshness + mission context
                  |
                  v
      automatic blockers / advisories
                  |
    + physical checklist session state
                  |
                  v
BLOCKED -> ATTENTION -> REVIEW REQUIRED -> MISSION READY

Manual sticks: never gated
Mission Start: BLOCKED until automatic prerequisites pass and review is complete
```

#### Physical checklist

The per-aircraft-session checklist comprises: airframe/propellers/payload and
batteries secure; takeoff/landing area clear; VLOS/weather/airspace reviewed;
DJI Home and RTH altitude checked against the recovery area and obstacle route;
Cendence controls understood; and, when applicable, mission route/final action
reviewed. The app records acknowledgement but cannot claim to have observed any
of these facts.

#### Deferred from this issue

- Native DJI geofence configuration, terrain clearance, survey patterns,
  camera/gimbal actions, and virtual-stick controls.
- Enabling a safety-setting write before a physical M210/Cendence validation
  proves the API and readback behavior.
- Treating RTK availability as a universal manual-flight blocker.

### Planning contract

#### Technical approach

Use a pure `FlightReadinessModel` to reduce `DjiDataHub.StatusSnapshot`, mission
context, capability/readback state, and physical acknowledgements into immutable
rows and a single readiness state. `OperateMvpView` owns rendering and touch
state only. `MissionMvpView` consumes the same model result for its Start gate;
it must not duplicate or reinterpret individual DJI callbacks.

`DjiDataHub` remains the one place that attaches recurring DJI callbacks. It
will expose capability-aware, one-shot flight-controller configuration reads and
explicit command methods only as they are validated; their completion callbacks
return to the readiness reducer through a small repository/controller boundary.

#### Assumptions and hardware gates

- The existing `StatusSnapshot` has enough passive data for the first read-only
  connection, RTK/GPS, OcuSync, and battery groups. Exact sensing and each
  configuration readback remain capability-gated.
- M210 V2 RTH/landing behavior must be described from DJI state and user-visible
  configuration, not inferred from generic modern DJI products.
- A successful build or simulated UI does not validate a setting write. Hardware
  validation is required before an edit control is enabled.

#### Sources and patterns

- `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiDataHub.java` —
  sole callback ownership, timestamped `StatusSnapshot`, and subscription
  lifecycle.
- `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`
  — current header groups, attention indicators, detail panel, and UI-thread
  render coalescing.
- `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateStatusModel.java`
  and `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/OperateStatusModelTest.java`
  — pure severity/freshness rules and unit-test style.
- `Sample Code/app/src/main/java/com/dji/sdk/sample/missionplanner/MissionPreflight.java`
  and `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/MissionMvpView.java`
  — existing Mission-only preflight gate.
- [QGroundControl Fly View](https://docs.qgroundcontrol.com/Stable_V5.0/en/qgc-user-guide/fly_view/hud.html)
  — compact status indicators, detail-on-tap, and deliberate action confirmation.
- [Matrice 200 Series V2 manual](https://dl.djicdn.com/downloads/m200_v2/20200515/M200_Series_V2_User_Manual_en.pdf)
  — M210 V2 RTH, low-battery, sensing, and landing-protection constraints.

### Implementation units

#### U1. Create the pure readiness domain model

**Goal:** Define immutable readiness rows, automatic severity, physical-check
state, capability state, and the Mission Start decision without Android Views or
DJI setter callbacks.

**Files:**

- Create `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/FlightReadinessModel.java`.
- Create `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/FlightReadinessModelTest.java`.
- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateStatusModel.java` only if shared freshness/severity helpers belong there.

**Approach:**

1. Model `BLOCKED`, `ATTENTION`, `REVIEW_REQUIRED`, and `MISSION_READY` separately
   from per-row severity.
2. Accept only immutable hub snapshots plus explicit mission and acknowledgement
   inputs; do not read Views or call DJI APIs.
3. Return reason codes and human-readable reason data so Operate and Mission use
   one interpretation.

**Test scenarios:**

- Fresh aircraft/link/power/navigation state with incomplete physical checks is
  `REVIEW_REQUIRED`, not ready.
- A stale required Mission input is `BLOCKED` even after physical acknowledgement.
- A low battery is `ATTENTION`; a critical battery is `BLOCKED` for Mission Start.
- RTH-active, missing DJI Home, and missing flight state each return a specific
  blocking reason.
- RTK non-FIX is advisory for ordinary missions and blocking only for an
  RTK-required mission context.
- Manual acknowledgement never clears an automatic blocker.

**Verification:** The model has deterministic unit coverage for every state
transition and exposes no DJI/Android dependency.

#### U2. Add bounded readiness session and diagnostic events

**Goal:** Persist physical acknowledgements only for the current aircraft session
and emit bounded, structured audit events.

**Files:**

- Create `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/FlightReadinessSession.java`.
- Create `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/FlightReadinessSessionTest.java`.
- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/transport/diagnostics/AsyncNdjsonDiagnosticWriter.java` and its tests only through the existing bounded event interface.

**Approach:**

1. Reset acknowledgements at app launch and aircraft disconnect/reconnect; do not
   persist a prior flight's physical confirmation as current truth.
2. Record acknowledgement, reset, readiness transition, and denied Mission Start
   with Android monotonic/receive time.
3. Keep diagnostics asynchronous and bounded; failure to write diagnostics must
   not alter readiness or block UI rendering.

**Test scenarios:**

- An acknowledgement survives normal panel close/reopen but resets on a new
  aircraft session.
- Acknowledgements record identity and time without storing raw video or secrets.
- Diagnostic queue saturation drops according to the existing bounded policy and
  does not block the caller.

**Verification:** Session lifecycle cannot accidentally carry a physical check
between aircraft sessions, and the event stream remains bounded.

#### U3. Extend hub-owned readback and capability representation

**Goal:** Make configuration availability/readback explicit without introducing a
second recurring DJI callback owner.

**Files:**

- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiDataHub.java`.
- Create `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/FlightSafetyConfiguration.java`.
- Create `Sample Code/app/src/test/java/com/dji/sdk/sample/djihub/DjiDataHubReadinessTest.java` or a narrowly scoped pure adapter test.

**Approach:**

1. Add a capability/readback snapshot that distinguishes unsupported, loading,
   available, stale, and failed-read states.
2. Keep recurrent flight/RTK/RC/battery/OcuSync listeners in the hub only; one-shot
   getters and later explicit writes pass through hub-owned methods.
3. Start read-only: do not expose any configuration setter until an exact M210
   hardware validation is recorded for that setting.

**Test scenarios:**

- Product disconnect clears configuration readback and capability state.
- Unsupported APIs produce `UNAVAILABLE`, never a default healthy setting.
- A delayed/failed one-shot read is surfaced without replacing fresher passive
  status.
- Adding the feature does not cause the callback ownership verification to fail.

**Verification:** The feature has one callback owner and the UI can distinguish
unsupported hardware from pending/failed reads.

#### U4. Build the compact Flight Readiness panel in Operate

**Goal:** Add a detail-on-demand panel to the existing header/status behavior
without reducing the primary video area or duplicating status chrome.

**Files:**

- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`.
- Modify `Sample Code/app/src/main/res/layout/view_operate_mvp.xml`.
- Modify/add only necessary Operate panel drawables and strings under
  `Sample Code/app/src/main/res/`.
- Add UI/presentation coverage adjacent to
  `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/OperateStatusModelTest.java`.

**Approach:**

1. Reuse the current chevron/attention affordance as the panel trigger.
2. Render four collapsible groups from `FlightReadinessModel`; use the existing
   compact panel visual language and show source/freshness in details.
3. Keep header rows as the concise summary and retain the red/amber marker only
   when the relevant group has a non-healthy state.
4. Include the session-local physical checklist and an explicit explanation of
   why any row blocks or requires review.

**Test scenarios:**

- A healthy summary opens a detailed panel without obscuring the video surface.
- Each attention marker opens the corresponding group with the reason visible.
- `UNAVAILABLE` and `STALE` states use neutral/warning presentation rather than
  the healthy colour.
- Closing/reopening preserves current-session acknowledgements but does not
  duplicate hub subscriptions.

**Verification:** Operate stays video-first, and every non-ready state has a
visible, comprehensible reason.

#### U5. Apply readiness consistently to Mission Start

**Goal:** Replace the narrow Mission preflight interpretation with the shared
readiness decision while preserving explicit Load/Upload/Start semantics.

**Files:**

- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/missionplanner/MissionPreflight.java`.
- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/MissionMvpView.java`.
- Modify `Sample Code/app/src/test/java/com/dji/sdk/sample/missionplanner/MissionPlannerUnitTestSuite.java`.

**Approach:**

1. Feed Mission the same reduced readiness state/reasons used by Operate.
2. Allow draft editing, Load, and Upload under their existing checks; gate only
   Start on `MISSION_READY`.
3. On denial, show the specific readiness reason and an affordance to open the
   relevant Operate panel; do not send a DJI mission command.

**Test scenarios:**

- A valid loaded/uploaded mission cannot start while physical review is incomplete.
- Manual flight controls are unaffected by any readiness state.
- Existing home/flight-state/RTH blocks remain enforced.
- A mission that does not require RTK may start with an RTK advisory when all
  other requirements pass.
- No start command is issued on any denied path.

**Verification:** Mission Start has a single, explainable gate and retains the
existing explicit command ordering.

#### U6. Add configuration write workflow behind capability gates

**Goal:** Enable only hardware-validated safety settings through an explicit,
auditable confirmation sequence.

**Files:**

- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/djihub/DjiDataHub.java`.
- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/FlightSafetyConfiguration.java`.
- Modify `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`.
- Extend the relevant readiness/session tests.

**Dependencies:** U1, U2, U3, U4.

**Approach:**

1. Implement one setting at a time after device validation: read current value,
   select proposed value, explain consequence, require hold-to-confirm, send the
   one explicit hub command, then request/read the resulting value.
2. Log proposed/current/result values and DJI error description without logging
   secrets.
3. Keep every unvalidated setting read-only with a visible reason.

**Test scenarios:**

- Canceling confirmation sends no command.
- A successful completion triggers readback and a visible pending/readback state.
- A DJI error leaves the prior known value intact and shows the failure.
- Unsupported setting cannot be forced into an edit workflow.
- Callback ownership remains centralized after every UI open/close.

**Verification:** No safety configuration can change without a distinct,
reviewable operator action and validated hardware support.

#### U7. Validate on the exact aircraft and document the capability matrix

**Goal:** Turn the guarded UI from a build-only feature into evidence-backed M210
behavior without flying or issuing unrequested commands.

**Files:**

- Modify `README.md` only if the final operator guide or known limitation changes.
- Modify this `docs/PLAN.md` only to record validated capability outcomes and
  remaining hardware gates.

**Dependencies:** U1–U6.

**Approach:**

1. Bench-test readback and freshness for Cendence, aircraft, OcuSync, RTK/GPS,
   B1/B2, DJI Home, RTH-active state, and exposed sensing/configuration values.
2. For each proposed writable setting, test one operator-confirmed change and
   readback in a controlled props-off/simulator-safe condition before enabling
   it generally.
3. Record unsupported APIs and ambiguous values as unsupported/unknown rather
   than attempting fallbacks.

**Test scenarios:**

- Device connection/disconnection resets readiness and physical review.
- A known stale callback state is displayed as stale after the freshness limit.
- DJI Home/RTH state matches DJI-reported behavior.
- Every enabled write reports successful readback; failed/unsupported writes
  remain disabled afterward.

**Verification:** The operator guide identifies what is physically confirmed,
what remains read-only, and what still needs hardware evidence.

### Verification contract

- Run the existing repository callback-ownership check; readiness must not add a
  direct recurring DJI callback outside `DjiDataHub`.
- Run the Android unit suite, including the new pure readiness, session, hub, and
  mission-gate coverage.
- Build and verify the debug APK before device installation.
- On the Tab S9, visually verify the connection screen and, when hardware is
  available, Operate panel opening/closing, no overlap with primary video, and
  the exact status-to-detail mapping.
- Treat physical M210/Cendence behavior as a separate acceptance layer; build or
  UI success never enables an unvalidated setting write.

### Definition of done

- The header remains minimal, while the Flight Readiness panel explains every
  known attention/blocker state using hub-sourced values and freshness.
- Automatic DJI evidence and physical acknowledgements are visibly distinct.
- Mission Start, and only Mission Start, is denied until automatic blockers are
  clear and the current-session review is complete.
- No new competing DJI callback, hidden flight command, or unbounded diagnostic
  path is introduced.
- Every enabled configuration write has explicit confirmation, completion/error
  feedback, readback, unit coverage, and M210/Cendence hardware evidence.
- Unsupported or unverified controls remain read-only and are documented as such.

## Next work, in order

1. **Flight readiness implementation:** execute the approved issue #2 plan above before enabling broader mission quality work.
2. **Mission hardware validation:** with the connected aircraft, confirm a selected waypoint changes DJI Home, `HOME READY` appears immediately, and a valid draft can Load/Upload. Test Start only in simulator/props-off conditions after explicit operator authorization.
3. **FPV transport validation:** collect bounded secondary framing diagnostics, then prove access units/RTP on UDP 5610 and visual FPV at the edge without regressing primary.
4. **Status accuracy:** characterize OcuSync quality on the exact Cendence firmware and RTK/GPS quality/fix behavior; keep unsupported/missing values explicit.
5. **Edge integration:** coordinate the direct ROS 2 Humble receiver transition in `edge-matrice-transport`; keep Android transport protocol stable until jointly changed and tested.
6. **Backlog progression:** work GitHub issues in the agreed priority order: #2, #1, #5, #3, #4, #7, #6, #11, #10, #8, #9. Do not close an issue based only on a successful build when it has a hardware acceptance gate.

## Acceptance checks

```bash
scripts/test_android.sh
scripts/build_android.sh :app:assembleDebug
scripts/verify_apk.sh
```

For a physical review, install the APK on the Tab S9, tap **Register APP**, then **Open**. Select the Edge IP as `IN USE` once, then verify that the same transport session persists through Operate -> Mission -> Operate. Record separately what was only built, what was exercised in the UI, and what was actually confirmed by DJI hardware/edge counters. A compiled APK never proves flight behavior, DJI Home readback, RTK quality, OcuSync signal, clock synchronization, secondary RTP, or ROS 2 publication.
