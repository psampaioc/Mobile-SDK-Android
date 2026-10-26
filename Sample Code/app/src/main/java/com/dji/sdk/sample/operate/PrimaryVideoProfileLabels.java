package com.dji.sdk.sample.operate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts DJI enum identities into stable labels without relying on protocol enum values. */
public final class PrimaryVideoProfileLabels {
    private static final Pattern DIMENSIONS = Pattern.compile(".*?(\\d+)[xX](\\d+).*");

    private PrimaryVideoProfileLabels() { }

    public static String resolution(String resolutionId) {
        if (resolutionId == null) return "UNKNOWN";
        Matcher matcher = DIMENSIONS.matcher(resolutionId);
        return matcher.matches() ? matcher.group(1) + " × " + matcher.group(2) : resolutionId;
    }

    /**
     * VideoFrameRate.value() is an MSDK protocol code, not a displayed FPS.  The enum name is
     * the stable, human-readable source: FRAME_RATE_29_DOT_970_FPS becomes 29.970 fps.
     */
    public static String frameRate(String frameRateId) {
        if (frameRateId == null || frameRateId.isEmpty()) return "UNKNOWN";
        String value = frameRateId.replaceFirst("^FRAME_RATE_", "").replaceFirst("_FPS$", "");
        if (value.equals(frameRateId) || value.equals("UNKNOWN")) return frameRateId;
        return value.replace("_DOT_", ".").replace('_', ' ') + " fps";
    }

    public static String profile(PrimaryVideoProfile value) {
        if (value == null) return "NONE";
        return resolution(value.getResolutionId()) + " · " + frameRate(value.getFrameRateId());
    }
}
