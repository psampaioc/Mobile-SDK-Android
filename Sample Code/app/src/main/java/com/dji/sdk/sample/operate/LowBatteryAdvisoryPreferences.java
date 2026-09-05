package com.dji.sdk.sample.operate;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** App-only persisted threshold; deliberately never touches DJI flight-controller settings. */
public final class LowBatteryAdvisoryPreferences {
    private static final String FILE = "operate_preferences";
    private static final String LANDING_ADVISORY_THRESHOLD = "landing_advisory_threshold";
    private final SharedPreferences preferences;

    public LowBatteryAdvisoryPreferences(@NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getThresholdPercent() {
        return LowBatteryAdvisory.normalizedThreshold(preferences.getInt(LANDING_ADVISORY_THRESHOLD,
                LowBatteryAdvisory.DEFAULT_THRESHOLD_PERCENT));
    }

    /** Invalid input is rejected and the previously persisted value is left intact. */
    public boolean saveThresholdPercent(int percent) {
        if (!LowBatteryAdvisory.isValidThreshold(percent)) return false;
        return preferences.edit().putInt(LANDING_ADVISORY_THRESHOLD, percent).commit();
    }
}
