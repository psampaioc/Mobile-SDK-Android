package com.dji.sdk.sample.missionplanner;

import com.dji.sdk.sample.operate.FlightReadinessModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Connected-state readiness gate. It reports conditions and never calls DJI APIs. */
public final class MissionPreflight {
    private MissionPreflight() { }

    public static List<String> validate(MissionDraft draft, boolean flightStateAvailable,
                                        boolean homeAvailable, boolean rthActive) {
        List<String> errors = new ArrayList<>(MissionDraftValidator.validate(draft));
        if (!flightStateAvailable) errors.add("Aircraft flight state is unavailable.");
        if (!homeAvailable) errors.add("DJI Home Point is unavailable.");
        if (rthActive) errors.add("RTH is active.");
        return Collections.unmodifiableList(errors);
    }

    /** Start-only extension. Load and Upload deliberately retain {@link #validate} semantics. */
    public static List<String> validateStart(MissionDraft draft, boolean flightStateAvailable,
            boolean homeAvailable, boolean rthActive, FlightReadinessModel.Decision readiness) {
        List<String> errors = new ArrayList<>(validate(draft, flightStateAvailable, homeAvailable, rthActive));
        if (readiness == null || !readiness.canStartMission()) {
            errors.add(readiness == null ? "Flight readiness is unavailable."
                    : readiness.getExplanation());
        }
        return Collections.unmodifiableList(errors);
    }
}
