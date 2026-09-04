package com.dji.sdk.sample.missionplanner;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Pure editor for ordered waypoint geometry. It never touches DJI or a map SDK. */
public final class MissionRouteEditor {
    private MissionDraft draft;

    public MissionRouteEditor(@NonNull MissionDraft initialDraft) {
        draft = initialDraft;
    }

    @NonNull
    public MissionDraft getDraft() { return draft; }

    public void replace(@NonNull MissionDraft nextDraft) { draft = nextDraft; }

    public void append(@NonNull MissionWaypoint waypoint) {
        List<MissionWaypoint> updated = new ArrayList<>(draft.getWaypoints());
        updated.add(waypoint);
        draft = draft.withWaypoints(updated);
    }

    public void insertAfter(int index, @NonNull MissionWaypoint waypoint) {
        List<MissionWaypoint> updated = new ArrayList<>(draft.getWaypoints());
        int insertionIndex = Math.max(0, Math.min(index + 1, updated.size()));
        updated.add(insertionIndex, waypoint);
        draft = draft.withWaypoints(updated);
    }

    public boolean move(int index, @NonNull MissionWaypoint waypoint) {
        if (index < 0 || index >= draft.getWaypoints().size()) return false;
        List<MissionWaypoint> updated = new ArrayList<>(draft.getWaypoints());
        updated.set(index, waypoint);
        draft = draft.withWaypoints(updated);
        return true;
    }

    public boolean delete(int index) {
        if (index < 0 || index >= draft.getWaypoints().size()) return false;
        List<MissionWaypoint> updated = new ArrayList<>(draft.getWaypoints());
        updated.remove(index);
        draft = draft.withWaypoints(updated);
        return true;
    }
}
