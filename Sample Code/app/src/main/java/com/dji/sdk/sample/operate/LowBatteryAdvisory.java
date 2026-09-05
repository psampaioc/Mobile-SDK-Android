package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

/** Pure local presentation rule; it has no DJI API or transport dependency. */
public final class LowBatteryAdvisory {
    public static final int DEFAULT_THRESHOLD_PERCENT = 30;
    public static final int CRITICAL_PERCENT = 15;

    public enum Severity { CLEAR, LAND_SOON, LAND_NOW, UNAVAILABLE }

    public static final class Snapshot {
        private final Severity severity;
        private final int limitingBatteryIndex;
        private final int limitingPercent;

        private Snapshot(Severity severity, int limitingBatteryIndex, int limitingPercent) {
            this.severity = severity;
            this.limitingBatteryIndex = limitingBatteryIndex;
            this.limitingPercent = limitingPercent;
        }

        @NonNull public Severity getSeverity() { return severity; }
        public int getLimitingBatteryIndex() { return limitingBatteryIndex; }
        public int getLimitingPercent() { return limitingPercent; }

        @NonNull public String render() {
            if (severity == Severity.UNAVAILABLE) return "BATTERY DATA UNAVAILABLE";
            if (severity == Severity.CLEAR) return "BATTERY ADVISORY CLEAR";
            return (severity == Severity.LAND_NOW ? "LAND NOW" : "LAND SOON")
                    + " — B" + (limitingBatteryIndex + 1) + " " + limitingPercent + "%";
        }
    }

    private LowBatteryAdvisory() { }

    public static boolean isValidThreshold(int percent) {
        return percent >= CRITICAL_PERCENT && percent <= 100;
    }

    public static int normalizedThreshold(int percent) {
        return isValidThreshold(percent) ? percent : DEFAULT_THRESHOLD_PERCENT;
    }

    @NonNull public static Snapshot evaluate(int batteryOnePercent, boolean batteryOneFresh,
            int batteryTwoPercent, boolean batteryTwoFresh, int thresholdPercent) {
        if (!batteryOneFresh || !batteryTwoFresh || batteryOnePercent < 0 || batteryTwoPercent < 0) {
            return new Snapshot(Severity.UNAVAILABLE, -1, -1);
        }
        int threshold = normalizedThreshold(thresholdPercent);
        int limitingIndex = batteryOnePercent <= batteryTwoPercent ? 0 : 1;
        int limitingPercent = limitingIndex == 0 ? batteryOnePercent : batteryTwoPercent;
        Severity severity = limitingPercent <= CRITICAL_PERCENT ? Severity.LAND_NOW
                : limitingPercent <= threshold ? Severity.LAND_SOON : Severity.CLEAR;
        return new Snapshot(severity, limitingIndex, limitingPercent);
    }
}
