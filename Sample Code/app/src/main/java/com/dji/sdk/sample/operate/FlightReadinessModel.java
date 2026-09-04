package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, DJI-independent reduction of passive aircraft evidence and the operator's current-session
 * review into the one decision consumed by Operate and Mission Start. It never issues commands.
 */
public final class FlightReadinessModel {
    public enum State { BLOCKED, ATTENTION, REVIEW_REQUIRED, MISSION_READY }

    public enum Group { AIRCRAFT_LINK, NAVIGATION_HOME, POWER, SAFETY_CONFIGURATION }

    public enum RowState { HEALTHY, ATTENTION, BLOCKED, UNAVAILABLE }

    public enum Reason {
        AIRCRAFT_UNAVAILABLE("Aircraft connection is unavailable."),
        CENDENCE_UNAVAILABLE("Cendence connection is unavailable."),
        OCU_SYNC_UNAVAILABLE("DJI OcuSync link is unavailable or stale."),
        FLIGHT_STATE_STALE("Aircraft flight state is unavailable or stale."),
        DJI_HOME_UNAVAILABLE("DJI Home Point is unavailable."),
        RTH_ACTIVE("RTH is active."),
        AIRCRAFT_BATTERY_CRITICAL("Drone battery is critical."),
        AIRCRAFT_BATTERY_LOW("Drone battery is low."),
        CENDENCE_BATTERY_CRITICAL("Cendence battery is critical."),
        CENDENCE_BATTERY_LOW("Cendence battery is low."),
        RTK_FIX_REQUIRED("This mission requires RTK FIX."),
        RTK_NOT_FIXED("RTK is not FIX; GPS mission remains possible."),
        PHYSICAL_REVIEW_INCOMPLETE("Complete the current-session physical flight review."),
        SAFETY_CONFIGURATION_UNAVAILABLE("Safety configuration is read-only until hardware validation.");

        private final String message;
        Reason(String message) { this.message = message; }
        @NonNull public String getMessage() { return message; }
    }

    public static final class Row {
        private final Group group;
        private final String label;
        private final String value;
        private final RowState state;
        private final Reason reason;

        private Row(Group group, String label, String value, RowState state, Reason reason) {
            this.group = group;
            this.label = label;
            this.value = value;
            this.state = state;
            this.reason = reason;
        }

        @NonNull public Group getGroup() { return group; }
        @NonNull public String getLabel() { return label; }
        @NonNull public String getValue() { return value; }
        @NonNull public RowState getState() { return state; }
        public Reason getReason() { return reason; }
    }

    public static final class Decision {
        private final State state;
        private final Reason primaryReason;
        private final List<Row> rows;

        private Decision(State state, Reason primaryReason, List<Row> rows) {
            this.state = state;
            this.primaryReason = primaryReason;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }

        @NonNull public State getState() { return state; }
        public Reason getPrimaryReason() { return primaryReason; }
        @NonNull public List<Row> getRows() { return rows; }
        /** Only a hard automatic blocker or missing physical review prevents this app's Start. */
        public boolean canStartMission() {
            return state == State.MISSION_READY || state == State.ATTENTION;
        }
        @NonNull public String getExplanation() {
            return primaryReason == null ? "No known app blocker for this prepared mission."
                    : primaryReason.getMessage();
        }
    }

    /** Immutable inputs deliberately contain only already-received passive data. */
    public static final class Input {
        public final boolean aircraftConnected;
        public final boolean cendenceConnected;
        public final boolean ocuSyncFresh;
        public final boolean flightStateFresh;
        public final boolean djiHomeAvailable;
        public final boolean rthActive;
        public final boolean gpsFresh;
        public final boolean rtkFresh;
        public final boolean rtkFix;
        public final boolean rtkRequired;
        public final boolean safetyConfigurationAvailable;
        public final int cendenceBatteryPercent;
        public final int aircraftBatteryOnePercent;
        public final int aircraftBatteryTwoPercent;
        public final boolean physicalReviewComplete;

        public Input(boolean aircraftConnected, boolean cendenceConnected, boolean ocuSyncFresh,
                boolean flightStateFresh, boolean djiHomeAvailable, boolean rthActive,
                boolean gpsFresh, boolean rtkFresh, boolean rtkFix, boolean rtkRequired,
                boolean safetyConfigurationAvailable, int cendenceBatteryPercent,
                int aircraftBatteryOnePercent, int aircraftBatteryTwoPercent,
                boolean physicalReviewComplete) {
            this.aircraftConnected = aircraftConnected;
            this.cendenceConnected = cendenceConnected;
            this.ocuSyncFresh = ocuSyncFresh;
            this.flightStateFresh = flightStateFresh;
            this.djiHomeAvailable = djiHomeAvailable;
            this.rthActive = rthActive;
            this.gpsFresh = gpsFresh;
            this.rtkFresh = rtkFresh;
            this.rtkFix = rtkFix;
            this.rtkRequired = rtkRequired;
            this.safetyConfigurationAvailable = safetyConfigurationAvailable;
            this.cendenceBatteryPercent = cendenceBatteryPercent;
            this.aircraftBatteryOnePercent = aircraftBatteryOnePercent;
            this.aircraftBatteryTwoPercent = aircraftBatteryTwoPercent;
            this.physicalReviewComplete = physicalReviewComplete;
        }

        @NonNull public Input withHomeAvailable(boolean value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    value, rthActive, gpsFresh, rtkFresh, rtkFix, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, aircraftBatteryOnePercent,
                    aircraftBatteryTwoPercent, physicalReviewComplete);
        }

        @NonNull public Input withAircraftBatteryPercent(int value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    djiHomeAvailable, rthActive, gpsFresh, rtkFresh, rtkFix, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, value, value,
                    physicalReviewComplete);
        }

        @NonNull public Input withRtkFix(boolean value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    djiHomeAvailable, rthActive, gpsFresh, rtkFresh, value, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, aircraftBatteryOnePercent,
                    aircraftBatteryTwoPercent, physicalReviewComplete);
        }

        @NonNull public Input withRtkRequired(boolean value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    djiHomeAvailable, rthActive, gpsFresh, rtkFresh, rtkFix, value,
                    safetyConfigurationAvailable, cendenceBatteryPercent, aircraftBatteryOnePercent,
                    aircraftBatteryTwoPercent, physicalReviewComplete);
        }

        @NonNull public Input withFlightStateFresh(boolean value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, value,
                    djiHomeAvailable, rthActive, gpsFresh, rtkFresh, rtkFix, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, aircraftBatteryOnePercent,
                    aircraftBatteryTwoPercent, physicalReviewComplete);
        }

        @NonNull private Input copy(boolean aircraftConnected, boolean cendenceConnected,
                boolean ocuSyncFresh, boolean flightStateFresh, boolean djiHomeAvailable,
                boolean rthActive, boolean gpsFresh, boolean rtkFresh, boolean rtkFix,
                boolean rtkRequired, boolean safetyConfigurationAvailable, int cendenceBatteryPercent,
                int aircraftBatteryOnePercent, int aircraftBatteryTwoPercent,
                boolean physicalReviewComplete) {
            return new Input(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    djiHomeAvailable, rthActive, gpsFresh, rtkFresh, rtkFix, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, aircraftBatteryOnePercent,
                    aircraftBatteryTwoPercent, physicalReviewComplete);
        }
    }

    private FlightReadinessModel() { }

    @NonNull public static Decision evaluate(@NonNull Input input) {
        List<Row> rows = new ArrayList<>();
        List<Reason> blocked = new ArrayList<>();
        List<Reason> attention = new ArrayList<>();
        addConnectionRows(input, rows, blocked);
        addNavigationRows(input, rows, blocked, attention);
        addPowerRows(input, rows, blocked, attention);
        rows.add(row(Group.SAFETY_CONFIGURATION, "Safety configuration", "READ-ONLY",
                input.safetyConfigurationAvailable ? RowState.HEALTHY : RowState.UNAVAILABLE,
                input.safetyConfigurationAvailable ? null : Reason.SAFETY_CONFIGURATION_UNAVAILABLE));

        if (!blocked.isEmpty()) return new Decision(State.BLOCKED, blocked.get(0), rows);
        if (!input.physicalReviewComplete) {
            rows.add(row(Group.SAFETY_CONFIGURATION, "Physical review", "INCOMPLETE",
                    RowState.BLOCKED, Reason.PHYSICAL_REVIEW_INCOMPLETE));
            return new Decision(State.REVIEW_REQUIRED, Reason.PHYSICAL_REVIEW_INCOMPLETE, rows);
        }
        if (!attention.isEmpty()) return new Decision(State.ATTENTION, attention.get(0), rows);
        rows.add(row(Group.SAFETY_CONFIGURATION, "Physical review", "COMPLETE",
                RowState.HEALTHY, null));
        return new Decision(State.MISSION_READY, null, rows);
    }

    private static void addConnectionRows(Input input, List<Row> rows, List<Reason> blocked) {
        addRequired(rows, blocked, Group.AIRCRAFT_LINK, "Aircraft", input.aircraftConnected,
                Reason.AIRCRAFT_UNAVAILABLE);
        addRequired(rows, blocked, Group.AIRCRAFT_LINK, "Cendence", input.cendenceConnected,
                Reason.CENDENCE_UNAVAILABLE);
        addRequired(rows, blocked, Group.AIRCRAFT_LINK, "DJI OcuSync", input.ocuSyncFresh,
                Reason.OCU_SYNC_UNAVAILABLE);
    }

    private static void addNavigationRows(Input input, List<Row> rows, List<Reason> blocked,
            List<Reason> attention) {
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "Flight state", input.flightStateFresh,
                Reason.FLIGHT_STATE_STALE);
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "GPS", input.gpsFresh,
                Reason.FLIGHT_STATE_STALE);
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "DJI Home", input.djiHomeAvailable,
                Reason.DJI_HOME_UNAVAILABLE);
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "RTH", !input.rthActive, Reason.RTH_ACTIVE);
        if (input.rtkRequired && (!input.rtkFresh || !input.rtkFix)) {
            rows.add(row(Group.NAVIGATION_HOME, "RTK", "FIX REQUIRED", RowState.BLOCKED,
                    Reason.RTK_FIX_REQUIRED));
            blocked.add(Reason.RTK_FIX_REQUIRED);
        } else if (!input.rtkFresh || !input.rtkFix) {
            rows.add(row(Group.NAVIGATION_HOME, "RTK", "NOT FIX", RowState.ATTENTION,
                    Reason.RTK_NOT_FIXED));
            attention.add(Reason.RTK_NOT_FIXED);
        } else {
            rows.add(row(Group.NAVIGATION_HOME, "RTK", "FIX", RowState.HEALTHY, null));
        }
    }

    private static void addPowerRows(Input input, List<Row> rows, List<Reason> blocked,
            List<Reason> attention) {
        addBattery(rows, blocked, attention, "Cendence battery", input.cendenceBatteryPercent,
                Reason.CENDENCE_BATTERY_LOW, Reason.CENDENCE_BATTERY_CRITICAL);
        addBattery(rows, blocked, attention, "Drone battery B1", input.aircraftBatteryOnePercent,
                Reason.AIRCRAFT_BATTERY_LOW, Reason.AIRCRAFT_BATTERY_CRITICAL);
        addBattery(rows, blocked, attention, "Drone battery B2", input.aircraftBatteryTwoPercent,
                Reason.AIRCRAFT_BATTERY_LOW, Reason.AIRCRAFT_BATTERY_CRITICAL);
    }

    private static void addBattery(List<Row> rows, List<Reason> blocked, List<Reason> attention,
            String label, int percent, Reason warning, Reason critical) {
        if (percent < 0) {
            rows.add(row(Group.POWER, label, "UNAVAILABLE", RowState.BLOCKED, critical));
            blocked.add(critical);
        } else if (percent <= 15) {
            rows.add(row(Group.POWER, label, percent + "%", RowState.BLOCKED, critical));
            blocked.add(critical);
        } else if (percent <= 30) {
            rows.add(row(Group.POWER, label, percent + "%", RowState.ATTENTION, warning));
            attention.add(warning);
        } else {
            rows.add(row(Group.POWER, label, percent + "%", RowState.HEALTHY, null));
        }
    }

    private static void addRequired(List<Row> rows, List<Reason> blocked, Group group,
            String label, boolean available, Reason reason) {
        rows.add(row(group, label, available ? "LIVE" : "UNAVAILABLE",
                available ? RowState.HEALTHY : RowState.BLOCKED, available ? null : reason));
        if (!available) blocked.add(reason);
    }

    private static Row row(Group group, String label, String value, RowState state, Reason reason) {
        return new Row(group, label, value, state, reason);
    }
}
