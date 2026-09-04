---
title: Issue 11 - Flight Session Timeline and Operator Log
type: feat
date: 2026-09-04
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: docs/plans/2026-09-04-0448-feat-operator-mission-workflow-backlog-plan.md
execution: code
---

# Issue 11 - Flight Session Timeline and Operator Log

## Goal Capsule

- **Objective:** Provide bounded, readable local evidence of a flight session without raw video or a second telemetry transport.
- **Baseline:** Partial. `AsyncNdjsonDiagnosticWriter` is bounded/non-blocking and captures telemetry/readiness events while Transport runs. It has no unified event vocabulary, no app-level mission/operator events, no readable timeline, and no export surface.
- **Authority:** Timeline entries report app/DJI observations and explicit user actions. They never trigger a command or reinterpret a source timestamp.

## Product Contract

- Add a bounded in-memory `SessionTimeline` with events carrying `android_elapsed_ms`, `time_source` (`OPERATOR_ACTION`, `DJI_CALLBACK`, `ANDROID_OBSERVATION`), type, and human-readable detail.
- Connect explicit Mission actions, Mission operator lifecycle callbacks, readiness transitions, RTH/link state changes, and Transport start/stop/health transitions where existing seams permit.
- Persist/export timeline entries through the existing bounded NDJSON writer only when Transport diagnostic output is active; otherwise retain bounded memory with no blocking file work.
- Provide a read-only Mission/Operate accessible session log dialog and local share/export path. Never include raw RTP/H.264 payloads.

## Implementation Units

### U1. Bounded pure timeline model

- Add event/value classes with capacity/overflow accounting and unit tests for order, capacity, and time-source labels.

### U2. Record mission/readiness/transport events

- Add a central app-local timeline singleton; instrument only existing explicit command handlers and listener callbacks.
- Reuse `AsyncNdjsonDiagnosticWriter.writeJsonRecord` with a small timeline JSON record. No new UDP field/protocol.

### U3. Read and export evidence

- Add a compact read-only session-log dialog reachable from Mission or Operate, listing timestamp source and event detail.
- Export text/NDJSON through an Android share intent or existing diagnostics location, with failure shown locally and without blocking DJI callbacks.

### U4. Verify and record

- Run timeline unit tests, callback ownership, Android tests, debug build, APK verification, and diff review. Defer actual operator/device lifecycle proof to the consolidated test.

## Verification Contract

- Oldest events are boundedly dropped with a visible counter, never blocking a producer.
- Operator actions and DJI callback observations remain distinguishable.
- No raw-video data enters the timeline or export.
- Timeline UI does not register DJI setters or send a command.

## Definition of Done

- A session can be read and exported locally with truthful time-source labels and bounded behavior.
- Code-level verification passes; connected-hardware evidence remains deferred to the final consolidated test.

## Review Record

- `ce-doc-review` could not dispatch its required reviewer personas because this harness exposes no subagent primitive. The plan was manually reviewed for boundedness, source-time truthfulness, and no raw-video scope.
