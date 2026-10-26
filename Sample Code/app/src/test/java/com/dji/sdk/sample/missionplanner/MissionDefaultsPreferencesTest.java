package com.dji.sdk.sample.missionplanner;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** JVM proof that Settings defaults survive a new view/store instance. */
final class MissionDefaultsPreferencesTest {
    static void run() {
        InMemoryPreferences backing = new InMemoryPreferences();
        MissionDefaults expected = new MissionDefaults(48f, 6f, MissionHeadingMode.INITIAL_DIRECTION,
                MissionFinishAction.RETURN_AND_LAND_DJI_HOME);
        require(new MissionDefaultsPreferences(backing).save(expected), "defaults must commit");
        MissionDefaults restored = new MissionDefaultsPreferences(backing).load();
        require(restored.getAltitudeMeters() == 48f && restored.getSpeedMetersPerSecond() == 6f,
                "numeric mission defaults must survive recreation");
        require(restored.getHeadingMode() == MissionHeadingMode.INITIAL_DIRECTION
                        && restored.getFinishAction() == MissionFinishAction.RETURN_AND_LAND_DJI_HOME,
                "enum mission defaults must survive recreation");
        try {
            new MissionDefaults(-1f, 6f, MissionHeadingMode.AUTO, MissionFinishAction.HOVER_AT_FINAL);
            throw new AssertionError("invalid defaults must be rejected before save");
        } catch (IllegalArgumentException expectedFailure) {
            MissionDefaults unchanged = new MissionDefaultsPreferences(backing).load();
            require(unchanged.getAltitudeMeters() == 48f && unchanged.getSpeedMetersPerSecond() == 6f,
                    "invalid input must leave the prior persisted defaults intact");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class InMemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(values); }
        @Override public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { Object value = values.get(key); return value instanceof Set ? new HashSet<>((Set<String>) value) : fallback; }
        @Override public int getInt(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
        @Override public long getLong(String key, long fallback) { Object value = values.get(key); return value instanceof Long ? (Long) value : fallback; }
        @Override public float getFloat(String key, float fallback) { Object value = values.get(key); return value instanceof Float ? (Float) value : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new Editor() {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removed = new HashSet<>();
            private boolean clear;
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { pending.put(key, value == null ? null : new HashSet<>(value)); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removed.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() { if (clear) values.clear(); for (String key : removed) values.remove(key); values.putAll(pending); }
        }; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }
}
