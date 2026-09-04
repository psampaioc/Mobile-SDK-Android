package com.dji.sdk.sample.missionplanner;

import java.util.List;

import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode;
import dji.common.mission.waypoint.WaypointMissionHeadingMode;

/** Translates a validated app draft to the legacy DJI waypoint mission without sending it. */
public final class WaypointMissionFactory {
    private WaypointMissionFactory() { }

    public static WaypointMission create(MissionDraft draft) {
        List<String> errors = MissionDraftValidator.validate(draft);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0));

        WaypointMission.Builder builder = new WaypointMission.Builder();
        builder.autoFlightSpeed(draft.getBaseSpeedMetersPerSecond());
        // This is an explicit envelope, not an instruction to fly at 15 m/s.
        builder.maxFlightSpeed(15f);
        builder.finishedAction(toDjiFinishAction(draft.getFinishAction()));
        builder.flightPathMode(WaypointMissionFlightPathMode.NORMAL);
        builder.gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY);
        builder.headingMode(WaypointMissionHeadingMode.AUTO);
        builder.repeatTimes(1);
        builder.setExitMissionOnRCSignalLostEnabled(false);
        for (MissionWaypoint waypoint : draft.getWaypoints()) {
            builder.addWaypoint(new Waypoint(waypoint.getLatitude(), waypoint.getLongitude(),
                    MissionDraftValidator.resolvedAltitude(draft, waypoint)));
        }
        return builder.build();
    }

    private static WaypointMissionFinishedAction toDjiFinishAction(MissionFinishAction action) {
        switch (action) {
            case RETURN_AND_LAND_DJI_HOME:
                return WaypointMissionFinishedAction.GO_HOME;
            case LAND_AT_FINAL:
                return WaypointMissionFinishedAction.AUTO_LAND;
            case HOVER_AT_FINAL:
            default:
                return WaypointMissionFinishedAction.NO_ACTION;
        }
    }
}
