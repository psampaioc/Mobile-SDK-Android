package com.dji.sdk.sample.missionplanner;

import java.util.List;

/** Local route geometry estimate. It deliberately has no terrain, battery, or DJI state input. */
public final class RouteStatistics {
    private static final double EARTH_RADIUS_METERS = 6371008.8d;

    private RouteStatistics() { }

    public static Snapshot calculate(MissionDraft draft) {
        if (draft == null || !MissionDraftValidator.validate(draft).isEmpty()) return Snapshot.unavailable();
        List<MissionWaypoint> waypoints = draft.getWaypoints();
        if (waypoints.size() < 2) return Snapshot.unavailable();
        double distance = 0d;
        float minAltitude = Float.POSITIVE_INFINITY;
        float maxAltitude = Float.NEGATIVE_INFINITY;
        float climb = 0f;
        float descent = 0f;
        Float previousAltitude = null;
        for (int index = 0; index < waypoints.size(); index++) {
            MissionWaypoint waypoint = waypoints.get(index);
            float altitude = MissionDraftValidator.resolvedAltitude(draft, waypoint);
            minAltitude = Math.min(minAltitude, altitude);
            maxAltitude = Math.max(maxAltitude, altitude);
            if (previousAltitude != null) {
                float delta = altitude - previousAltitude;
                if (delta >= 0f) climb += delta;
                else descent -= delta;
                distance += haversineMeters(waypoints.get(index - 1), waypoint);
            }
            previousAltitude = altitude;
        }
        return new Snapshot(true, waypoints.size(), distance, minAltitude, maxAltitude, climb, descent,
                distance / draft.getBaseSpeedMetersPerSecond());
    }

    private static double haversineMeters(MissionWaypoint start, MissionWaypoint end) {
        double latitudeDelta = Math.toRadians(end.getLatitude() - start.getLatitude());
        double longitudeDelta = Math.toRadians(end.getLongitude() - start.getLongitude());
        double startLatitude = Math.toRadians(start.getLatitude());
        double endLatitude = Math.toRadians(end.getLatitude());
        double a = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(startLatitude) * Math.cos(endLatitude)
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return EARTH_RADIUS_METERS * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    public static final class Snapshot {
        private final boolean available;
        private final int waypointCount;
        private final double distanceMeters;
        private final float minimumAltitudeMeters;
        private final float maximumAltitudeMeters;
        private final float climbMeters;
        private final float descentMeters;
        private final double estimatedDurationSeconds;

        private Snapshot(boolean available, int waypointCount, double distanceMeters,
                float minimumAltitudeMeters, float maximumAltitudeMeters, float climbMeters,
                float descentMeters, double estimatedDurationSeconds) {
            this.available = available;
            this.waypointCount = waypointCount;
            this.distanceMeters = distanceMeters;
            this.minimumAltitudeMeters = minimumAltitudeMeters;
            this.maximumAltitudeMeters = maximumAltitudeMeters;
            this.climbMeters = climbMeters;
            this.descentMeters = descentMeters;
            this.estimatedDurationSeconds = estimatedDurationSeconds;
        }

        private static Snapshot unavailable() { return new Snapshot(false, 0, 0d, 0f, 0f, 0f, 0f, 0d); }
        public boolean isAvailable() { return available; }
        public int getWaypointCount() { return waypointCount; }
        public double getDistanceMeters() { return distanceMeters; }
        public float getMinimumAltitudeMeters() { return minimumAltitudeMeters; }
        public float getMaximumAltitudeMeters() { return maximumAltitudeMeters; }
        public float getClimbMeters() { return climbMeters; }
        public float getDescentMeters() { return descentMeters; }
        public double getEstimatedDurationSeconds() { return estimatedDurationSeconds; }
    }
}
