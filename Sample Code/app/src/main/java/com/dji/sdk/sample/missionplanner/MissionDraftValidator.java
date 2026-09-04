package com.dji.sdk.sample.missionplanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure validation for local drafts. Connected-product readiness is evaluated separately. */
public final class MissionDraftValidator {
    public static final float MIN_ALTITUDE_METERS = 0f;
    public static final float MAX_BASE_SPEED_METERS_PER_SECOND = 15f;
    public static final float MIN_BASE_SPEED_METERS_PER_SECOND = 0.1f;

    private MissionDraftValidator() { }

    public static List<String> validate(MissionDraft draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null) {
            errors.add("Mission draft is missing.");
            return errors;
        }
        if (draft.getWaypoints().size() < 2) errors.add("At least two waypoints are required.");
        if (!finite(draft.getDefaultAltitudeMeters()) || draft.getDefaultAltitudeMeters() < MIN_ALTITUDE_METERS) {
            errors.add("Default altitude must be zero or greater.");
        }
        if (!finite(draft.getBaseSpeedMetersPerSecond())
                || draft.getBaseSpeedMetersPerSecond() < MIN_BASE_SPEED_METERS_PER_SECOND
                || draft.getBaseSpeedMetersPerSecond() > MAX_BASE_SPEED_METERS_PER_SECOND) {
            errors.add("Base speed must be between 0.1 and 15 m/s.");
        }
        if ((draft.getPlanningReferenceLatitude() == null) != (draft.getPlanningReferenceLongitude() == null)) {
            errors.add("Planning reference must include both latitude and longitude.");
        } else if (draft.getPlanningReferenceLatitude() != null && !validCoordinate(
                draft.getPlanningReferenceLatitude(), draft.getPlanningReferenceLongitude())) {
            errors.add("Planning reference coordinates are invalid.");
        }
        for (int i = 0; i < draft.getWaypoints().size(); i++) {
            MissionWaypoint waypoint = draft.getWaypoints().get(i);
            if (waypoint == null || !validCoordinate(waypoint == null ? 0 : waypoint.getLatitude(),
                    waypoint == null ? 0 : waypoint.getLongitude())) {
                errors.add("Waypoint " + (i + 1) + " coordinates are invalid.");
                continue;
            }
            Float override = waypoint.getAltitudeOverrideMeters();
            if (override != null && (!finite(override) || override < MIN_ALTITUDE_METERS)) {
                errors.add("Waypoint " + (i + 1) + " altitude must be zero or greater.");
            }
        }
        return Collections.unmodifiableList(errors);
    }

    public static float resolvedAltitude(MissionDraft draft, MissionWaypoint waypoint) {
        return waypoint.getAltitudeOverrideMeters() == null ? draft.getDefaultAltitudeMeters()
                : waypoint.getAltitudeOverrideMeters();
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90d && latitude <= 90d && longitude >= -180d && longitude <= 180d;
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
