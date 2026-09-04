---
title: Issue 6 - Local Planning Safety Boundaries
type: feat
date: 2026-09-04
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/plans/2026-09-04-0448-feat-operator-mission-workflow-backlog-plan.md
execution: code
---

# Issue 6 - Local Planning Safety Boundaries

## Goal Capsule

- **Objective:** Let an operator create, retain, visualize, and receive warnings for local inclusion/exclusion circles and polygons while never presenting them as DJI geofences.
- **Baseline:** Absent. Mission drafts and the MapLibre view have no boundary model, persistence, intersection logic, or advisory UI.
- **Authority:** Boundaries are local planning evidence. They never subscribe to DJI, send a command, block Cendence/manual control, or claim autonomous aircraft avoidance.

## Product Contract

- Model named local profiles containing inclusion and exclusion circles/polygons.
- Persist a chosen profile reference with a mission draft or allow selecting a named profile locally; existing mission JSON remains backward compatible.
- Evaluate each route segment against each boundary and render a concise warning. For inclusion zones, warn when route points/segments are outside; for exclusion zones, warn on entering/crossing.
- Map styling distinguishes inclusion, exclusion, and route; text always states `LOCAL PLANNING WARNING — NOT DJI ENFORCED`.

## Implementation Units

### U1. Pure geometry and persistence model

- Add immutable coordinate, boundary, profile, and evaluation classes under `missionplanner`.
- Use robust local planar projection around the profile/route reference for circles, point-in-polygon, and segment intersections; reject invalid/degenerate shapes.
- Add JSON version-tolerant profile storage and an optional selected-profile name in drafts, preserving old drafts without a boundary.
- Add JVM tests for circle/polygon inside, crossing, outside, invalid shapes, and profile round-trip.

### U2. Local profile controls

- Add compact Mission controls to select/save an explicitly named profile and create circle/polygon geometry by explicit coordinate entry (not ambiguous map gestures as the only method).
- Do not overload waypoint taps. Scope the map mode explicitly for boundary vertex/circle-center actions when enabled.

### U3. Map and advisory output

- Extend only `MapLibreMissionMapView` rendering to draw selected profile geometry and route warnings.
- Re-evaluate after route/profile changes and show warning count/details near route estimates.
- No preflight gating or mission state mutation.

### U4. Verification and record

- Run geometry/unit tests, callback ownership, full Android tests, debug build, APK verification, and a diff review for no DJI/Transport command changes.
- Record code-level completion; map ergonomics and live operator validation stay in the consolidated tablet test.

## Verification Contract

- Valid circles and polygons save/load as local profiles and do not modify DJI mission data or aircraft state.
- Route crossings or outside-inclusion cases are detected for all route segments, not just waypoint points.
- Invalid geometry is rejected without replacing an existing valid profile.
- Every advisory presentation names its local/non-enforced limitation, and manual/explicit DJI controls remain available.

## Definition of Done

- The operator can define/select local circle and polygon constraints, see map and textual route warnings, and retain them locally.
- Boundaries are truthful advisory-only behavior, with complete code-level verification and pending physical UI validation.

## Review Record

- `ce-doc-review` could not dispatch its required reviewer personas because this harness exposes no subagent primitive. The plan was manually checked for geometry integrity, backward-compatible persistence, and the no-enforcement boundary.
