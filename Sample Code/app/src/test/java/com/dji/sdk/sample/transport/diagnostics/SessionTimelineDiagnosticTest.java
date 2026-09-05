package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.operate.SessionTimeline;

/** Verifies local timeline evidence stays bounded, escaped, and distinct from media transport. */
public final class SessionTimelineDiagnosticTest {
    private SessionTimelineDiagnosticTest() { }

    public static void run() {
        SessionTimeline timeline = new SessionTimeline(4);
        timeline.record(1L, SessionTimeline.TimeSource.OPERATOR_ACTION, "DJI_HOME_SET", "Requested");
        timeline.record(2L, SessionTimeline.TimeSource.DJI_CALLBACK, "MISSION_UPLOAD_RESULT", "Accepted");
        timeline.record(3L, SessionTimeline.TimeSource.ANDROID_OBSERVATION, "AIRCRAFT_LINK", "Disconnected");
        timeline.record(4L, SessionTimeline.TimeSource.ANDROID_OBSERVATION, "TRANSPORT_HEALTH", "Healthy");
        SessionTimeline.Event event = timeline.record(5L, SessionTimeline.TimeSource.ANDROID_OBSERVATION,
                "BATTERY_LANDING_ADVISORY", "LAND SOON — B2 24% \"review\"");
        String json = JsonEncoder.sessionTimelineEvent("session\n1", event);
        check(!json.contains("\n"), "timeline NDJSON is one physical line");
        check(json.contains("\\n"), "session identifier is escaped");
        check(json.contains("\\\"review\\\""), "detail quotes are escaped");
        check(json.contains("\"time_source\":\"ANDROID_OBSERVATION\""), "source is retained");
        check(json.contains("\"event_type\":\"BATTERY_LANDING_ADVISORY\""), "event type is retained");
        check(!json.contains("h264") && !json.contains("rtp_payload"), "timeline contains no raw media");
        check(timeline.snapshot().size() == 4 && timeline.getDroppedCount() == 1,
                "timeline remains bounded under event overflow");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
