package com.dji.sdk.sample.missionplanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local planning geometry only; it is never a DJI geofence. */
public final class PlanningBoundary {
    public enum Kind { INCLUSION, EXCLUSION }
    public enum Shape { CIRCLE, POLYGON }

    private final String name;
    private final Kind kind;
    private final Shape shape;
    private final List<Point> points;
    private final double radiusMeters;

    private PlanningBoundary(String name, Kind kind, Shape shape, List<Point> points, double radiusMeters) {
        if (points == null || (shape == Shape.CIRCLE && points.size() != 1)
                || (shape == Shape.POLYGON && points.size() < 3) || radiusMeters < 0d) {
            throw new IllegalArgumentException("Invalid local planning boundary");
        }
        this.name = name == null || name.trim().isEmpty() ? "Boundary" : name.trim();
        this.kind = kind;
        this.shape = shape;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.radiusMeters = radiusMeters;
    }

    public static PlanningBoundary exclusionCircle(String name, double latitude, double longitude, double radiusMeters) {
        return new PlanningBoundary(name, Kind.EXCLUSION, Shape.CIRCLE,
                Collections.singletonList(new Point(latitude, longitude)), radiusMeters);
    }

    public static PlanningBoundary inclusionPolygon(String name, List<Point> points) {
        return new PlanningBoundary(name, Kind.INCLUSION, Shape.POLYGON, points, 0d);
    }

    public static PlanningBoundary exclusionPolygon(String name, List<Point> points) {
        return new PlanningBoundary(name, Kind.EXCLUSION, Shape.POLYGON, points, 0d);
    }

    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public Shape getShape() { return shape; }
    public List<Point> getPoints() { return points; }
    public double getRadiusMeters() { return radiusMeters; }

    public static final class Point {
        private final double latitude;
        private final double longitude;
        public Point(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
    }
}
