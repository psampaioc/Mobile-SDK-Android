package com.dji.sdk.sample.operate;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free coverage for valid dependent resolution/FPS selection. */
public final class PrimaryVideoProfileSelectionTest {
    public static void main(String[] args) {
        PrimaryVideoProfile uhd30 = new PrimaryVideoProfile("UHD", "30");
        PrimaryVideoProfile fhd30 = new PrimaryVideoProfile("FHD", "30");
        PrimaryVideoProfile fhd60 = new PrimaryVideoProfile("FHD", "60");
        PrimaryVideoProfileSelection selection = new PrimaryVideoProfileSelection(Arrays.asList(
                new PrimaryVideoProfileSelection.Choice(uhd30, "3840 × 2160", "30 fps"),
                new PrimaryVideoProfileSelection.Choice(fhd30, "1920 × 1080", "30 fps"),
                new PrimaryVideoProfileSelection.Choice(fhd60, "1920 × 1080", "60 fps"),
                new PrimaryVideoProfileSelection.Choice(fhd60, "1920 × 1080", "60 fps")));
        List<String> resolutions = selection.resolutions();
        require(resolutions.size() == 2 && resolutions.get(0).equals("UHD"), "unique resolutions");
        require(selection.frameRatesFor("FHD").size() == 2, "fps filtered by resolution");
        require(selection.resolve(new PrimaryVideoProfile("FHD", "60")) != null, "valid pair resolves");
        require(selection.resolve(new PrimaryVideoProfile("FHD", "60", PrimaryCameraBinding.targetId())) != null,
                "target-scoped valid pair resolves");
        require(selection.resolve(new PrimaryVideoProfile("UHD", "60")) == null, "invalid cross-product rejected");
        require(selection.frameRatesFor("FHD").size() == 2, "duplicate tuple is collapsed");
        preferencesRoundTripAndRejectsMalformedPair();
        primaryBindingIsFixedToLeftConnector();
        labelsUseDjiEnumNamesInsteadOfProtocolCodes();
        System.out.println("Primary video profile selection tests passed");
    }

    private static void labelsUseDjiEnumNamesInsteadOfProtocolCodes() {
        require("30 fps".equals(PrimaryVideoProfileLabels.frameRate("FRAME_RATE_30_FPS")),
                "30 fps must not display the protocol code");
        require("29.970 fps".equals(PrimaryVideoProfileLabels.frameRate("FRAME_RATE_29_DOT_970_FPS")),
                "fractional FPS label");
        require("3840 × 2160".equals(PrimaryVideoProfileLabels.resolution("RESOLUTION_3840x2160")),
                "resolution label");
    }

    private static void preferencesRoundTripAndRejectsMalformedPair() {
        InMemoryPreferences backing = new InMemoryPreferences();
        PrimaryVideoProfile stored = new PrimaryVideoProfile("RESOLUTION_3840x2160", "FRAME_RATE_30_FPS",
                PrimaryCameraBinding.targetId());
        require(new PrimaryVideoProfilePreferences(backing).save(stored), "profile must commit");
        require(stored.equals(new PrimaryVideoProfilePreferences(backing).load()), "profile must survive recreation");
        backing.values.put("resolution", "RESOLUTION_3840x2160");
        backing.values.remove("frame_rate");
        require(new PrimaryVideoProfilePreferences(backing).load() == null, "incomplete stored pair must be rejected");
    }
    private static void primaryBindingIsFixedToLeftConnector() {
        PrimaryVideoProfile left = new PrimaryVideoProfile("FHD", "30", PrimaryCameraBinding.targetId());
        require(PrimaryCameraBinding.componentIndex() == 0, "M210 left payload uses component zero");
        require(PrimaryCameraBinding.matches(left), "left-target preference must match");
        require(!PrimaryCameraBinding.matches(new PrimaryVideoProfile("FHD", "30")),
                "legacy unscoped preference must not auto-target a payload");
        require(!PrimaryCameraBinding.matches(new PrimaryVideoProfile("FHD", "30", "other-camera")),
                "another payload preference must not match");
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
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { pending.put(key, value == null ? null : new HashSet<>(value)); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removed.add(key); return this; }
            @Override public Editor clear() { pending.clear(); removed.addAll(values.keySet()); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() { for (String key : removed) values.remove(key); values.putAll(pending); }
        }; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }
}
