package com.dji.sdk.sample.missionplanner;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Android-runtime coverage for app-local defaults. Uses an isolated preferences file. */
@RunWith(AndroidJUnit4.class)
public final class MissionDefaultsPreferencesInstrumentedTest {
    private static final String TEST_FILE = "mission_defaults_instrumented_test";
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        context.getSharedPreferences(TEST_FILE, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        context.getSharedPreferences(TEST_FILE, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void persistsOnlyTheValidProfile() {
        MissionDefaultsPreferences preferences = new MissionDefaultsPreferences(context, TEST_FILE);
        MissionDefaults saved = new MissionDefaults(40f, 5f, MissionHeadingMode.REMOTE_CONTROLLER,
                MissionFinishAction.RETURN_AND_LAND_DJI_HOME);
        assertEquals(true, preferences.save(saved));

        MissionDefaults restored = new MissionDefaultsPreferences(context, TEST_FILE).load();
        assertEquals(40f, restored.getAltitudeMeters(), 0f);
        assertEquals(5f, restored.getSpeedMetersPerSecond(), 0f);
        assertEquals(MissionHeadingMode.REMOTE_CONTROLLER, restored.getHeadingMode());
        assertEquals(MissionFinishAction.RETURN_AND_LAND_DJI_HOME, restored.getFinishAction());

        try {
            new MissionDefaults(-1f, 5f, MissionHeadingMode.AUTO, MissionFinishAction.HOVER_AT_FINAL);
            fail("invalid defaults must be rejected before persistence");
        } catch (IllegalArgumentException expected) { }

        MissionDefaults unchanged = preferences.load();
        assertEquals(40f, unchanged.getAltitudeMeters(), 0f);
        assertEquals(MissionHeadingMode.REMOTE_CONTROLLER, unchanged.getHeadingMode());
    }
}
