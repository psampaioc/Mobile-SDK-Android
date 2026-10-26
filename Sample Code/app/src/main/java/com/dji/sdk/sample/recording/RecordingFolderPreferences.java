package com.dji.sdk.sample.recording;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Persisted operator-authorized Storage Access Framework tree for local evidence. */
public final class RecordingFolderPreferences {
    private static final String PREFS = "local_recording_folder";
    private static final String KEY_URI = "tree_uri";
    private final SharedPreferences preferences;

    public RecordingFolderPreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Uri getTreeUri() {
        String raw = preferences.getString(KEY_URI, null);
        return raw == null || raw.trim().isEmpty() ? null : Uri.parse(raw);
    }

    public boolean save(Uri uri) {
        return uri != null && preferences.edit().putString(KEY_URI, uri.toString()).commit();
    }

    public boolean hasFolder() { return getTreeUri() != null; }

    public String displayName() {
        Uri uri = getTreeUri();
        if (uri == null) return "CHOOSE RECORDING FOLDER";
        String segment = uri.getLastPathSegment();
        return segment == null || segment.isEmpty() ? "RECORDING FOLDER READY" : segment;
    }
}
