package com.dji.sdk.sample.missionplanner;

import java.util.Collections;
import java.util.List;

/** Segment-aware, advisory-only evaluator for local planning boundaries. */
public final class BoundaryEvaluator {
    private BoundaryEvaluator() { }
    public static Result evaluate(MissionDraft draft, List<PlanningBoundary> boundaries) {
        if (draft == null || boundaries == null || boundaries.isEmpty()) return new Result(0);
        int warnings = 0;
        for (PlanningBoundary boundary : boundaries) {
            boolean violation = false;
            List<MissionWaypoint> route = draft.getWaypoints();
            for (int i = 0; i < route.size(); i++) {
                boolean inside = contains(boundary, route.get(i).getLatitude(), route.get(i).getLongitude());
                if ((boundary.getKind() == PlanningBoundary.Kind.EXCLUSION && inside)
                        || (boundary.getKind() == PlanningBoundary.Kind.INCLUSION && !inside)) { violation = true; break; }
                if (i > 0 && crosses(boundary, route.get(i - 1), route.get(i))) { violation = true; break; }
            }
            if (violation) warnings++;
        }
        return new Result(warnings);
    }
    private static boolean contains(PlanningBoundary b, double lat, double lon) {
        if (b.getShape() == PlanningBoundary.Shape.CIRCLE) return distanceMeters(b.getPoints().get(0), lat, lon) <= b.getRadiusMeters();
        boolean inside = false; List<PlanningBoundary.Point> p = b.getPoints();
        for (int i = 0, j = p.size() - 1; i < p.size(); j = i++) {
            if ((p.get(i).getLongitude() > lon) != (p.get(j).getLongitude() > lon)
                    && lat < (p.get(j).getLatitude() - p.get(i).getLatitude()) * (lon - p.get(i).getLongitude())
                    / (p.get(j).getLongitude() - p.get(i).getLongitude()) + p.get(i).getLatitude()) inside = !inside;
        }
        return inside;
    }
    private static boolean crosses(PlanningBoundary b, MissionWaypoint a, MissionWaypoint z) {
        boolean start = contains(b, a.getLatitude(), a.getLongitude());
        boolean end = contains(b, z.getLatitude(), z.getLongitude());
        if (b.getShape() == PlanningBoundary.Shape.CIRCLE) {
            PlanningBoundary.Point center = b.getPoints().get(0);
            return start != end || segmentDistanceMeters(center, a, z) <= b.getRadiusMeters();
        }
        if (start != end) return true;
        List<PlanningBoundary.Point> points = b.getPoints();
        for (int i = 0; i < points.size(); i++) {
            PlanningBoundary.Point first = points.get(i);
            PlanningBoundary.Point second = points.get((i + 1) % points.size());
            if (segmentsIntersect(a.getLatitude(), a.getLongitude(), z.getLatitude(), z.getLongitude(),
                    first.getLatitude(), first.getLongitude(), second.getLatitude(), second.getLongitude())) return true;
        }
        return false;
    }
    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        double ab1 = cross(ax, ay, bx, by, cx, cy), ab2 = cross(ax, ay, bx, by, dx, dy);
        double cd1 = cross(cx, cy, dx, dy, ax, ay), cd2 = cross(cx, cy, dx, dy, bx, by);
        return ab1 * ab2 <= 0d && cd1 * cd2 <= 0d;
    }
    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }
    private static double segmentDistanceMeters(PlanningBoundary.Point center, MissionWaypoint a, MissionWaypoint z) {
        double scaleX = 111320d * Math.cos(Math.toRadians(center.getLatitude()));
        double scaleY = 110540d;
        double ax = (a.getLongitude() - center.getLongitude()) * scaleX, ay = (a.getLatitude() - center.getLatitude()) * scaleY;
        double zx = (z.getLongitude() - center.getLongitude()) * scaleX, zy = (z.getLatitude() - center.getLatitude()) * scaleY;
        double lengthSquared = zx * zx + zy * zy;
        double t = lengthSquared == 0d ? 0d : Math.max(0d, Math.min(1d, -(ax * zx + ay * zy) / lengthSquared));
        double x = ax + t * zx, y = ay + t * zy;
        return Math.sqrt(x * x + y * y);
    }
    private static double distanceMeters(PlanningBoundary.Point p, double lat, double lon) {
        double dx = (lon - p.getLongitude()) * 111320d * Math.cos(Math.toRadians((lat + p.getLatitude()) / 2d));
        double dy = (lat - p.getLatitude()) * 110540d; return Math.sqrt(dx * dx + dy * dy);
    }
    public static final class Result { private final int warnings; private Result(int warnings) { this.warnings = warnings; }
        public int getWarningCount() { return warnings; } }
}
