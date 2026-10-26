package com.dji.sdk.sample.operate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure compatibility model; the view adapts DJI tuples into these choices. */
public final class PrimaryVideoProfileSelection {
    public static final class Choice {
        public final PrimaryVideoProfile profile;
        public final String resolutionLabel;
        public final String frameRateLabel;
        public Choice(PrimaryVideoProfile profile, String resolutionLabel, String frameRateLabel) {
            this.profile = profile; this.resolutionLabel = resolutionLabel; this.frameRateLabel = frameRateLabel;
        }
    }
    private final List<Choice> choices;
    public PrimaryVideoProfileSelection(List<Choice> source) {
        if (source == null) {
            choices = Collections.emptyList();
            return;
        }
        Map<PrimaryVideoProfile, Choice> unique = new LinkedHashMap<>();
        for (Choice choice : source) if (choice != null && choice.profile != null) unique.put(choice.profile, choice);
        choices = Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }
    public List<String> resolutions() {
        Map<String, String> unique = new LinkedHashMap<>();
        for (Choice choice : choices) unique.put(choice.profile.getResolutionId(), choice.resolutionLabel);
        return new ArrayList<>(unique.keySet());
    }
    public String labelForResolution(String id) {
        for (Choice choice : choices) if (choice.profile.getResolutionId().equals(id)) return choice.resolutionLabel;
        return id;
    }
    public List<Choice> frameRatesFor(String resolutionId) {
        List<Choice> result = new ArrayList<>();
        for (Choice choice : choices) if (choice.profile.getResolutionId().equals(resolutionId)) result.add(choice);
        return result;
    }
    public Choice resolve(PrimaryVideoProfile profile) {
        if (profile == null) return null;
        for (Choice choice : choices) if (choice.profile.sameSelection(profile)) return choice;
        return null;
    }
}
