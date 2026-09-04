package com.dji.sdk.sample.operate;

/** Dependency-free JVM contract tests for passive Operate status semantics. */
public final class OperateStatusModelTest {
    private OperateStatusModelTest() { }

    public static void run() {
        equal(OperateStatusModel.Severity.UNAVAILABLE,
                OperateStatusModel.freshness(-1L, 10L, true), "no callback is unavailable");
        equal(OperateStatusModel.Severity.UNAVAILABLE,
                OperateStatusModel.freshness(10L, 10L, false), "unsupported is unavailable");
        equal(OperateStatusModel.Severity.HEALTHY,
                OperateStatusModel.freshness(10L, 10L + OperateStatusModel.STATUS_STALE_AFTER_MS, true),
                "threshold itself remains fresh");
        equal(OperateStatusModel.Severity.STALE,
                OperateStatusModel.freshness(10L, 11L + OperateStatusModel.STATUS_STALE_AFTER_MS, true),
                "older callback is stale");
        equal(OperateStatusModel.Severity.CRITICAL,
                OperateStatusModel.battery(15, 10L, 10L, true), "critical battery threshold");
        equal(OperateStatusModel.Severity.WARNING,
                OperateStatusModel.battery(30, 10L, 10L, true), "warning battery threshold");
        equal("ATTENTION", OperateStatusModel.preflightLabel(true, 0),
                "automatic attention wins over manual checks");
        equal("REVIEW REQUIRED", OperateStatusModel.preflightLabel(false, 1),
                "manual physical check remains visible");
        equal("REVIEW COMPLETE", OperateStatusModel.preflightLabel(false, 0),
                "review is complete only when both classes pass");
        if (OperateStatusModel.manualAcknowledgementCanPassAutomatic(true)) {
            throw new AssertionError("manual acknowledgement cannot clear automatic attention");
        }
        if (!OperateStatusModel.requiresAttention(OperateStatusModel.Severity.STALE)
                || OperateStatusModel.requiresAttention(OperateStatusModel.Severity.HEALTHY)) {
            throw new AssertionError("only fresh healthy status may pass automatic review");
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected
                + ", got " + actual);
    }
}
