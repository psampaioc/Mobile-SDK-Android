package com.dji.sdk.sample.missionplanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Persisted, scoped SAF tree selected explicitly by the operator. */
public final class MissionFolderPreferences {
    private static final String PREFS = "mission_folder";
    private static final String KEY_URI = "tree_uri";
    private final SharedPreferences preferences;

    public MissionFolderPreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Uri getTreeUri() {
        String raw = preferences.getString(KEY_URI, null);
        return raw == null || raw.trim().isEmpty() ? null : Uri.parse(raw);
    }

    public boolean save(Uri uri) {
        return uri != null && preferences.edit().putString(KEY_URI, uri.toString()).commit();
    }

    public String displayName() {
        Uri uri = getTreeUri();
        if (uri == null) return "SELECT MISSION FOLDER";
        String path = uri.getLastPathSegment();
        return path == null || path.isEmpty() ? uri.toString() : path;
    }
}
