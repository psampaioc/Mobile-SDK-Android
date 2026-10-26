package com.dji.sdk.sample.recording;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Records unfinished sessions across process death; it never attempts an unsafe MP4 append. */
final class RecordingRecoveryStore {
    private static final String PREFS = "local_recording_recovery";
    private static final String ACTIVE = "active";
    private static final String DIRECTORY_URI = "directory_uri";

    private RecordingRecoveryStore() { }

    static void markActive(Context context, Uri directoryUri) {
        preferences(context).edit().putBoolean(ACTIVE, true)
                .putString(DIRECTORY_URI, directoryUri.toString()).commit();
    }

    static void markFinalized(Context context) {
        preferences(context).edit().clear().commit();
    }

    /** Writes a small independent marker because the original sidecar might not have flushed. */
    static boolean consumeInterrupted(Context context) {
        SharedPreferences preferences = preferences(context);
        if (!preferences.getBoolean(ACTIVE, false)) return false;
        String value = preferences.getString(DIRECTORY_URI, null);
        preferences.edit().clear().commit();
        if (value == null || value.isEmpty()) return true;
        try {
            DocumentFile directory = DocumentFile.fromSingleUri(context, Uri.parse(value));
            if (directory == null || !directory.canWrite()) return true;
            DocumentFile marker = directory.createFile("application/x-ndjson", "interrupted.ndjson");
            if (marker == null) return true;
            OutputStream output = context.getContentResolver().openOutputStream(marker.getUri(), "w");
            if (output != null) {
                output.write("{\"v\":1,\"type\":\"recording_interrupted\",\"detail\":\"process_recreated_no_mp4_append\"}\n"
                        .getBytes(StandardCharsets.UTF_8));
                output.close();
            }
        } catch (Exception ignored) { /* UI still reports the interrupted session. */ }
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
