package com.dji.sdk.sample.missionplanner;

/** Immutable, app-local profile applied only when the operator creates a new draft. */
public final class MissionDefaults {
    public static final float DEFAULT_ALTITUDE_METERS = 30f;
    public static final float DEFAULT_SPEED_METERS_PER_SECOND = 3f;
    private final float altitudeMeters;
    private final float speedMetersPerSecond;
    private final MissionHeadingMode headingMode;
    private final MissionFinishAction finishAction;

    public MissionDefaults(float altitudeMeters, float speedMetersPerSecond,
            MissionHeadingMode headingMode, MissionFinishAction finishAction) {
        if (!Float.isFinite(altitudeMeters) || altitudeMeters < MissionDraftValidator.MIN_ALTITUDE_METERS
                || !Float.isFinite(speedMetersPerSecond)
                || speedMetersPerSecond < MissionDraftValidator.MIN_BASE_SPEED_METERS_PER_SECOND
                || speedMetersPerSecond > MissionDraftValidator.MAX_BASE_SPEED_METERS_PER_SECOND) {
            throw new IllegalArgumentException("Mission defaults are invalid");
        }
        this.altitudeMeters = altitudeMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.headingMode = headingMode == null ? MissionHeadingMode.AUTO : headingMode;
        this.finishAction = finishAction == null ? MissionFinishAction.HOVER_AT_FINAL : finishAction;
    }

    public static MissionDefaults standard() {
        return new MissionDefaults(DEFAULT_ALTITUDE_METERS, DEFAULT_SPEED_METERS_PER_SECOND,
                MissionHeadingMode.AUTO, MissionFinishAction.HOVER_AT_FINAL);
    }
    public float getAltitudeMeters() { return altitudeMeters; }
    public float getSpeedMetersPerSecond() { return speedMetersPerSecond; }
    public MissionHeadingMode getHeadingMode() { return headingMode; }
    public MissionFinishAction getFinishAction() { return finishAction; }
    public MissionDraft newDraft() {
        return new MissionDraft("Untitled mission", altitudeMeters, speedMetersPerSecond,
                finishAction, headingMode, java.util.Collections.emptyList(), null, null);
    }
}
