---
title: Issue 1 - Mission Inspector and Editable Waypoint Sequence
type: feat
date: 2026-09-04
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/plans/2026-09-04-0448-feat-operator-mission-workflow-backlog-plan.md
execution: code
---

# Issue 1 - Mission Inspector and Editable Waypoint Sequence

## Goal Capsule

- **Objective:** Give the operator a precise, ordered mission editor for the same local draft already shown on the map.
- **Baseline:** Partial. `MissionRouteEditor` already provides immutable append, insert-after, replace, and delete; numbered markers already map to their route index; draft equality already invalidates a loaded/uploaded mission. The UI only exposes append/delete and a two-action map popup, so no exact waypoint inspection or edit flow is available.
- **Authority:** This work edits only the local `MissionDraft`. `LOAD`, `UPLOAD`, `START`, `PAUSE`, `CANCEL`, and `SET DJI HOME` remain distinct operator actions. It does not add a DJI callback, transport path, or automatic command.
- **Hardware boundary:** JVM and Android build checks can prove editor behavior and wiring. A later consolidated tablet/drone test must prove touch ergonomics and mission-operator behavior.

## Product Contract

### Requirements

- A selected waypoint exposes its one-based route number, exact WGS84 latitude/longitude, and resolved relative altitude.
- The operator can change only that waypoint's coordinates and per-waypoint altitude override; an empty override means the route default applies.
- The operator can insert a waypoint after the selection, move it earlier or later in route order, or delete it.
- Map marker numbering and the inspector order must always describe the same immutable draft.
- An edit makes any locally loaded/uploaded state stale immediately and visibly; it never changes aircraft state on its own.
- Invalid latitude, longitude, altitude, or numeric input cannot replace the existing valid draft.

### Non-goals

- No aircraft mission download/reconciliation (issue #5).
- No route metrics, safety boundaries, session timeline, raw-video work, or map redesign.
- No new flight, gimbal, Cendence, DjiDataHub, or Transport behavior.

### Current-state and impact gate

| Surface | Assessment | Impact / guardrail |
| --- | --- | --- |
| Mission model | Partial: immutable point replacement and ordered insertion exist. | Add only small editor operations needed for ordered movement; retain immutable drafts. |
| Map | Partial: numbered markers and pointer-local popup exist. | Continue rendering from the editor draft; no map callback changes. |
| Mission lifecycle | Existing loaded-draft equality blocks upload/start after changes. | Centralize the local invalidation at every successful edit, then preserve explicit Load/Upload/Start gates. |
| DjiDataHub / Transport | No interaction required. | Do not subscribe, register a setter callback, or change service lifetime. |
| Persistence | Current save/load stores the draft correctly. | Preserve schema; route defaults continue to be saved with each draft. |
| Operator value | High. Map taps alone are insufficient for precise WGS84 missions. | Keep the inspector compact and selection-led rather than adding a second broad editor. |

**Decision:** proceed. The change is additive, local, and has field value without requiring unavailable hardware evidence.

## Technical Design

### UI flow

1. Tapping a numbered map marker opens the existing anchored popup with `INSPECT`, `SET DJI HOME`, and `DELETE`.
2. `INSPECT` opens a compact modal editor for that index. It shows route order, latitude, longitude, resolved altitude, and whether altitude inherits the route default.
3. `SAVE WAYPOINT` parses and validates all fields before calling one immutable editor replacement. `INSERT AFTER`, `MOVE EARLIER`, `MOVE LATER`, and `DELETE` act only on the selected ordered entry.
4. Every successful route mutation refreshes the map, preserves/selects the appropriate index, and clears local loaded/uploaded evidence with an explicit `Mission changed. Load it again before upload or start.` status.
5. Cancel/dismiss has no mutation. Existing `SAVE DRAFT` remains the only persistence action.

### Code boundaries

- `MissionRouteEditor`: add bounded order-change helpers if needed (`moveEarlier`, `moveLater`) and unit-test them. Do not put Android UI or DJI state here.
- `MissionMvpView`: owns selection, parsing, dialog controls, draft invalidation, UI refresh, and the existing explicit command controls.
- `MapLibreMissionMapView`: remains a render/tap boundary and continues to derive marker numbers from the ordered draft.
- `MissionPlannerUnitTestSuite`: verifies pure ordered-mutation, equality, and invalid-input behavior; Android build verifies resource/UI wiring.

## Implementation Units

### U1. Harden ordered draft mutations

- Inspect `MissionRouteEditor` and validator behavior before editing.
- Add only pure helpers necessary to reorder one item without changing its coordinates, altitude override, or route defaults.
- Add JVM tests for earlier/later moves, bounds, and order preservation.
- **Stop:** if the current editor cannot express the operation without changing the immutable draft contract, stop and revise this plan before UI work.

### U2. Add a waypoint inspector dialog

- Extend the marker popup with `INSPECT`; retain `SET DJI HOME` and `DELETE` unchanged.
- Build the inspector programmatically in `MissionMvpView` to match the current compact panel style and avoid adding a parallel screen.
- Render index, exact coordinate fields, altitude override/inherited state, and edit/order actions.
- Parse first, validate the candidate `MissionDraft`, and only then replace the selected point.
- **Stop:** do not apply a partial edit after any parse/validation failure.

### U3. Make local state truthfully stale after edits

- Introduce one local helper that clears `loadedMission`, `loadedDraft`, and `uploaded` only after a successful local route mutation.
- Use it for map append, dialog save/insert/reorder/delete, existing delete, and opening a saved draft where appropriate.
- Refresh the map and route count after mutations; maintain a valid selected index.
- Do not state or imply that clearing local state changes a mission already on the aircraft.

### U4. Verify and review

- Run focused JVM tests, callback-ownership verification, full offline Android test suite, debug APK build, and APK verification.
- Inspect the diff for accidental `DjiDataHub`, Transport, callback, command, or persistence-schema changes.
- Record this issue's status and test evidence on GitHub. Hardware validation remains deferred to the single consolidated test.

## Verification Contract

- Editing one selected waypoint changes only that ordered entry and invalidates local loaded/uploaded state.
- Inserting, moving, and deleting keep the inspector and map marker numbers in matching order.
- Invalid WGS84 values, negative altitude override, or invalid numeric input leave the prior draft untouched.
- Closing the inspector without Save does not mutate the draft.
- Existing explicit `LOAD`, `UPLOAD`, `START`, `PAUSE`, `CANCEL`, and `SET DJI HOME` actions still compile and retain their separation.
- `scripts/verify_dji_callback_ownership.sh`, `scripts/test_android.sh --offline`, `scripts/build_android.sh :app:assembleDebug`, and `scripts/verify_apk.sh` pass.

## Definition of Done

- The operator can inspect and precisely edit a selected waypoint without relying on map-tap precision.
- Ordered list behavior and numbered map markers remain consistent.
- Local edits never masquerade as an aircraft upload and do not alter shared callback or Transport ownership.
- Code-level verification passes; physical behavior is explicitly pending the final consolidated tablet/drone test.

## Review Record

- `ce-doc-review` could not dispatch its required reviewer personas because this harness exposes no subagent primitive. The plan was manually checked for scope, command-authority, callback-ownership, and verification coherence; no automatic document changes were applied.
