package com.dji.sdk.sample.missionplanner;

/** App-owned heading choices supported by the legacy waypoint mission. */
public enum MissionHeadingMode {
    AUTO("AUTO"),
    INITIAL_DIRECTION("INITIAL DIRECTION"),
    REMOTE_CONTROLLER("REMOTE CONTROLLER");

    private final String label;
    MissionHeadingMode(String label) { this.label = label; }
    public String getLabel() { return label; }
}
