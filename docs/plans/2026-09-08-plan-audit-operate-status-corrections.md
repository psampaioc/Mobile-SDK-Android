---
title: Plan-Audit Corrections - Operate Status Details
type: refactor
date: 2026-09-08
topic: operate-status-details-audit-corrections
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
execution: code
origin_plan: docs/plans/2026-09-07-1905-refactor-operate-status-details-plan.md
---

# Plan-Audit Corrections - Operate Status Details

## Goal Capsule

- **Objective:** Close the verified gaps in the Operate status-detail refactor without broadening its scope or adding DJI commands.
- **Authority:** `DjiDataHub` remains the only DJI callback owner. All changes consume hub snapshots and may not issue aircraft, motor, home, gimbal, RTH, or mission commands.
- **Scope:** Restore complete Power detail content, preserve `WAITING FOR SAMPLE` versus `STALE` versus `UNAVAILABLE` in the readiness/detail presentation, and add focused regression coverage for the panel's pure state rules.

## Audit Findings and Requirements

| Finding | Severity | Required correction |
| --- | --- | --- |
| `powerDetail` is built in `OperateMvpView.renderStatusOnMain()` but never rendered by `renderExpandedDetail()`. The Power sheet omits app-only landing advisory, battery sample freshness, pack details, and threshold context. | P1 | Render the live Power detail from the same current hub snapshot used by header/readiness, without reintroducing duplicated rows into another group. |
| `FlightReadinessStore` reduces both stale and absent mandatory samples to a boolean, and `FlightReadinessModel` renders both as `UNAVAILABLE`. This contradicts the plan's explicit evidence vocabulary. | P2 | Preserve a small evidence state through the reducer so a connected-but-old sample is visibly `STALE`, while never-received remains `WAITING FOR SAMPLE` and missing capability remains `UNAVAILABLE`. |
| No pure regression test proves the selected-group/anchor contract or component-present/no-quality semantics. | P2 | Extract only the minimal pure state needed for deterministic tests; do not introduce a second listener or Android-view test harness. |

## Settled Decisions

- Keep exactly three header chevrons (Link, Nav, Power); Safety remains an internal tab.
- Internal tab changes preserve the panel's existing anchor; only a header chevron supplies a new anchor.
- A missing OcuSync-quality callback never invents a disconnected OcuSync component or blocks readiness.
- Physical checklist entries remain manual acknowledgements and must not claim that the app detected propellers, weather, terrain, or obstacles.

## Implementation Units

### U1 - Preserve evidence semantics in the reducer

- **Files:** `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/FlightReadinessModel.java`, `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/FlightReadinessStore.java`, `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateStatusModel.java`.
- **Approach:** Replace boolean-only freshness inputs for mandatory live evidence with a compact, dependency-free evidence state. Map a hub capability plus no sample to `WAITING FOR SAMPLE`, a received old sample to `STALE`, and a missing component to `UNAVAILABLE`; map all three to the existing appropriate readiness blocker/advisory semantics without treating stale data as healthy.
- **Acceptance checks:** Flight, RTK, battery, and OcuSync component/downlink presentation distinguish the three states; stale B1/B2/RC cannot yield a ready assessment; component-present/no-quality remains non-blocking.

### U2 - Render complete, group-owned Power detail

- **Files:** `Sample Code/app/src/main/java/com/dji/sdk/sample/operate/OperateMvpView.java`.
- **Approach:** Have the Power group append the existing live-only Power facts at render time: Cendence/B1/B2 detail, sample state, app-only landing advisory, configured threshold, and explicit statement that DJI protection is unchanged. Remove obsolete cached detail strings if they become unused. Do not move Power information into Link, Nav, or Safety.
- **Acceptance checks:** Opening Power displays all above content; stale/absent values are shown as such; refreshed hub/battery data updates an open panel; no additional DJI listener is installed.

### U3 - Add pure regression coverage for detail-state rules

- **Files:** `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/FlightReadinessModelTest.java`, `Sample Code/app/src/test/java/com/dji/sdk/sample/operate/OperateStatusModelTest.java`, and a focused new pure detail-state test only if extraction is necessary.
- **Approach:** Cover component-present/no-quality, stale versus waiting evidence, stale batteries, one-group-only row assignment, and top-anchor versus internal-tab state. Keep Android visual behavior in the Tab S9 checklist rather than mocking views.
- **Acceptance checks:** New tests fail before the reducer/state correction and pass afterward; existing readiness, mission, transport, and ownership tests remain green.

### U4 - Verify and hand off

- **Files:** no production files expected.
- **Approach:** Run the Android unit suite, debug assembly, APK verification, callback-ownership verifier, and diff check. Install or visually inspect only with separate current-task authorization; no aircraft commands.
- **Acceptance checks:** Every local gate passes; the remaining bench list explicitly requires a connected props-off aircraft for live evidence and visual anchoring.

## Verification Contract

- `scripts/test_android.sh --offline`
- `scripts/build_android.sh :app:assembleDebug`
- `scripts/verify_apk.sh`
- `scripts/verify_dji_callback_ownership.sh`
- `git diff --check`

## Definition of Done

- Power detail contains its live, group-owned advisory and battery evidence.
- The reducer/presentation distinguishes `WAITING FOR SAMPLE`, `STALE`, and `UNAVAILABLE`; none of those states can be displayed as a healthy readiness pass.
- OcuSync component presence remains separate from quality callback freshness.
- Regression coverage proves the corrected pure state rules and all local gates pass.
- No direct DJI callback is added outside `DjiDataHub`, and no DJI command path is added.
- Hardware validation remains explicitly pending until a props-off M210/Cendence bench can provide live data.
