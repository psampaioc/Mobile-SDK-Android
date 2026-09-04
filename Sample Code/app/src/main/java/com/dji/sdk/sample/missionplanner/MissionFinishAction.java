package com.dji.sdk.sample.missionplanner;

/** Operator-selected completion behavior. Never inferred from waypoint altitude. */
public enum MissionFinishAction {
    HOVER_AT_FINAL,
    RETURN_AND_LAND_DJI_HOME,
    LAND_AT_FINAL
}
