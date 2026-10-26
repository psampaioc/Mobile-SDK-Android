package com.dji.sdk.sample.missionplanner;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/** Android-runtime coverage for the app-owned JSON draft contract. */
@RunWith(AndroidJUnit4.class)
public final class MissionDraftJsonInstrumentedTest {
    @Test public void legacyDraftWithoutHeadingDefaultsToAuto() throws Exception {
        String legacy = "{\"format_version\":1,\"name\":\"Legacy\",\"default_altitude_m\":30,"
                + "\"base_speed_m_s\":3,\"finish_action\":\"HOVER_AT_FINAL\",\"waypoints\":["
                + "{\"latitude_deg\":38.7,\"longitude_deg\":-9.1},{\"latitude_deg\":38.71,\"longitude_deg\":-9.11}]}";
        assertEquals(MissionHeadingMode.AUTO, MissionDraftJson.decode(legacy).getHeadingMode());
    }

    @Test public void selectedHeadingSurvivesAndroidJsonRoundTrip() throws Exception {
        MissionDraft draft = new MissionDraft("Heading", 30f, 3f, MissionFinishAction.HOVER_AT_FINAL,
                MissionHeadingMode.REMOTE_CONTROLLER, Arrays.asList(
                new MissionWaypoint(38.7, -9.1, null), new MissionWaypoint(38.71, -9.11, null)), null, null);
        MissionDraft decoded = MissionDraftJson.decode(MissionDraftJson.encode(draft));
        assertEquals(MissionHeadingMode.REMOTE_CONTROLLER, decoded.getHeadingMode());
    }
}
