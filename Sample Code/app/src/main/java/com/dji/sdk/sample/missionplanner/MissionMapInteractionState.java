package com.dji.sdk.sample.missionplanner;

/** Local Mission-map interaction mode; it never sends a DJI command. */
public final class MissionMapInteractionState {
    public enum Mode { IDLE, ADD_WAYPOINTS, SELECT_DJI_HOME, CAPTURE_BOUNDARY_POINTS }

    private Mode mode = Mode.IDLE;

    public Mode getMode() { return mode; }

    public boolean toggleAddWaypoints() {
        mode = mode == Mode.ADD_WAYPOINTS ? Mode.IDLE : Mode.ADD_WAYPOINTS;
        return mode == Mode.ADD_WAYPOINTS;
    }

    /** Selects one map coordinate for an explicit DJI Home action; it is never a route waypoint. */
    public boolean toggleSelectDjiHome() {
        mode = mode == Mode.SELECT_DJI_HOME ? Mode.IDLE : Mode.SELECT_DJI_HOME;
        return mode == Mode.SELECT_DJI_HOME;
    }

    public void beginBoundaryCapture() { mode = Mode.CAPTURE_BOUNDARY_POINTS; }

    public boolean isBoundaryCapture() { return mode == Mode.CAPTURE_BOUNDARY_POINTS; }

    /** Cancellation always returns to neutral; it never silently resumes waypoint entry. */
    public void cancelBoundaryCapture() { mode = Mode.IDLE; }

    public void clear() { mode = Mode.IDLE; }
}
