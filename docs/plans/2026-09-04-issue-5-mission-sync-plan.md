---
title: Issue 5 - Mission Synchronization With Aircraft
type: feat
date: 2026-09-04
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/plans/2026-09-04-0448-feat-operator-mission-workflow-backlog-plan.md
execution: code
---

# Issue 5 - Mission Synchronization With Aircraft

## Goal Capsule

- **Objective:** Make the difference between a local draft, the app-loaded mission, confirmed upload, active execution, and unknown remote mission state unambiguous.
- **Baseline:** Partial. The screen already separates load, upload, and start actions, and blocks start after local changes. It has no rendered lifecycle state, its download listener is empty, and it treats a successful upload as sufficient without clarifying that reconnect/readback uncertainty remains.
- **MSDK evidence:** The official sample permits `WaypointMissionOperator.downloadMission()` only in `EXECUTING` or `EXECUTION_PAUSED`. Download progress reveals completion/count, not a general inactive-aircraft mission payload. Therefore a disconnected, idle, or not-read-back aircraft mission must remain `REMOTE UNKNOWN`.
- **Authority:** Download is an explicit operator action. No download, load, upload, start, pause, cancel, or home action becomes automatic. DjiDataHub and Transport remain untouched.

## Product Contract

### Required visible states

- `LOCAL DRAFT` — a local route exists but has not been loaded into this app session.
- `APP LOADED` — the current local draft was accepted by `operator.loadMission`; this is not aircraft confirmation.
- `UPLOADED THIS SESSION` — upload completed successfully in the current app session; it becomes stale when the draft changes, the app reconnects, or download cannot corroborate it.
- `EXECUTING` / `PAUSED` — current operator state reported by DJI.
- `REMOTE UNKNOWN` — no truthful current readback evidence exists for the aircraft mission.

### Explicit download behavior

1. The right panel gains a visible `DOWNLOAD` action beside `LOAD` and `UPLOAD`.
2. Tapping it only calls the SDK when the current operator state is `EXECUTING` or `EXECUTION_PAUSED`.
3. Otherwise it explains that DJI only supports mission download while executing or paused, retains `REMOTE UNKNOWN`, and changes no local draft.
4. Download-progress/completion is reported as limited evidence; it must not overwrite the local route because the V4 download API does not provide a complete independent mission model here.

### Impact assessment

| Surface | Decision |
| --- | --- |
| Callback ownership | No setter callback. The existing mission-operator listener remains view-scoped and is not a DjiDataHub concern. |
| Explicit commands | Preserve the separate user gestures for all mission operations. |
| Lifecycle / persistence | Add only an in-memory presentation state; drafts stay local and schema remains unchanged. |
| Transport | No change. |
| UI | Add a compact state line and one action; do not reopen the mission editor design. |

## Implementation Units

### U1. Define a pure lifecycle presentation model

- Add a small package-local or missionplanner model translating local flags plus `WaypointMissionState` into truthful labels.
- Include transitions for initial, loaded, upload-success, local mutation, SDK execution/paused, disconnect/re-attach, download-start/progress/finish/error.
- Unit-test that a local draft is never rendered as remote confirmation and that unsupported download states remain unknown.

### U2. Wire the state into `MissionMvpView`

- Replace ad-hoc `loadedMission`/`uploaded` rendering with named state updates while retaining existing start gate semantics.
- Add one compact status TextView and a `DOWNLOAD` control.
- Reset remote evidence on attach/reconnect and after local mutation; preserve it only when supported callbacks provide evidence in the same session.

### U3. Implement guarded download evidence

- Inspect the live `operator.getCurrentState()` at the explicit button press.
- Guard to `EXECUTING` and `EXECUTION_PAUSED`, call `downloadMission` only there, and surface callback error/progress/completion precisely.
- Do not call `getLoadedMission()` as aircraft proof unless confirmed by the SDK event contract; treat it as app/operator-local.

### U4. Verify and record

- Run focused lifecycle unit tests, callback ownership validation, offline Android tests, debug APK build, and APK verification.
- Diff-review for accidental changes to hub, transport, draft persistence, or automatic commands.
- Record code-level evidence on GitHub; retain physical proof of DJI download and reconnection semantics for the consolidated hardware test.

## Verification Contract

- Initial or reattached screen displays `REMOTE UNKNOWN`, not uploaded/aircraft-ready.
- A successful local load shows `APP LOADED`, while upload success shows only `UPLOADED THIS SESSION`.
- Editing a waypoint clears loaded/uploaded evidence and restores `REMOTE UNKNOWN`.
- `DOWNLOAD` outside execution/paused does not call DJI download and states the MSDK limitation.
- Download callback errors never replace the draft or claim remote confirmation.
- Existing mission command actions remain explicit and compile; callback ownership and full Android verification pass.

## Definition of Done

- The operator cannot confuse a local/app-session state with an independently proven aircraft mission.
- The app exposes the only supported explicit download path without implying a general idle-aircraft readback.
- Hardware validation of the M210's actual download behavior remains explicitly pending.

## Review Record

- `ce-doc-review` could not dispatch its required reviewer personas because this harness exposes no subagent primitive. The plan was manually checked against the official sample and the V4.18 class surface; no automatic document changes were applied.
