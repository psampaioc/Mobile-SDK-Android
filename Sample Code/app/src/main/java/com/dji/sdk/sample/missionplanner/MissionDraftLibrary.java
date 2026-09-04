package com.dji.sdk.sample.missionplanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local draft library. It never uploads, starts, or otherwise controls an aircraft. */
public final class MissionDraftLibrary {
    private static final String PREFERENCES = "mission_draft_library";
    private static final String KEY_DRAFTS = "drafts_v1";

    private final SharedPreferences preferences;

    public MissionDraftLibrary(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }

    public synchronized List<MissionDraft> loadAll() {
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
}
