package com.dji.sdk.sample.operate;

import android.content.Context;
import android.content.SharedPreferences;

/** Local operator intent only; saving never applies a DJI camera setting. */
public final class PrimaryVideoProfilePreferences {
    private static final String FILE = "primary_video_profile";
    private static final String RESOLUTION = "resolution";
    private static final String FRAME_RATE = "frame_rate";
    private static final String TARGET = "target";
    private final SharedPreferences preferences;

    public PrimaryVideoProfilePreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Injectable only for deterministic local-persistence verification. */
    PrimaryVideoProfilePreferences(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public PrimaryVideoProfile load() {
        try { return new PrimaryVideoProfile(preferences.getString(RESOLUTION, null),
                preferences.getString(FRAME_RATE, null), preferences.getString(TARGET, null)); }
        catch (RuntimeException ignored) { return null; }
    }

    public boolean save(PrimaryVideoProfile value) {
        return value != null && preferences.edit().putString(RESOLUTION, value.getResolutionId())
                .putString(FRAME_RATE, value.getFrameRateId())
                .putString(TARGET, value.getTargetId()).commit();
    }
}
