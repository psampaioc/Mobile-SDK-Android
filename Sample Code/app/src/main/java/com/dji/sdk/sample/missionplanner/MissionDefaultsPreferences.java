package com.dji.sdk.sample.missionplanner;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;

/** Local-only defaults; never changes DJI aircraft settings. */
public final class MissionDefaultsPreferences {
    private static final String FILE = "mission_defaults";
    private static final String ALTITUDE = "altitude_m";
    private static final String SPEED = "speed_m_s";
    private static final String HEADING = "heading";
    private static final String FINISH = "finish";
    private final SharedPreferences preferences;
    public MissionDefaultsPreferences(@NonNull Context context) {
        this(context, FILE);
    }
    MissionDefaultsPreferences(@NonNull Context context, @NonNull String file) {
        preferences = context.getApplicationContext().getSharedPreferences(file, Context.MODE_PRIVATE);
    }
    /** Injectable only for deterministic local-persistence verification. */
    MissionDefaultsPreferences(@NonNull SharedPreferences preferences) {
        this.preferences = preferences;
    }
    @NonNull public MissionDefaults load() {
        try {
            return new MissionDefaults(preferences.getFloat(ALTITUDE, MissionDefaults.DEFAULT_ALTITUDE_METERS),
                    preferences.getFloat(SPEED, MissionDefaults.DEFAULT_SPEED_METERS_PER_SECOND),
                    MissionHeadingMode.valueOf(preferences.getString(HEADING, MissionHeadingMode.AUTO.name())),
                    MissionFinishAction.valueOf(preferences.getString(FINISH, MissionFinishAction.HOVER_AT_FINAL.name())));
        } catch (RuntimeException ignored) { return MissionDefaults.standard(); }
    }
    public boolean save(@NonNull MissionDefaults value) {
        return preferences.edit().putFloat(ALTITUDE, value.getAltitudeMeters())
                .putFloat(SPEED, value.getSpeedMetersPerSecond()).putString(HEADING, value.getHeadingMode().name())
                .putString(FINISH, value.getFinishAction().name()).commit();
    }
}
