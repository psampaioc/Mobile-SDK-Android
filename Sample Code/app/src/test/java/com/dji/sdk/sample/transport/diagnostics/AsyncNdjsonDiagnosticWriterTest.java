package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

public final class AsyncNdjsonDiagnosticWriterTest {
    public static void run() throws Exception {
        File destination = File.createTempFile("dji-telemetry-", ".ndjson");
        destination.deleteOnExit();
        AsyncNdjsonDiagnosticWriter writer = new AsyncNdjsonDiagnosticWriter(destination, 8);
        writer.onTelemetry(new TelemetryEvent("bench", "gimbal", 1, 0, 99,
                Collections.singletonMap("mode", new TelemetryField("FREE", true, "gimbal", 1))));
        writer.close();

        AsyncNdjsonDiagnosticWriter.Snapshot snapshot = writer.snapshot();
        check(snapshot.writtenRecords == 1, "written record counter");
        check(!snapshot.workerAlive, "worker stops cleanly");
        check(snapshot.terminalFailure.isEmpty(), "no writer terminal failure");

        List<String> lines = Files.readAllLines(destination.toPath(), StandardCharsets.UTF_8);
        check(lines.size() == 1, "one flushed record");
        check(lines.get(0).contains("\"source\":\"gimbal\""), "source serialized");
        check(lines.get(0).contains("\"component_index\":1"), "component index serialized");

        rotationFailurePreservesSourceEvidence();
        renameFailurePreservesSourceEvidence();
    }

    private static void rotationFailurePreservesSourceEvidence() throws Exception {
        File destination = File.createTempFile("dji-telemetry-rotation-", ".ndjson");
        File blockedOldest = new File(destination.getPath() + "." + AsyncNdjsonDiagnosticWriter.MAX_SEGMENTS);
        try {
            AsyncNdjsonDiagnosticWriter writer = new AsyncNdjsonDiagnosticWriter(destination, 8);
            writer.onTelemetry(new TelemetryEvent("bench", "gimbal", 1, 0, 99,
                    Collections.singletonMap("mode", new TelemetryField("FREE", true, "gimbal", 1))));
            waitForWrittenRecords(writer, 1);

            check(blockedOldest.mkdir(), "create blocked oldest segment directory");
            File child = new File(blockedOldest, "retain");
            try (FileOutputStream stream = new FileOutputStream(child)) {
                stream.write(1);
            }
            forceRotationBeforeNextRecord(writer);
            writer.onTelemetry(new TelemetryEvent("bench", "battery", 2, 0, 100,
                    Collections.singletonMap("mode", new TelemetryField("FREE", true, "gimbal", 1))));
            waitForTerminalFailure(writer);
            writer.close();

            AsyncNdjsonDiagnosticWriter.Snapshot snapshot = writer.snapshot();
            check(snapshot.terminalFailure.contains("Cannot delete diagnostic segment"),
                    "rotation delete failure reaches terminal writer state");
            writer.onTelemetry(new TelemetryEvent("bench", "battery", 3, 0, 101,
                    Collections.singletonMap("mode", new TelemetryField("FREE", true, "gimbal", 1))));
            check(writer.getQueueSize() == 0, "terminal writer failure rejects later records");
            List<String> lines = Files.readAllLines(destination.toPath(), StandardCharsets.UTF_8);
            check(lines.size() == 1, "source evidence remains after failed rotation");
            check(lines.get(0).contains("\"source\":\"gimbal\""),
                    "original source record remains");
        } finally {
            File child = new File(blockedOldest, "retain");
            child.delete();
            blockedOldest.delete();
            destination.delete();
        }
    }

    private static void renameFailurePreservesSourceEvidence() throws Exception {
        File destination = File.createTempFile("dji-telemetry-rename-", ".ndjson");
        try {
            AsyncNdjsonDiagnosticWriter writer = new AsyncNdjsonDiagnosticWriter(destination, 8,
                    new AsyncNdjsonDiagnosticWriter.FileOperations() {
                        @Override
                        public boolean delete(File file) {
                            return file.delete();
                        }

                        @Override
                        public boolean rename(File source, File target) {
                            return false;
                        }
                    });
            writer.onTelemetry(new TelemetryEvent("bench", "gimbal", 1, 0, 99,
                    Collections.singletonMap("mode", new TelemetryField("FREE", true, "gimbal", 1))));
            waitForWrittenRecords(writer, 1);
            forceRotationBeforeNextRecord(writer);
            writer.onTelemetry(new TelemetryEvent("bench", "gimbal", 2, 0, 100,
                    Collections.singletonMap("mode", new TelemetryField("FREE", true, "gimbal", 1))));
            waitForTerminalFailure(writer);
            writer.close();

            AsyncNdjsonDiagnosticWriter.Snapshot snapshot = writer.snapshot();
            check(snapshot.terminalFailure.contains("Cannot rotate diagnostic source"),
                    "rotation rename failure reaches terminal writer state");
            List<String> lines = Files.readAllLines(destination.toPath(), StandardCharsets.UTF_8);
            check(lines.size() == 1, "source evidence remains after failed rename");
            check(lines.get(0).contains("\"source\":\"gimbal\""),
                    "original source record remains after rename failure");
        } finally {
            destination.delete();
        }
    }

    private static void forceRotationBeforeNextRecord(AsyncNdjsonDiagnosticWriter writer) throws Exception {
        Field currentSegmentBytes = AsyncNdjsonDiagnosticWriter.class.getDeclaredField("currentSegmentBytes");
        currentSegmentBytes.setAccessible(true);
        currentSegmentBytes.setLong(writer, AsyncNdjsonDiagnosticWriter.MAX_SEGMENT_BYTES);
    }

    private static void waitForWrittenRecords(AsyncNdjsonDiagnosticWriter writer, long expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (writer.snapshot().writtenRecords < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        check(writer.snapshot().writtenRecords >= expected, "initial source record written");
    }

    private static void waitForTerminalFailure(AsyncNdjsonDiagnosticWriter writer) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (writer.snapshot().terminalFailure.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        check(!writer.snapshot().terminalFailure.isEmpty(), "rotation failure becomes terminal");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
