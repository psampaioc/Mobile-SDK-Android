package com.dji.sdk.sample.operate;

/** App-owned identity of one DJI-supported resolution/frame-rate pair. */
public final class PrimaryVideoProfile {
    private final String resolutionId;
    private final String frameRateId;
    private final String targetId;

    public PrimaryVideoProfile(String resolutionId, String frameRateId) {
        this(resolutionId, frameRateId, null);
    }

    public PrimaryVideoProfile(String resolutionId, String frameRateId, String targetId) {
        if (blank(resolutionId) || blank(frameRateId)) {
            throw new IllegalArgumentException("Primary video profile is incomplete");
        }
        this.resolutionId = resolutionId;
        this.frameRateId = frameRateId;
        this.targetId = blank(targetId) ? null : targetId;
    }

    public String getResolutionId() { return resolutionId; }
    public String getFrameRateId() { return frameRateId; }
    public String getTargetId() { return targetId; }
    public PrimaryVideoProfile forTarget(String target) {
        return new PrimaryVideoProfile(resolutionId, frameRateId, target);
    }
    /** True when two values describe the same DJI resolution/frame-rate tuple. */
    public boolean sameSelection(PrimaryVideoProfile other) {
        return other != null && resolutionId.equals(other.resolutionId) && frameRateId.equals(other.frameRateId);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof PrimaryVideoProfile)) return false;
        PrimaryVideoProfile value = (PrimaryVideoProfile) other;
        return sameSelection(value) && (targetId == null ? value.targetId == null : targetId.equals(value.targetId));
    }
    @Override public int hashCode() {
        int result = 31 * resolutionId.hashCode() + frameRateId.hashCode();
        return 31 * result + (targetId == null ? 0 : targetId.hashCode());
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
