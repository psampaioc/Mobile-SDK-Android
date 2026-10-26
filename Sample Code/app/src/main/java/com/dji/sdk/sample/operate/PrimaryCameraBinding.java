package com.dji.sdk.sample.operate;

/**
 * Fixed payload identity for the current M210 RTK V2 installation.
 *
 * <p>The Primary Zenmuse is attached to the aircraft's left connector. Camera-list order is
 * not a stable identity, so Settings must use this component index rather than getCameras().</p>
 */
public final class PrimaryCameraBinding {
    public static final int LEFT_CONNECTOR_COMPONENT_INDEX = 0;
    public static final String LEFT_CONNECTOR_TARGET_ID = "m210-left-connector-camera-0";
    public static final String LEFT_CONNECTOR_LABEL = "LEFT CONNECTOR \u2022 ZENMUSE";

    private PrimaryCameraBinding() { }

    public static int componentIndex() { return LEFT_CONNECTOR_COMPONENT_INDEX; }
    public static String targetId() { return LEFT_CONNECTOR_TARGET_ID; }
    public static String label() { return LEFT_CONNECTOR_LABEL; }

    public static boolean matches(PrimaryVideoProfile profile) {
        return profile != null && LEFT_CONNECTOR_TARGET_ID.equals(profile.getTargetId());
    }
}
