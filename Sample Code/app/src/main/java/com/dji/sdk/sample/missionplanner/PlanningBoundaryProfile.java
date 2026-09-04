package com.dji.sdk.sample.missionplanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Named, app-local boundary profile. It is never serialized into a DJI mission. */
public final class PlanningBoundaryProfile {
    private static final int VERSION = 1;
    private final String name;
    private final List<PlanningBoundary> boundaries;
    public PlanningBoundaryProfile(String name, List<PlanningBoundary> boundaries) {
        this.name = name == null || name.trim().isEmpty() ? "Local profile" : name.trim();
        this.boundaries = Collections.unmodifiableList(new ArrayList<>(boundaries == null
                ? Collections.<PlanningBoundary>emptyList() : boundaries));
    }
    public String getName() { return name; }
    public List<PlanningBoundary> getBoundaries() { return boundaries; }
    public String encode() throws JSONException {
        JSONObject root = new JSONObject().put("v", VERSION).put("name", name);
        JSONArray values = new JSONArray();
        for (PlanningBoundary boundary : boundaries) {
            JSONObject value = new JSONObject().put("name", boundary.getName())
                    .put("kind", boundary.getKind().name()).put("shape", boundary.getShape().name())
                    .put("radius_m", boundary.getRadiusMeters());
            JSONArray points = new JSONArray();
            for (PlanningBoundary.Point point : boundary.getPoints()) points.put(new JSONObject()
                    .put("lat", point.getLatitude()).put("lon", point.getLongitude()));
            values.put(value.put("points", points));
        }
        return root.put("boundaries", values).toString();
    }
    public static PlanningBoundaryProfile decode(String source) throws JSONException {
        JSONObject root = new JSONObject(source);
        if (root.optInt("v", -1) != VERSION) throw new JSONException("Unsupported boundary profile");
        List<PlanningBoundary> boundaries = new ArrayList<>();
        JSONArray values = root.getJSONArray("boundaries");
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i); JSONArray points = value.getJSONArray("points");
            List<PlanningBoundary.Point> parsed = new ArrayList<>();
            for (int j = 0; j < points.length(); j++) { JSONObject p = points.getJSONObject(j);
                parsed.add(new PlanningBoundary.Point(p.getDouble("lat"), p.getDouble("lon"))); }
            PlanningBoundary.Kind kind = PlanningBoundary.Kind.valueOf(value.getString("kind"));
            PlanningBoundary.Shape shape = PlanningBoundary.Shape.valueOf(value.getString("shape"));
            if (shape == PlanningBoundary.Shape.CIRCLE) boundaries.add(PlanningBoundary.circle(value.getString("name"), kind,
                    parsed.get(0).getLatitude(), parsed.get(0).getLongitude(), value.getDouble("radius_m")));
            else boundaries.add(PlanningBoundary.polygon(value.getString("name"), kind, parsed));
        }
        return new PlanningBoundaryProfile(root.getString("name"), boundaries);
    }
}
