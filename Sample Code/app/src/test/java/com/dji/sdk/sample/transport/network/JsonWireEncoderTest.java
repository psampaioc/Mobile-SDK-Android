package com.dji.sdk.sample.transport.network;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class JsonWireEncoderTest {
    public static void run() {
        testFlightAllowlistAndInvalidNumbers();
        testRtkAndGimbalAllowlist();
        testHealthAndMetadataAreCompact();
    }

    private static void testFlightAllowlistAndInvalidNumbers() {
        Map<String, TelemetryField> fields = fields();
        fields.put("aircraft.latitude_deg", field(1.25, true));
        fields.put("aircraft.longitude_deg", field(Double.NaN, true));
        fields.put("aircraft.altitude_m", field(12.0, true));
        fields.put("heading_deg", field(90.0, true));
        fields.put("velocity.north_m_s", field(3.0, true));
        String json = text(JsonWireEncoder.telemetry(event("flight_controller", fields), 7));
        check(json.contains("\"type\":\"flight\""), "flight type");
        check(json.contains("\"seq\":7"), "flight sequence");
        check(json.contains("\"aircraft.latitude_deg\""), "latitude included");
        check(json.contains("\"aircraft.longitude_deg\":{\"value\":null,\"valid\":false}"),
                "non-finite coordinate is null and invalid");
        check(!json.contains("velocity.north_m_s"), "velocity excluded");
        check(!json.contains("chunk_index"), "chunk fields excluded");
        check(!json.contains("NaN"), "wire never contains NaN");
        Map<String, Object> parsed = object(json);
        keys(parsed, "flight envelope", "v", "type", "session", "stream", "seq", "rx_mono_ns", "data");
        keys(object(parsed.get("data")), "flight data", "fields");
        keys(object(object(parsed.get("data")).get("fields")), "flight fields",
                "aircraft.latitude_deg", "aircraft.longitude_deg", "aircraft.altitude_m", "heading_deg");
    }

    private static void testRtkAndGimbalAllowlist() {
        Map<String, TelemetryField> rtk = fields();
        rtk.put("fusion.latitude_deg", field(-23.0, true));
        rtk.put("fusion.longitude_deg", field(-46.0, true));
        rtk.put("is_being_used", field(true, true));
        rtk.put("mobile.altitude_m", field(99.0, true));
        String rtkJson = text(JsonWireEncoder.telemetry(event("rtk", rtk), 2));
        check(rtkJson.contains("\"type\":\"rtk\""), "rtk type");
        check(!rtkJson.contains("mobile.altitude_m"), "extra RTK fields excluded");

        Map<String, TelemetryField> gimbal = fields();
        gimbal.put("attitude.pitch_deg", field(-45.0, true));
        gimbal.put("attitude.roll_deg", field(5.0, true));
        String gimbalJson = text(JsonWireEncoder.telemetry(event("gimbal", gimbal), 3));
        check(gimbalJson.contains("\"type\":\"gimbal\""), "gimbal type");
        check(gimbalJson.contains("attitude.pitch_deg"), "pitch included");
        check(!gimbalJson.contains("attitude.roll_deg"), "roll excluded");
        check(JsonWireEncoder.telemetry(event("battery", gimbal), 4) == null,
                "battery is not on transport wire");
        Map<String, Object> rtkParsed = object(rtkJson);
        keys(object(object(rtkParsed.get("data")).get("fields")), "rtk fields",
                "fusion.latitude_deg", "fusion.longitude_deg", "is_being_used");
        Map<String, Object> gimbalParsed = object(text(JsonWireEncoder.telemetry(event("gimbal", gimbal), 3)));
        keys(object(object(gimbalParsed.get("data")).get("fields")), "gimbal fields", "attitude.pitch_deg");
    }

    private static void testHealthAndMetadataAreCompact() {
        byte[] health = JsonWireEncoder.health("session", 2, 999, 1, 2, 3, 4, 5,
                6, 7, 8, 9, 10, 11);
        String healthJson = text(health);
        check(health.length <= TransportConfig.MAX_JSON_DATAGRAM_BYTES, "health budget");
        check(healthJson.contains("\"stream\":\"health:0\""), "health stream");
        check(!healthJson.contains("callback_hz"), "callback diagnostics stay local");
        Map<String, Object> parsedHealth = object(healthJson);
        keys(parsedHealth, "health envelope", "v", "type", "session", "stream", "seq", "rx_mono_ns", "data");
        keys(object(parsedHealth.get("data")), "health counters",
                "primary_video_callbacks", "secondary_video_callbacks",
                "primary_video_callback_drops", "secondary_video_callback_drops",
                "primary_rtp_packets", "secondary_rtp_packets", "primary_access_units",
                "secondary_access_units", "telemetry_queue_drops", "telemetry_socket_errors",
                "video_socket_errors");

        String fpv = text(JsonWireEncoder.frameMetadata("session", "secondary", 4, 0x11223344,
                100, 10, 11));
        check(fpv.contains("\"feed\":\"fpv\""), "secondary metadata is fpv");
        check(!fpv.contains("physical_source"), "physical source excluded");
        check(!fpv.contains("first_rtp_seq"), "packet diagnostics excluded");
        keys(object(fpv), "video metadata", "v", "type", "session", "feed", "frame_seq",
                "rtp_ssrc", "rtp_ts", "au_first_byte_rx_mono_ns", "au_complete_rx_mono_ns");

        Map<String, TelemetryField> escaped = fields();
        escaped.put("aircraft.latitude_deg", field(1.0, true));
        escaped.put("aircraft.longitude_deg", field(2.0, true));
        escaped.put("aircraft.altitude_m", field(3.0, true));
        escaped.put("heading_deg", field(4.0, true));
        object(text(JsonWireEncoder.telemetry(new TelemetryEvent("session-\\\"newline\\n",
                "flight_controller", 0, 1, 2, escaped), 1)));
    }

    private static TelemetryEvent event(String source, Map<String, TelemetryField> fields) {
        return new TelemetryEvent("session", source, 0, 12, 1234, fields);
    }

    private static Map<String, TelemetryField> fields() { return new LinkedHashMap<>(); }
    private static TelemetryField field(Object value, boolean valid) {
        return new TelemetryField(value, valid, "test", 0);
    }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new AssertionError("expected JSON object");
        return (Map<String, Object>) value;
    }

    private static void keys(Map<String, Object> value, String label, String... expected) {
        Set<String> actual = value.keySet();
        Set<String> wanted = new HashSet<>(Arrays.asList(expected));
        check(actual.equals(wanted), label + " keys: " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Small strict parser keeps this JVM-only suite independent from Android's org.json runtime. */
    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) { this.text = text; }

        Object parse() {
            Object value = value();
            whitespace();
            if (position != text.length()) fail("trailing input");
            return value;
        }

        private Object value() {
            whitespace();
            if (position >= text.length()) fail("missing value");
            char next = text.charAt(position);
            if (next == '{') return objectValue();
            if (next == '"') return string();
            if (next == 't') return literal("true", Boolean.TRUE);
            if (next == 'f') return literal("false", Boolean.FALSE);
            if (next == 'n') return literal("null", null);
            if (next == '-' || (next >= '0' && next <= '9')) return number();
            fail("unexpected value");
            return null;
        }

        private Map<String, Object> objectValue() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return result;
            while (true) {
                whitespace();
                if (position >= text.length() || text.charAt(position) != '"') fail("object key");
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char next = text.charAt(position++);
                if (next == '"') return result.toString();
                if (next == '\\') {
                    if (position >= text.length()) fail("unfinished escape");
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"': result.append('"'); break;
                        case '\\': result.append('\\'); break;
                        case '/': result.append('/'); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u': result.append((char) Integer.parseInt(take(4), 16)); break;
                        default: fail("invalid escape");
                    }
                } else {
                    if (next < 0x20) fail("unescaped control character");
                    result.append(next);
                }
            }
            fail("unfinished string");
            return null;
        }

        private Object literal(String literal, Object result) {
            if (!text.startsWith(literal, position)) fail("invalid literal");
            position += literal.length();
            return result;
        }

        private Number number() {
            int start = position;
            if (consume('-')) { }
            digits();
            if (consume('.')) digits();
            if (consume('e') || consume('E')) {
                consume('+'); consume('-'); digits();
            }
            try { return Double.valueOf(text.substring(start, position)); }
            catch (NumberFormatException error) { fail("invalid number"); return 0; }
        }

        private void digits() {
            int start = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
            if (start == position) fail("expected digit");
        }

        private String take(int count) {
            if (position + count > text.length()) fail("truncated unicode escape");
            String result = text.substring(position, position + count);
            position += count;
            return result;
        }

        private boolean consume(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            whitespace();
            if (!consume(expected)) fail("expected '" + expected + "'");
        }

        private void whitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private void fail(String message) { throw new AssertionError(message + " at " + position); }
    }

    private static Map<String, Object> object(String json) {
        return object(new Parser(json).parse());
    }
}
