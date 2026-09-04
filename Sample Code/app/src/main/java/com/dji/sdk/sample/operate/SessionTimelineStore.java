package com.dji.sdk.sample.operate;

import android.os.SystemClock;

/** Process-wide app-local timeline; recording is bounded and performs no I/O. */
public final class SessionTimelineStore {
    private static final SessionTimeline TIMELINE = new SessionTimeline(256);
    private SessionTimelineStore() { }
    public static void operator(String type, String detail) { TIMELINE.record(SystemClock.elapsedRealtime(), SessionTimeline.TimeSource.OPERATOR_ACTION, type, detail); }
    public static void dji(String type, String detail) { TIMELINE.record(SystemClock.elapsedRealtime(), SessionTimeline.TimeSource.DJI_CALLBACK, type, detail); }
    public static void android(String type, String detail) { TIMELINE.record(SystemClock.elapsedRealtime(), SessionTimeline.TimeSource.ANDROID_OBSERVATION, type, detail); }
    public static SessionTimeline timeline() { return TIMELINE; }
}
