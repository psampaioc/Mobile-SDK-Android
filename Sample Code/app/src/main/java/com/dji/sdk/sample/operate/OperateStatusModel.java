package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

/** Pure presentation rules for the passive Operate status and pre-flight review. */
public final class OperateStatusModel {
    public static final long STATUS_STALE_AFTER_MS = 5_000L;

    public enum Severity { HEALTHY, WARNING, CRITICAL, STALE, UNAVAILABLE }

    private OperateStatusModel() { }

    @NonNull
    public static Severity freshness(long receivedAtMs, long nowMs, boolean supported) {
        if (!supported) return Severity.UNAVAILABLE;
        if (receivedAtMs < 0L) return Severity.UNAVAILABLE;
        return nowMs - receivedAtMs > STATUS_STALE_AFTER_MS ? Severity.STALE : Severity.HEALTHY;
    }

    @NonNull
    public static Severity battery(int percent, long receivedAtMs, long nowMs, boolean supported) {
        Severity freshness = freshness(receivedAtMs, nowMs, supported);
        if (freshness != Severity.HEALTHY) return freshness;
        if (percent < 0) return Severity.UNAVAILABLE;
        if (percent <= 15) return Severity.CRITICAL;
        if (percent <= 30) return Severity.WARNING;
        return Severity.HEALTHY;
    }

    @NonNull
    public static String preflightLabel(boolean automaticAttention, int incompletePhysicalChecks) {
        if (automaticAttention) return "ATTENTION";
        return incompletePhysicalChecks > 0 ? "REVIEW REQUIRED" : "REVIEW COMPLETE";
    }

    public static boolean manualAcknowledgementCanPassAutomatic(boolean automaticAttention) {
        return !automaticAttention;
    }

    /** Any state other than a fresh healthy value needs operator review; it is never a pass. */
    public static boolean requiresAttention(@NonNull Severity severity) {
        return severity != Severity.HEALTHY;
    }
}
