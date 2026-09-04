package com.dji.sdk.sample.missionplanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Versioned, app-owned interchange format for local mission drafts. */
public final class MissionDraftJson {
    private static final String KEY_VERSION = "format_version";
    private static final String KEY_NAME = "name";
    private static final String KEY_DEFAULT_ALTITUDE = "default_altitude_m";
    private static final String KEY_BASE_SPEED = "base_speed_m_s";
    private static final String KEY_FINISH_ACTION = "finish_action";
    private static final String KEY_PLANNING_REFERENCE = "planning_reference";
    private static final String KEY_LATITUDE = "latitude_deg";
    private static final String KEY_LONGITUDE = "longitude_deg";
    private static final String KEY_WAYPOINTS = "waypoints";
    private static final String KEY_ALTITUDE_OVERRIDE = "altitude_override_m";

    private MissionDraftJson() { }

    public static String encode(MissionDraft draft) throws JSONException {
        JSONObject root = new JSONObject();
        root.put(KEY_VERSION, MissionDraft.FORMAT_VERSION);
        root.put(KEY_NAME, draft.getName());
        root.put(KEY_DEFAULT_ALTITUDE, draft.getDefaultAltitudeMeters());
        root.put(KEY_BASE_SPEED, draft.getBaseSpeedMetersPerSecond());
        root.put(KEY_FINISH_ACTION, draft.getFinishAction().name());
        if (draft.getPlanningReferenceLatitude() != null) {
            JSONObject reference = new JSONObject();
            reference.put(KEY_LATITUDE, draft.getPlanningReferenceLatitude());
            reference.put(KEY_LONGITUDE, draft.getPlanningReferenceLongitude());
            root.put(KEY_PLANNING_REFERENCE, reference);
        }
        JSONArray waypoints = new JSONArray();
        for (MissionWaypoint waypoint : draft.getWaypoints()) {
            JSONObject value = new JSONObject();
            value.put(KEY_LATITUDE, waypoint.getLatitude());
            value.put(KEY_LONGITUDE, waypoint.getLongitude());
            if (waypoint.getAltitudeOverrideMeters() != null) {
                value.put(KEY_ALTITUDE_OVERRIDE, waypoint.getAltitudeOverrideMeters());
            }
            waypoints.put(value);
        }
        root.put(KEY_WAYPOINTS, waypoints);
        return root.toString();
    }

    public static MissionDraft decode(String encoded) throws JSONException {
        JSONObject root = new JSONObject(encoded);
        if (root.optInt(KEY_VERSION, -1) != MissionDraft.FORMAT_VERSION) {
            throw new JSONException("Unsupported mission draft format");
        }
        MissionFinishAction finishAction;
        try {
            finishAction = MissionFinishAction.valueOf(root.getString(KEY_FINISH_ACTION));
        } catch (IllegalArgumentException exception) {
            throw new JSONException("Unsupported finish action");
        }
        Double referenceLatitude = null;
        Double referenceLongitude = null;
        JSONObject reference = root.optJSONObject(KEY_PLANNING_REFERENCE);
        if (reference != null) {
            referenceLatitude = reference.getDouble(KEY_LATITUDE);
            referenceLongitude = reference.getDouble(KEY_LONGITUDE);
        }
        JSONArray values = root.getJSONArray(KEY_WAYPOINTS);
        List<MissionWaypoint> waypoints = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            Float altitudeOverride = value.has(KEY_ALTITUDE_OVERRIDE)
                    ? (float) value.getDouble(KEY_ALTITUDE_OVERRIDE) : null;
            waypoints.add(new MissionWaypoint(value.getDouble(KEY_LATITUDE),
                    value.getDouble(KEY_LONGITUDE), altitudeOverride));
        }
        MissionDraft draft = new MissionDraft(root.optString(KEY_NAME, "Untitled mission"),
                (float) root.getDouble(KEY_DEFAULT_ALTITUDE),
                (float) root.getDouble(KEY_BASE_SPEED), finishAction, waypoints,
                referenceLatitude, referenceLongitude);
        if (!MissionDraftValidator.validate(draft).isEmpty()) {
            throw new JSONException("Mission draft contains invalid values");
        }
        return draft;
    }
}
