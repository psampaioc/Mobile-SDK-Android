package com.dji.sdk.sample.operate;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.djihub.CallbackMulticaster;

/** Process-wide app-local timeline; recording is bounded and performs no I/O. */
public final class SessionTimelineStore {
    private static final SessionTimeline TIMELINE = new SessionTimeline(256);
    private static final CallbackMulticaster<Listener> LISTENERS = new CallbackMulticaster<>();

    public interface Listener {
        void onTimelineEvent(@NonNull SessionTimeline.Event event);
    }

    private SessionTimelineStore() { }

    public static void operator(String type, String detail) {
        record(SessionTimeline.TimeSource.OPERATOR_ACTION, type, detail);
    }

    public static void dji(String type, String detail) {
        record(SessionTimeline.TimeSource.DJI_CALLBACK, type, detail);
    }

    public static void android(String type, String detail) {
        record(SessionTimeline.TimeSource.ANDROID_OBSERVATION, type, detail);
    }

    @NonNull public static CallbackMulticaster.Subscription addListener(@NonNull Listener listener) {
        return LISTENERS.add(listener);
    }

    public static SessionTimeline timeline() { return TIMELINE; }

    private static void record(SessionTimeline.TimeSource source, String type, String detail) {
        SessionTimeline.Event event = TIMELINE.record(SystemClock.elapsedRealtime(), source, type, detail);
        LISTENERS.dispatch(listener -> listener.onTimelineEvent(event));
    }
}
