package com.dji.sdk.sample.missionplanner;

/**
 * App-local Mission load/upload evidence tied to one observed aircraft connection generation.
 * A local draft is deliberately not part of this state, so it survives reconnects unchanged.
 */
public final class MissionSessionEvidence {
    private long aircraftSessionGeneration = -1L;
    private boolean loaded;
    private boolean uploaded;

    public void recordLoaded(long generation) {
        aircraftSessionGeneration = generation;
        loaded = true;
        uploaded = false;
    }

    /** Returns false when a late upload result belongs to a previous aircraft session. */
    public boolean recordUploaded(long generation) {
        if (!isLoadedFor(generation)) return false;
        uploaded = true;
        return true;
    }

    /** Returns true only when prior app evidence was cleared because the aircraft session changed. */
    public boolean invalidateFor(long generation) {
        if (!loaded && !uploaded) return false;
        if (aircraftSessionGeneration == generation) return false;
        clear();
        return true;
    }

    public boolean isLoadedFor(long generation) {
        return loaded && aircraftSessionGeneration == generation;
    }

    public boolean isUploadedFor(long generation) {
        return uploaded && isLoadedFor(generation);
    }

    public void clear() {
        aircraftSessionGeneration = -1L;
        loaded = false;
        uploaded = false;
    }
}
