package com.dji.sdk.sample.transport.network;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON wire records for the intentionally small Android-to-Edge contract. */
public final class JsonWireEncoder {
    private JsonWireEncoder() { }

    /** Returns one unfragmented record, or {@code null} for a source not on the wire. */
    public static byte[] telemetry(TelemetryEvent event, long wireSequence) {
        String type = typeFor(event.getSource());
        if (type == null) return null;
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        if ("flight".equals(type)) {
            copy(event, fields, "aircraft.latitude_deg");
            copy(event, fields, "aircraft.longitude_deg");
            copy(event, fields, "aircraft.altitude_m");
            copy(event, fields, "heading_deg");
        } else if ("rtk".equals(type)) {
            copy(event, fields, "fusion.latitude_deg");
            copy(event, fields, "fusion.longitude_deg");
            copy(event, fields, "is_being_used");
        } else copy(event, fields, "attitude.pitch_deg");
        return telemetryRecord(type, event.getSessionId(), stream(type, event.getComponentIndex()),
                wireSequence, event.getReceivedElapsedRealtimeNanos(), fields);
    }

    private static byte[] telemetryRecord(String type, String session, String stream, long sequence,
            long receivedNanos, Map<String, Object> fields) {
        LinkedHashMap<String, Object> root = envelope(type, session, stream, sequence, receivedNanos);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("fields", fields);
        root.put("data", data);
        return JsonValueEncoder.toUtf8(root, TransportConfig.MAX_JSON_DATAGRAM_BYTES);
    }

    public static byte[] frameMetadata(String session, String feed, long frameSequence, int ssrc,
            long rtpTimestamp, long firstNanos, long completeNanos) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("v", TransportConfig.PROTOCOL_VERSION);
        root.put("type", "video_au");
        root.put("session", session);
        root.put("feed", "secondary".equals(feed) ? "fpv" : "primary");
        root.put("frame_seq", frameSequence);
        root.put("rtp_ssrc", Integer.toUnsignedLong(ssrc));
        root.put("rtp_ts", rtpTimestamp);
        root.put("au_first_byte_rx_mono_ns", firstNanos);
        root.put("au_complete_rx_mono_ns", Math.max(firstNanos, completeNanos));
        return JsonValueEncoder.toUtf8(root, TransportConfig.MAX_JSON_DATAGRAM_BYTES);
    }

    public static byte[] health(String session, long sequence, long nowNanos,
            long telemetryDrops, long telemetryErrors, long primaryVideoDrops,
            long secondaryVideoDrops, long videoSocketErrors, long primaryVideoCallbacks,
            long secondaryVideoCallbacks, long primaryAccessUnits, long secondaryAccessUnits,
            long primaryRtpPackets, long secondaryRtpPackets) {
        LinkedHashMap<String, Object> root = envelope("health", session, "health:0", sequence, nowNanos);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("primary_video_callbacks", primaryVideoCallbacks);
        data.put("secondary_video_callbacks", secondaryVideoCallbacks);
        data.put("primary_video_callback_drops", primaryVideoDrops);
        data.put("secondary_video_callback_drops", secondaryVideoDrops);
        data.put("primary_rtp_packets", primaryRtpPackets);
        data.put("secondary_rtp_packets", secondaryRtpPackets);
        data.put("primary_access_units", primaryAccessUnits);
        data.put("secondary_access_units", secondaryAccessUnits);
        data.put("telemetry_queue_drops", telemetryDrops);
        data.put("telemetry_socket_errors", telemetryErrors);
        data.put("video_socket_errors", videoSocketErrors);
        root.put("data", data);
        return JsonValueEncoder.toUtf8(root, TransportConfig.MAX_JSON_DATAGRAM_BYTES);
    }

    private static LinkedHashMap<String, Object> envelope(String type, String session, String stream,
            long sequence, long receivedNanos) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("v", TransportConfig.PROTOCOL_VERSION);
        root.put("type", type);
        root.put("session", session);
        root.put("stream", stream);
        root.put("seq", sequence);
        root.put("rx_mono_ns", receivedNanos);
        return root;
    }

    private static void copy(TelemetryEvent event, Map<String, Object> output, String key) {
        TelemetryField field = event.getFields().get(key);
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        boolean valid = field != null && field.isValid() && finite(field.getValue());
        value.put("value", valid ? field.getValue() : null);
        value.put("valid", valid);
        output.put(key, value);
    }

    private static boolean finite(Object value) {
        if (!(value instanceof Number)) return value != null;
        double number = ((Number) value).doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number);
    }

    private static String typeFor(String source) {
        if ("flight_controller".equals(source)) return "flight";
        if ("rtk".equals(source)) return "rtk";
        if ("gimbal".equals(source)) return "gimbal";
        return null;
    }

    private static String stream(String type, int componentIndex) {
        return type + ":" + componentIndex;
    }
}
