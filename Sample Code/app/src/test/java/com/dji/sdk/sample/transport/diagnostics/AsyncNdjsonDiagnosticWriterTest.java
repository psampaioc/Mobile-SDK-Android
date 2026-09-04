package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;

import java.io.File;
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

        List<String> lines = Files.readAllLines(destination.toPath(), StandardCharsets.UTF_8);
        check(lines.size() == 1, "one flushed record");
        check(lines.get(0).contains("\"source\":\"gimbal\""), "source serialized");
        check(lines.get(0).contains("\"component_index\":1"), "component index serialized");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
