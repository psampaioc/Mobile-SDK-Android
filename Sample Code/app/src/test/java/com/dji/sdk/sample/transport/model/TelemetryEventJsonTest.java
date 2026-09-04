package com.dji.sdk.sample.transport.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TelemetryEventJsonTest {
    public static void run() {
        Map<String, TelemetryField> fields = new LinkedHashMap<>();
        fields.put("flight.mode", new TelemetryField("GPS\nmode", true, "flight_controller", 0));
        fields.put("aircraft.latitude_deg", new TelemetryField(Double.NaN, false, "flight_controller", 0));
        TelemetryEvent event = new TelemetryEvent("session-1", "flight_controller", 0,
                7, 123456789L, fields);

        String json = event.toNdjsonLine();
        check(json.startsWith("{"), "JSON object start");
        check(!json.contains("\n"), "one physical line");
        check(json.contains("\"sequence\":7"), "sequence");
        check(json.contains("GPS\\nmode"), "escaped newline");
        check(json.contains("\"value\":null,\"valid\":false"), "invalid non-finite value");
        check(json.contains("\"received_elapsed_realtime_ns\":123456789"), "monotonic timestamp");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
