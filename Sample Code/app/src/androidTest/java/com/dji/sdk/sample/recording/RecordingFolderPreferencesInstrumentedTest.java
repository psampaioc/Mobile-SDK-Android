package com.dji.sdk.sample.recording;

import android.content.Context;
import android.net.Uri;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Android-runtime proof that the operator-selected recording tree survives a new preferences instance. */
@RunWith(AndroidJUnit4.class)
public final class RecordingFolderPreferencesInstrumentedTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        context.getSharedPreferences("local_recording_folder", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        context.getSharedPreferences("local_recording_folder", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void persistsTheSelectedTreeUriWithoutAFileSystemPath() {
        RecordingFolderPreferences first = new RecordingFolderPreferences(context);
        assertFalse(first.hasFolder());
        Uri selected = Uri.parse("content://com.example.documents/tree/primary%3ADocuments%2FMatriceTransport");
        assertTrue(first.save(selected));

        RecordingFolderPreferences restored = new RecordingFolderPreferences(context);
        assertTrue(restored.hasFolder());
        assertEquals(selected, restored.getTreeUri());
        assertTrue(restored.displayName().contains("MatriceTransport"));
    }
}
