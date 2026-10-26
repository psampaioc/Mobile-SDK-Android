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

    public enum RowState { HEALTHY, ATTENTION, BLOCKED, WAITING_FOR_SAMPLE, STALE, UNAVAILABLE }

    /** Passive data-evidence state; a non-LIVE value is never a readiness pass. */
    public enum EvidenceState {
        LIVE, WAITING_FOR_SAMPLE, STALE, UNAVAILABLE;

        @NonNull RowState rowState() {
            if (this == LIVE) return RowState.HEALTHY;
            if (this == WAITING_FOR_SAMPLE) return RowState.WAITING_FOR_SAMPLE;
            if (this == STALE) return RowState.STALE;
            return RowState.UNAVAILABLE;
        }

        @NonNull String value() {
            return this == WAITING_FOR_SAMPLE ? "WAITING FOR SAMPLE" : name();
        }
    }

    public enum Reason {
        AIRCRAFT_UNAVAILABLE("Aircraft connection is unavailable."),
        CENDENCE_UNAVAILABLE("Cendence connection is unavailable."),
        OCU_SYNC_UNAVAILABLE("DJI OcuSync link is unavailable or stale."),
        FLIGHT_STATE_STALE("Aircraft flight state is unavailable or stale."),
        DJI_HOME_UNAVAILABLE("DJI Home Point is unavailable."),
        RTH_ACTIVE("RTH is active."),
        AIRCRAFT_BATTERY_CRITICAL("Drone battery is critical."),
        AIRCRAFT_BATTERY_LOW("Drone battery is low."),
        AIRCRAFT_BATTERY_STATUS_UNAVAILABLE("Drone battery status is stale or unavailable."),
        CENDENCE_BATTERY_CRITICAL("Cendence battery is critical."),
        CENDENCE_BATTERY_LOW("Cendence battery is low."),
        CENDENCE_BATTERY_STATUS_UNAVAILABLE("Cendence battery status is stale or unavailable."),
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
        public final EvidenceState ocuSyncComponentEvidence;
        public final EvidenceState flightStateEvidence;
        public final EvidenceState djiHomeEvidence;
        public final EvidenceState gpsEvidence;
        public final EvidenceState rtkEvidence;
        public final EvidenceState cendenceBatteryEvidence;
        public final EvidenceState aircraftBatteryOneEvidence;
        public final EvidenceState aircraftBatteryTwoEvidence;

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
            ocuSyncComponentEvidence = available(ocuSyncFresh);
            flightStateEvidence = available(flightStateFresh);
            djiHomeEvidence = available(djiHomeAvailable);
            gpsEvidence = available(gpsFresh);
            rtkEvidence = available(rtkFresh);
            cendenceBatteryEvidence = batteryEvidence(cendenceBatteryPercent);
            aircraftBatteryOneEvidence = batteryEvidence(aircraftBatteryOnePercent);
            aircraftBatteryTwoEvidence = batteryEvidence(aircraftBatteryTwoPercent);
        }

        private Input(Input source, EvidenceState ocuSyncComponentEvidence,
                EvidenceState flightStateEvidence, EvidenceState djiHomeEvidence, EvidenceState gpsEvidence,
                EvidenceState rtkEvidence, EvidenceState cendenceBatteryEvidence,
                EvidenceState aircraftBatteryOneEvidence, EvidenceState aircraftBatteryTwoEvidence) {
            aircraftConnected = source.aircraftConnected;
            cendenceConnected = source.cendenceConnected;
            ocuSyncFresh = source.ocuSyncFresh;
            flightStateFresh = source.flightStateFresh;
            djiHomeAvailable = source.djiHomeAvailable;
            rthActive = source.rthActive;
            gpsFresh = source.gpsFresh;
            rtkFresh = source.rtkFresh;
            rtkFix = source.rtkFix;
            rtkRequired = source.rtkRequired;
            safetyConfigurationAvailable = source.safetyConfigurationAvailable;
            cendenceBatteryPercent = source.cendenceBatteryPercent;
            aircraftBatteryOnePercent = source.aircraftBatteryOnePercent;
            aircraftBatteryTwoPercent = source.aircraftBatteryTwoPercent;
            physicalReviewComplete = source.physicalReviewComplete;
            this.ocuSyncComponentEvidence = ocuSyncComponentEvidence;
            this.flightStateEvidence = flightStateEvidence;
            this.djiHomeEvidence = djiHomeEvidence;
            this.gpsEvidence = gpsEvidence;
            this.rtkEvidence = rtkEvidence;
            this.cendenceBatteryEvidence = cendenceBatteryEvidence;
            this.aircraftBatteryOneEvidence = aircraftBatteryOneEvidence;
            this.aircraftBatteryTwoEvidence = aircraftBatteryTwoEvidence;
        }

        @NonNull public Input withEvidence(EvidenceState ocuSyncComponentEvidence,
                EvidenceState flightStateEvidence, EvidenceState gpsEvidence,
                EvidenceState rtkEvidence, EvidenceState cendenceBatteryEvidence,
                EvidenceState aircraftBatteryOneEvidence, EvidenceState aircraftBatteryTwoEvidence) {
            return new Input(this, ocuSyncComponentEvidence, flightStateEvidence, djiHomeEvidence,
                    gpsEvidence, rtkEvidence, cendenceBatteryEvidence, aircraftBatteryOneEvidence,
                    aircraftBatteryTwoEvidence);
        }

        @NonNull public Input withFlightStateEvidence(EvidenceState value) {
            return withEvidence(ocuSyncComponentEvidence, value, gpsEvidence, rtkEvidence,
                    cendenceBatteryEvidence, aircraftBatteryOneEvidence, aircraftBatteryTwoEvidence);
        }

        @NonNull public Input withDjiHomeEvidence(EvidenceState value) {
            return new Input(this, ocuSyncComponentEvidence, flightStateEvidence, value, gpsEvidence,
                    rtkEvidence, cendenceBatteryEvidence, aircraftBatteryOneEvidence,
                    aircraftBatteryTwoEvidence);
        }

        @NonNull public Input withAircraftBatteryEvidence(int index, EvidenceState value) {
            if (index == 0) return withEvidence(ocuSyncComponentEvidence, flightStateEvidence,
                    gpsEvidence, rtkEvidence, cendenceBatteryEvidence, value, aircraftBatteryTwoEvidence);
            if (index == 1) return withEvidence(ocuSyncComponentEvidence, flightStateEvidence,
                    gpsEvidence, rtkEvidence, cendenceBatteryEvidence, aircraftBatteryOneEvidence, value);
            throw new IllegalArgumentException("Unsupported battery index " + index);
        }

        @NonNull private static EvidenceState available(boolean value) {
            return value ? EvidenceState.LIVE : EvidenceState.UNAVAILABLE;
        }

        @NonNull private static EvidenceState batteryEvidence(int percent) {
            return percent < 0 ? EvidenceState.UNAVAILABLE : EvidenceState.LIVE;
        }

        @NonNull public Input withHomeAvailable(boolean value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    value, rthActive, gpsFresh, rtkFresh, rtkFix, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, aircraftBatteryOnePercent,
                    aircraftBatteryTwoPercent, physicalReviewComplete)
                    .withDjiHomeEvidence(available(value));
        }

        @NonNull public Input withAircraftBatteryPercent(int value) {
            return copy(aircraftConnected, cendenceConnected, ocuSyncFresh, flightStateFresh,
                    djiHomeAvailable, rthActive, gpsFresh, rtkFresh, rtkFix, rtkRequired,
                    safetyConfigurationAvailable, cendenceBatteryPercent, value, value,
                    physicalReviewComplete)
                    .withAircraftBatteryEvidence(0, batteryEvidence(value))
                    .withAircraftBatteryEvidence(1, batteryEvidence(value));
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
                    aircraftBatteryTwoPercent, physicalReviewComplete)
                    .withFlightStateEvidence(available(value))
                    .withEvidence(ocuSyncComponentEvidence, available(value), available(value),
                            rtkEvidence, cendenceBatteryEvidence, aircraftBatteryOneEvidence,
                            aircraftBatteryTwoEvidence);
        }

        @NonNull private Input copy(boolean aircraftConnected, boolean cendenceConnected,
                boolean ocuSyncFresh, boolean flightStateFresh, boolean djiHomeAvailable,
                boolean rthActive, boolean gpsFresh, boolean rtkFresh, boolean rtkFix,
                boolean rtkRequired, boolean safetyConfigurationAvailable, int cendenceBatteryPercent,
                int aircraftBatteryOnePercent, int aircraftBatteryTwoPercent,
                boolean physicalReviewComplete) {
            Input updated = new Input(aircraftConnected, cendenceConnected, ocuSyncFresh,
                    flightStateFresh, djiHomeAvailable, rthActive, gpsFresh, rtkFresh, rtkFix,
                    rtkRequired, safetyConfigurationAvailable, cendenceBatteryPercent,
                    aircraftBatteryOnePercent, aircraftBatteryTwoPercent, physicalReviewComplete);
            return new Input(updated, ocuSyncComponentEvidence, flightStateEvidence,
                    djiHomeEvidence, gpsEvidence, rtkEvidence, cendenceBatteryEvidence,
                    aircraftBatteryOneEvidence, aircraftBatteryTwoEvidence);
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
        addRequired(rows, blocked, Group.AIRCRAFT_LINK, "OcuSync component", input.ocuSyncComponentEvidence,
                Reason.OCU_SYNC_UNAVAILABLE);
    }

    private static void addNavigationRows(Input input, List<Row> rows, List<Reason> blocked,
            List<Reason> attention) {
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "Flight state", input.flightStateEvidence,
                Reason.FLIGHT_STATE_STALE);
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "GPS", input.gpsEvidence,
                Reason.FLIGHT_STATE_STALE);
        addRequired(rows, blocked, Group.NAVIGATION_HOME, "DJI Home", input.djiHomeEvidence,
                Reason.DJI_HOME_UNAVAILABLE);
        if (input.flightStateEvidence != EvidenceState.LIVE) {
            addRequired(rows, blocked, Group.NAVIGATION_HOME, "RTH", input.flightStateEvidence,
                    Reason.RTH_ACTIVE);
        } else {
            addRequired(rows, blocked, Group.NAVIGATION_HOME, "RTH", !input.rthActive, Reason.RTH_ACTIVE);
        }
        if (input.rtkEvidence != EvidenceState.LIVE) {
            RowState state = input.rtkEvidence.rowState();
            rows.add(row(Group.NAVIGATION_HOME, "RTK", input.rtkEvidence.value(), state,
                    Reason.RTK_NOT_FIXED));
            attention.add(Reason.RTK_NOT_FIXED);
        } else if (!input.rtkFix) {
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
                input.cendenceBatteryEvidence,
                Reason.CENDENCE_BATTERY_LOW, Reason.CENDENCE_BATTERY_CRITICAL,
                Reason.CENDENCE_BATTERY_STATUS_UNAVAILABLE);
        addBattery(rows, blocked, attention, "Drone battery B1", input.aircraftBatteryOnePercent,
                input.aircraftBatteryOneEvidence,
                Reason.AIRCRAFT_BATTERY_LOW, Reason.AIRCRAFT_BATTERY_CRITICAL,
                Reason.AIRCRAFT_BATTERY_STATUS_UNAVAILABLE);
        addBattery(rows, blocked, attention, "Drone battery B2", input.aircraftBatteryTwoPercent,
                input.aircraftBatteryTwoEvidence,
                Reason.AIRCRAFT_BATTERY_LOW, Reason.AIRCRAFT_BATTERY_CRITICAL,
                Reason.AIRCRAFT_BATTERY_STATUS_UNAVAILABLE);
    }

    private static void addBattery(List<Row> rows, List<Reason> blocked, List<Reason> attention,
            String label, int percent, EvidenceState evidence, Reason warning, Reason critical,
            Reason unavailable) {
        if (evidence != EvidenceState.LIVE) {
            rows.add(row(Group.POWER, label, evidence.value(), evidence.rowState(), unavailable));
            blocked.add(unavailable);
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

    private static void addRequired(List<Row> rows, List<Reason> blocked, Group group,
            String label, EvidenceState evidence, Reason reason) {
        rows.add(row(group, label, evidence.value(), evidence.rowState(),
                evidence == EvidenceState.LIVE ? null : reason));
        if (evidence != EvidenceState.LIVE) blocked.add(reason);
    }

    private static Row row(Group group, String label, String value, RowState state, Reason reason) {
        return new Row(group, label, value, state, reason);
    }
}
