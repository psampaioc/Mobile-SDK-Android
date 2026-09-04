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

    private static final double METERS_PER_LATITUDE_DEGREE = 110540d;
    private static final double METERS_PER_LONGITUDE_DEGREE = 111320d;
    private static final double MINIMUM_POLYGON_AREA_SQUARE_METERS = 0.01d;
    private static final double VERTEX_TOLERANCE_METERS = 0.01d;

    private PlanningBoundary(String name, Kind kind, Shape shape, List<Point> points, double radiusMeters) {
        if (kind == null || shape == null || points == null || (shape == Shape.CIRCLE && points.size() != 1)
                || (shape == Shape.POLYGON && points.size() < 3)
                || (shape == Shape.CIRCLE && (!Double.isFinite(radiusMeters) || radiusMeters <= 0d))) {
            throw new IllegalArgumentException("Invalid local planning boundary");
        }
        for (Point point : points) if (point == null || !point.isValid()) {
            throw new IllegalArgumentException("Invalid local planning boundary coordinate");
        }
        if (shape == Shape.POLYGON) validatePolygon(points);
        this.name = name == null || name.trim().isEmpty() ? "Boundary" : name.trim();
        this.kind = kind;
        this.shape = shape;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.radiusMeters = radiusMeters;
    }

    public static PlanningBoundary exclusionCircle(String name, double latitude, double longitude, double radiusMeters) {
        return circle(name, Kind.EXCLUSION, latitude, longitude, radiusMeters);
    }
    public static PlanningBoundary circle(String name, Kind kind, double latitude, double longitude, double radiusMeters) {
        return new PlanningBoundary(name, kind, Shape.CIRCLE, Collections.singletonList(new Point(latitude, longitude)), radiusMeters);
    }

    public static PlanningBoundary inclusionPolygon(String name, List<Point> points) {
        return new PlanningBoundary(name, Kind.INCLUSION, Shape.POLYGON, points, 0d);
    }

    public static PlanningBoundary exclusionPolygon(String name, List<Point> points) {
        return polygon(name, Kind.EXCLUSION, points);
    }
    public static PlanningBoundary polygon(String name, Kind kind, List<Point> points) { return new PlanningBoundary(name, kind, Shape.POLYGON, points, 0d); }

    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public Shape getShape() { return shape; }
    public List<Point> getPoints() { return points; }
    public double getRadiusMeters() { return radiusMeters; }

    private static void validatePolygon(List<Point> points) {
        if (hasDuplicateVertices(points)) throw new IllegalArgumentException("Polygon vertices must be distinct");
        List<LocalPoint> local = localPoints(points);
        if (Math.abs(signedAreaSquareMeters(local)) < MINIMUM_POLYGON_AREA_SQUARE_METERS) {
            throw new IllegalArgumentException("Polygon area must be non-zero");
        }
        if (hasSelfIntersection(local)) throw new IllegalArgumentException("Polygon edges must not cross");
    }

    private static boolean hasDuplicateVertices(List<Point> points) {
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                if (distanceMeters(points.get(first), points.get(second)) < VERTEX_TOLERANCE_METERS) return true;
            }
        }
        return false;
    }

    private static List<LocalPoint> localPoints(List<Point> points) {
        double latitude = 0d;
        double longitude = 0d;
        for (Point point : points) { latitude += point.latitude; longitude += point.longitude; }
        latitude /= points.size();
        longitude /= points.size();
        double longitudeScale = METERS_PER_LONGITUDE_DEGREE * Math.cos(Math.toRadians(latitude));
        List<LocalPoint> result = new ArrayList<>();
        for (Point point : points) result.add(new LocalPoint((point.longitude - longitude) * longitudeScale,
                (point.latitude - latitude) * METERS_PER_LATITUDE_DEGREE));
        return result;
    }

    private static double signedAreaSquareMeters(List<LocalPoint> points) {
        double twiceArea = 0d;
        for (int index = 0; index < points.size(); index++) {
            LocalPoint first = points.get(index);
            LocalPoint second = points.get((index + 1) % points.size());
            twiceArea += first.x * second.y - second.x * first.y;
        }
        return twiceArea / 2d;
    }

    private static boolean hasSelfIntersection(List<LocalPoint> points) {
        int count = points.size();
        for (int first = 0; first < count; first++) {
            int firstNext = (first + 1) % count;
            for (int second = first + 1; second < count; second++) {
                int secondNext = (second + 1) % count;
                if (first == second || firstNext == second || secondNext == first) continue;
                if (segmentsIntersect(points.get(first), points.get(firstNext), points.get(second), points.get(secondNext))) return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(LocalPoint firstStart, LocalPoint firstEnd,
            LocalPoint secondStart, LocalPoint secondEnd) {
        double firstA = cross(firstStart, firstEnd, secondStart);
        double firstB = cross(firstStart, firstEnd, secondEnd);
        double secondA = cross(secondStart, secondEnd, firstStart);
        double secondB = cross(secondStart, secondEnd, firstEnd);
        if (oppositeSides(firstA, firstB) && oppositeSides(secondA, secondB)) return true;
        return (isZero(firstA) && onSegment(firstStart, firstEnd, secondStart))
                || (isZero(firstB) && onSegment(firstStart, firstEnd, secondEnd))
                || (isZero(secondA) && onSegment(secondStart, secondEnd, firstStart))
                || (isZero(secondB) && onSegment(secondStart, secondEnd, firstEnd));
    }

    private static boolean oppositeSides(double first, double second) { return first * second < 0d; }
    private static boolean isZero(double value) { return Math.abs(value) < 0.000001d; }
    private static double cross(LocalPoint start, LocalPoint end, LocalPoint point) {
        return (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x);
    }
    private static boolean onSegment(LocalPoint start, LocalPoint end, LocalPoint point) {
        return point.x >= Math.min(start.x, end.x) - 0.000001d && point.x <= Math.max(start.x, end.x) + 0.000001d
                && point.y >= Math.min(start.y, end.y) - 0.000001d && point.y <= Math.max(start.y, end.y) + 0.000001d;
    }
    private static double distanceMeters(Point first, Point second) {
        double latitude = (first.latitude + second.latitude) / 2d;
        double x = (second.longitude - first.longitude) * METERS_PER_LONGITUDE_DEGREE * Math.cos(Math.toRadians(latitude));
        double y = (second.latitude - first.latitude) * METERS_PER_LATITUDE_DEGREE;
        return Math.sqrt(x * x + y * y);
    }

    private static final class LocalPoint {
        private final double x;
        private final double y;
        private LocalPoint(double x, double y) { this.x = x; this.y = y; }
    }

    public static final class Point {
        private final double latitude;
        private final double longitude;
        public Point(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        private boolean isValid() {
            return Double.isFinite(latitude) && Double.isFinite(longitude)
                    && latitude >= -90d && latitude <= 90d && longitude >= -180d && longitude <= 180d;
        }
    }
}
