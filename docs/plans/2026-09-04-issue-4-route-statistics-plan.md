---
title: Issue 4 - Route Statistics and Conservative Mission Feasibility
type: feat
date: 2026-09-04
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/plans/2026-09-04-0448-feat-operator-mission-workflow-backlog-plan.md
execution: code
---

# Issue 4 - Route Statistics and Conservative Mission Feasibility

## Goal Capsule

- **Objective:** Show compact, traceable route consequences before upload without inventing terrain, battery reserve, or DJI enforcement.
- **Baseline:** Absent. The immutable draft contains WGS84 waypoints, default/override relative altitudes, and speed; no statistics or feasibility output exists.
- **Authority:** Values are local planning estimates only. They do not gate or alter `LOAD`, `UPLOAD`, `START`, Cendence control, or DJI safety logic.

## Product Contract

- Compute segment distance using a documented spherical WGS84 approximation, total route distance, waypoint count, min/max resolved relative altitude, total climb/descent, and travel-time estimate from route distance/base speed.
- Render a compact read-only panel below the route/sync evidence with its inputs and uncertainty: `TERRAIN UNKNOWN · BATTERY/RESERVE NOT ESTIMATED`.
- Invalid/insufficient drafts render unavailable values; no estimate is fabricated.
- Recompute only when the local draft changes or is loaded/saved; stats never require a DJI callback or transport data.

## Implementation Units

### U1. Pure route-statistics calculator

- Add a dependency-free model under `missionplanner` using resolved waypoint altitude and haversine segment distance.
- Return an immutable result with availability plus values, not sentinel healthy values.
- Unit-test empty/one-point route, known short segment, overrides, zero/invalid speed, and climb/descent direction.

### U2. Advisory presentation

- Add one compact text panel in `MissionMvpView`/existing layout.
- Update it from the same local route refresh path. Clearly label estimates as local and terrain/battery as unknown.
- Do not add a preflight error or mutate mission state from statistics.

### U3. Verification and record

- Run focused mission tests, callback ownership verification, offline Android test suite, debug build, and APK verification.
- Diff-review no changes to transport, DJI commands, callbacks, or persistence schema.
- Record code evidence on issue #4; defer physical checks to the consolidated test.

## Verification Contract

- Statistics exactly reflect the same ordered draft rendered on the map.
- Altitude ranges, climb/descent, and duration use supported local inputs only.
- Terrain and battery are visibly unknown, never passed off as safe.
- Advisory output cannot block an explicit DJI mission command.

## Definition of Done

- A field operator can inspect route distance, count, altitude range, estimated travel time, and climb/descent with honest uncertainty before upload.
- All values remain local advisory evidence and all code-level verification passes.

## Review Record

- `ce-doc-review` could not dispatch its required reviewer personas because this harness exposes no subagent primitive. The plan was manually checked for data-source truthfulness, advisory-only behavior, and scope isolation.
