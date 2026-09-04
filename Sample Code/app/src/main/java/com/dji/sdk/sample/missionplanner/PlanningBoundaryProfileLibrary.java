package com.dji.sdk.sample.missionplanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded local storage for named app-side planning profiles. */
public final class PlanningBoundaryProfileLibrary {
    private static final String PREFERENCES = "planning_boundary_profiles";
    private static final String KEY_PROFILES = "profiles_v1";
    private final SharedPreferences preferences;
    public PlanningBoundaryProfileLibrary(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
    public synchronized List<PlanningBoundaryProfile> loadAll() {
        String value = preferences.getString(KEY_PROFILES, null);
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray encoded = new JSONArray(value); List<PlanningBoundaryProfile> profiles = new ArrayList<>();
            for (int i = 0; i < encoded.length(); i++) try { profiles.add(PlanningBoundaryProfile.decode(encoded.getString(i))); }
            catch (Exception ignored) { }
            return Collections.unmodifiableList(profiles);
        } catch (Exception ignored) { return Collections.emptyList(); }
    }
    public synchronized boolean save(PlanningBoundaryProfile profile) {
        if (profile == null) return false;
        try {
            List<PlanningBoundaryProfile> profiles = new ArrayList<>(loadAll());
            for (int i = 0; i < profiles.size(); i++) if (profile.getName().equals(profiles.get(i).getName())) { profiles.set(i, profile); return replaceAll(profiles); }
            profiles.add(profile); return replaceAll(profiles);
        } catch (Exception ignored) { return false; }
    }
    private boolean replaceAll(List<PlanningBoundaryProfile> profiles) throws Exception {
        JSONArray values = new JSONArray(); for (PlanningBoundaryProfile profile : profiles) values.put(profile.encode());
        return preferences.edit().putString(KEY_PROFILES, values.toString()).commit();
    }
}
