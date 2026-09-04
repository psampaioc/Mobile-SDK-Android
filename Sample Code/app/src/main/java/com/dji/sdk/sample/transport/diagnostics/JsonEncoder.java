package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;
import com.dji.sdk.sample.transport.network.VideoStreamSender;

import java.util.Map;

/** Small dependency-free JSON encoder for the fixed telemetry schema. */
public final class JsonEncoder {
    private JsonEncoder() { }

    public static String telemetryEvent(TelemetryEvent event) {
        StringBuilder out = new StringBuilder(2048);
        out.append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "telemetry").append(',');
        member(out, "session_id", event.getSessionId()).append(',');
        member(out, "source", event.getSource()).append(',');
        member(out, "component_index", event.getComponentIndex()).append(',');
        member(out, "sequence", event.getSequence()).append(',');
        member(out, "received_elapsed_realtime_ns", event.getReceivedElapsedRealtimeNanos()).append(',');
        out.append("\"fields\":{");
        boolean first = true;
        for (Map.Entry<String, TelemetryField> item : event.getFields().entrySet()) {
            if (!first) out.append(',');
            first = false;
            quote(out, item.getKey()).append(':');
            field(out, item.getValue());
        }
        return out.append("}}").toString();
    }

    public static String rateSnapshot(String sessionId, CallbackRateSnapshot snapshot,
                                      long generatedElapsedRealtimeNanos) {
        StringBuilder out = new StringBuilder(512).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "callback_rate").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "source_key", snapshot.getSourceKey()).append(',');
        member(out, "generated_elapsed_realtime_ns", generatedElapsedRealtimeNanos).append(',');
        member(out, "callback_count", snapshot.getCallbackCount()).append(',');
        member(out, "sampled_interval_count", snapshot.getSampledIntervalCount()).append(',');
        member(out, "mean_hz", snapshot.getMeanHz()).append(',');
        member(out, "median_interval_ns", snapshot.getMedianIntervalNanos()).append(',');
        member(out, "p95_interval_ns", snapshot.getP95IntervalNanos()).append(',');
        member(out, "p99_interval_ns", snapshot.getP99IntervalNanos()).append(',');
        member(out, "max_interval_ns", snapshot.getMaxIntervalNanos()).append(',');
        member(out, "non_monotonic_count", snapshot.getNonMonotonicCount());
        return out.append('}').toString();
    }

    public static String secondaryVideoDiagnostics(String sessionId,
            VideoStreamSender.Diagnostics diagnostics, long generatedElapsedRealtimeNanos) {
        com.dji.sdk.sample.transport.video.BoundedVideoIngestor.Counters ingestion =
                diagnostics.ingestion;
        com.dji.sdk.sample.transport.video.AnnexBParser.Counters parser = diagnostics.parser;
        long average = ingestion.acceptedChunks == 0 ? 0
                : ingestion.acceptedBytes / ingestion.acceptedChunks;
        StringBuilder out = new StringBuilder(1024).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "secondary_video_diagnostics").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "generated_elapsed_realtime_ns", generatedElapsedRealtimeNanos).append(',');
        member(out, "feed", diagnostics.feed).append(',');
        member(out, "physical_source", diagnostics.physicalSource).append(',');
        out.append("\"ingestion\":{");
        member(out, "callback_bytes_total", ingestion.acceptedBytes).append(',');
        member(out, "callback_bytes_min", ingestion.minimumChunkBytes).append(',');
        member(out, "callback_bytes_avg", average).append(',');
        member(out, "callback_bytes_max", ingestion.maximumChunkBytes).append(',');
        member(out, "annexb_signature_3", ingestion.annexBThreeByteSignatures).append(',');
        member(out, "annexb_signature_4", ingestion.annexBFourByteSignatures).append(',');
        member(out, "initial_callback_hex", ingestion.initialHexSamples).append("},");
        out.append("\"parser\":{");
        member(out, "format", parser.detectedFormat).append(',');
        member(out, "annexb_start_codes_3", parser.annexBThreeByteStartCodes).append(',');
        member(out, "annexb_start_codes_4", parser.annexBFourByteStartCodes).append(',');
        member(out, "avcc_candidates", parser.lengthPrefixCandidates).append(',');
        member(out, "invalid_length_prefixes", parser.invalidLengthPrefixes).append(',');
        member(out, "resets", parser.parserResets).append(',');
        member(out, "nal_units", parser.emittedNalUnits).append(',');
        member(out, "vcl_nals", parser.vclNalUnits).append(',');
        member(out, "sps_nals", parser.spsNalUnits).append(',');
        member(out, "pps_nals", parser.ppsNalUnits).append(',');
        member(out, "idr_nals", parser.idrNalUnits).append(',');
        member(out, "malformed_nals", parser.malformedNalUnits).append(',');
        member(out, "discontinuities", parser.discontinuities).append("}}");
        return out.toString();
    }

    private static void field(StringBuilder out, TelemetryField field) {
        out.append('{');
        out.append("\"value\":"); value(out, field.getValue()); out.append(',');
        member(out, "valid", field.isValid()).append(',');
        member(out, "source", field.getSource()).append(',');
        member(out, "component_index", field.getComponentIndex());
        out.append('}');
    }

    private static StringBuilder member(StringBuilder out, String key, Object value) {
        quote(out, key).append(':');
        value(out, value);
        return out;
    }

    private static void value(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) out.append("null");
            else out.append(value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else {
            quote(out, String.valueOf(value));
        }
    }

    private static StringBuilder quote(StringBuilder out, String value) {
        out.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                }
            }
        }
        return out.append('"');
    }
}
