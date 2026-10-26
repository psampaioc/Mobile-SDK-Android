package com.dji.sdk.sample.missionplanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Local draft library. It never uploads, starts, or otherwise controls an aircraft. */
public final class MissionDraftLibrary {
    private static final String PREFERENCES = "mission_draft_library";
    private static final String KEY_DRAFTS = "drafts_v1";
    private static final String KEY_SAF_MIGRATED = "saf_migrated_v1";

    private final SharedPreferences preferences;
    private final Context context;
    private final MissionFolderPreferences folder;

    public MissionDraftLibrary(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        folder = new MissionFolderPreferences(this.context);
    }

    public synchronized List<MissionDraft> loadAll() {
        DocumentFile tree = tree();
        if (tree != null) {
            migrateLegacyIfNeeded(tree);
            return loadFromTree(tree);
        }
        String saved = preferences.getString(KEY_DRAFTS, null);
        if (saved == null || saved.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray encodedDrafts = new JSONArray(saved);
            List<MissionDraft> drafts = new ArrayList<>();
            for (int index = 0; index < encodedDrafts.length(); index++) {
                try {
                    drafts.add(MissionDraftJson.decode(encodedDrafts.getString(index)));
                } catch (JSONException ignored) {
                    // A malformed imported draft is skipped; valid saved drafts remain available.
                }
            }
            return Collections.unmodifiableList(drafts);
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
    }

    public synchronized boolean replaceAll(List<MissionDraft> drafts) {
        JSONArray encodedDrafts = new JSONArray();
        try {
            for (MissionDraft draft : drafts == null ? Collections.<MissionDraft>emptyList() : drafts) {
                if (!MissionDraftValidator.validate(draft).isEmpty()) return false;
                encodedDrafts.put(MissionDraftJson.encode(draft));
            }
            return preferences.edit().putString(KEY_DRAFTS, encodedDrafts.toString()).commit();
        } catch (JSONException ignored) {
            return false;
        }
    }

    public synchronized boolean save(MissionDraft draft) {
        if (draft == null || !MissionDraftValidator.validate(draft).isEmpty()) return false;
        DocumentFile tree = tree();
        if (tree != null) return saveToTree(tree, draft);
        List<MissionDraft> drafts = new ArrayList<>(loadAll());
        for (int index = 0; index < drafts.size(); index++) {
            if (draft.getName().equals(drafts.get(index).getName())) {
                drafts.set(index, draft);
                return replaceAll(drafts);
            }
        }
        drafts.add(draft);
        return replaceAll(drafts);
    }

    private DocumentFile tree() {
        Uri uri = folder.getTreeUri();
        return uri == null ? null : DocumentFile.fromTreeUri(context, uri);
    }

    private List<MissionDraft> loadFromTree(DocumentFile tree) {
        List<MissionDraft> drafts = new ArrayList<>();
        try {
            for (DocumentFile file : tree.listFiles()) {
                if (!file.isFile() || file.getName() == null || !file.getName().endsWith(".json")) continue;
                try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
                    if (input == null) continue;
                    drafts.add(MissionDraftJson.decode(new String(read(input), StandardCharsets.UTF_8)));
                } catch (Exception ignored) { }
            }
        } catch (SecurityException ignored) { }
        return Collections.unmodifiableList(drafts);
    }

    private boolean saveToTree(DocumentFile tree, MissionDraft draft) {
        String fileName = safeName(draft.getName()) + ".json";
        DocumentFile target = tree.findFile(fileName);
        if (target == null) target = tree.createFile("application/json", fileName);
        if (target == null) return false;
        try (OutputStream output = context.getContentResolver().openOutputStream(target.getUri(), "wt")) {
            if (output == null) return false;
            output.write(MissionDraftJson.encode(draft).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void migrateLegacyIfNeeded(DocumentFile tree) {
        if (preferences.getBoolean(KEY_SAF_MIGRATED, false)) return;
        List<MissionDraft> legacy = loadLegacy();
        for (MissionDraft draft : legacy) if (!saveToTree(tree, draft)) return;
        preferences.edit().putBoolean(KEY_SAF_MIGRATED, true).apply();
    }

    private List<MissionDraft> loadLegacy() {
        String saved = preferences.getString(KEY_DRAFTS, null);
        if (saved == null || saved.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONArray encodedDrafts = new JSONArray(saved);
            List<MissionDraft> drafts = new ArrayList<>();
            for (int index = 0; index < encodedDrafts.length(); index++) {
                try { drafts.add(MissionDraftJson.decode(encodedDrafts.getString(index))); }
                catch (JSONException ignored) { }
            }
            return drafts;
        } catch (JSONException ignored) { return Collections.emptyList(); }
    }

    private static byte[] read(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String safeName(String value) {
        String result = value == null ? "mission" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return result.isEmpty() ? "mission" : result;
    }
}
