package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;
import com.dji.sdk.sample.transport.network.VideoStreamSender;
import com.dji.sdk.sample.transport.network.BoundedUdpSender;
import com.dji.sdk.sample.transport.network.RtpSequenceWindowMetrics;
import com.dji.sdk.sample.operate.SessionTimeline;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.transport.telemetry.DjiTelemetryCollector;

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

    public static String primaryVideoDiagnostics(String sessionId,
            VideoStreamSender.Diagnostics diagnostics, long generatedElapsedRealtimeNanos) {
        com.dji.sdk.sample.transport.video.BoundedVideoIngestor.Counters ingestion =
                diagnostics.ingestion;
        com.dji.sdk.sample.transport.video.AnnexBParser.Counters parser = diagnostics.parser;
        long average = ingestion.acceptedChunks == 0 ? 0
                : ingestion.acceptedBytes / ingestion.acceptedChunks;
        StringBuilder out = new StringBuilder(1024).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "primary_video_diagnostics").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "generated_elapsed_realtime_ns", generatedElapsedRealtimeNanos).append(',');
        member(out, "feed", diagnostics.feed).append(',');
        member(out, "physical_source", diagnostics.physicalSource).append(',');
        member(out, "median_access_unit_interval_ns", diagnostics.medianAccessUnitIntervalNanos).append(',');
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
        member(out, "discontinuities", parser.discontinuities).append("},");
        out.append("\"socket\":{");
        member(out, "effective_send_buffer_bytes", diagnostics.effectiveSendBufferBytes).append(',');
        member(out, "metadata_attempts", diagnostics.socket.metadataAttempts).append(',');
        member(out, "metadata_accepted", diagnostics.socket.metadataAccepted).append(',');
        member(out, "metadata_accepted_bytes", diagnostics.socket.metadataAcceptedBytes).append(',');
        member(out, "rtp_attempts", diagnostics.socket.rtpAttempts).append(',');
        member(out, "rtp_accepted", diagnostics.socket.rtpAccepted).append(',');
        member(out, "rtp_accepted_bytes", diagnostics.socket.rtpAcceptedBytes).append(',');
        member(out, "metadata_errors", diagnostics.socket.metadataErrors).append(',');
        member(out, "rtp_errors", diagnostics.socket.rtpErrors).append(',');
        member(out, "last_metadata_accepted_ns", diagnostics.socket.lastMetadataAcceptedNanos).append(',');
        member(out, "last_rtp_accepted_ns", diagnostics.socket.lastRtpAcceptedNanos).append(',');
        member(out, "last_send_duration_ns", diagnostics.socket.lastSendDurationNanos).append(',');
        member(out, "last_metadata_error", diagnostics.socket.lastMetadataError).append(',');
        member(out, "last_rtp_error", diagnostics.socket.lastRtpError).append("},");
        out.append("\"worker\":{");
        member(out, "failures", diagnostics.workerFailures).append(',');
        member(out, "last_failure", diagnostics.lastWorkerFailure).append(',');
        member(out, "last_failure_ns", diagnostics.lastWorkerFailureNanos).append("}}");
        return out.toString();
    }

    public static String primaryTransportIncident(String sessionId, String state, String detail,
            long generatedElapsedRealtimeNanos) {
        StringBuilder out = new StringBuilder(384).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "primary_transport_incident").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "generated_elapsed_realtime_ns", generatedElapsedRealtimeNanos).append(',');
        member(out, "state", state).append(',');
        member(out, "detail", detail);
        return out.append('}').toString();
    }

    /** Compact local-only 1 Hz evidence used to locate a transport pause. */
    public static String transportPulse(String sessionId, String runtimeState, String endpoint,
            boolean foregroundServiceAlive, long foregroundLastReconcileNanos,
            long wallClockEpochMs, long nowNanos, long tickIntervalNanos,
            VideoStreamSender.Diagnostics primary, long videoMaxOfferNanos,
            long videoMaxSendNanos, RtpSequenceWindowMetrics.Snapshot rtpSequenceWindow,
            BoundedUdpSender.Snapshot telemetry,
            DjiDataHub.VideoDispatchSnapshot hubPrimary,
            DjiTelemetryCollector.FlightDiagnostics flight,
            AsyncNdjsonDiagnosticWriter.Snapshot writer) {
        long callbackAge = age(nowNanos, primary.lastCallbackNanos);
        long auAge = age(nowNanos, primary.lastAccessUnitNanos);
        long rtpAge = age(nowNanos, primary.socket.lastRtpAcceptedNanos);
        long metadataAge = age(nowNanos, primary.socket.lastMetadataAcceptedNanos);
        long telemetryAge = age(nowNanos, telemetry.lastAcceptedSendNanos);
        StringBuilder out = new StringBuilder(1800).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "transport_pulse").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "wall_clock_epoch_ms", wallClockEpochMs).append(',');
        member(out, "generated_elapsed_realtime_ns", nowNanos).append(',');
        member(out, "runtime_state", runtimeState).append(',');
        member(out, "endpoint", endpoint).append(',');
        member(out, "foreground_service_alive", foregroundServiceAlive).append(',');
        member(out, "foreground_last_reconcile_age_ns", age(nowNanos, foregroundLastReconcileNanos)).append(',');
        member(out, "rate_writer_interval_ns", tickIntervalNanos).append(',');
        out.append("\"diagnostic_writer\":{");
        member(out, "queue_depth", writer.queueDepth).append(',');
        member(out, "dropped_records", writer.droppedRecords).append(',');
        member(out, "written_records", writer.writtenRecords).append(',');
        member(out, "last_write_age_ns", age(nowNanos, writer.lastWriteElapsedRealtimeNanos)).append(',');
        member(out, "worker_alive", writer.workerAlive).append(',');
        member(out, "terminal_failure", writer.terminalFailure).append("},");
        out.append("\"hub_primary\":{");
        member(out, "ingress_callbacks", hubPrimary.ingressCallbacks).append(',');
        member(out, "last_ingress_age_ns", age(nowNanos, hubPrimary.lastIngressNanos)).append(',');
        member(out, "last_fanout_complete_age_ns", age(nowNanos, hubPrimary.lastFanoutCompleteNanos)).append(',');
        member(out, "fanout_last_ns", hubPrimary.lastFanoutDurationNanos).append(',');
        member(out, "fanout_max_ns", hubPrimary.maxFanoutDurationNanos).append("},");
        out.append("\"primary\":{");
        member(out, "dji_callbacks", primary.callbacks).append(',');
        member(out, "last_callback_age_ns", callbackAge).append(',');
        member(out, "offer_last_ns", primary.lastOfferDurationNanos).append(',');
        member(out, "offer_max_ns", videoMaxOfferNanos).append(',');
        member(out, "queue_depth", primary.ingestion.queueDepth).append(',');
        member(out, "queue_high_water", primary.ingestion.highWaterMark).append(',');
        member(out, "queue_oldest_age_ns", primary.ingestion.oldestQueueAgeNanos).append(',');
        member(out, "dropped_chunks", primary.ingestion.droppedChunks).append(',');
        member(out, "access_units", primary.accessUnits).append(',');
        member(out, "last_access_unit_age_ns", auAge).append(',');
        member(out, "rtp_accepted", primary.socket.rtpAccepted).append(',');
        member(out, "rtp_accepted_bytes", primary.socket.rtpAcceptedBytes).append(',');
        member(out, "last_rtp_accepted_age_ns", rtpAge).append(',');
        member(out, "rtp_errors", primary.socket.rtpErrors).append(',');
        member(out, "metadata_accepted", primary.socket.metadataAccepted).append(',');
        member(out, "metadata_accepted_bytes", primary.socket.metadataAcceptedBytes).append(',');
        member(out, "last_metadata_accepted_age_ns", metadataAge).append(',');
        member(out, "metadata_errors", primary.socket.metadataErrors).append(',');
        member(out, "udp_send_last_ns", primary.socket.lastSendDurationNanos).append(',');
        member(out, "udp_send_max_ns", videoMaxSendNanos).append("},");
        out.append("\"rtp_sequence_window\":{");
        member(out, "generated_first", rtpSequenceWindow.firstGeneratedSequence).append(',');
        member(out, "generated_last", rtpSequenceWindow.lastGeneratedSequence).append(',');
        member(out, "generated_packets", rtpSequenceWindow.generatedPackets).append(',');
        member(out, "generated_gaps", rtpSequenceWindow.generatedGaps).append(',');
        member(out, "accepted_first", rtpSequenceWindow.firstAcceptedSequence).append(',');
        member(out, "accepted_last", rtpSequenceWindow.lastAcceptedSequence).append(',');
        member(out, "accepted_packets", rtpSequenceWindow.acceptedPackets).append(',');
        member(out, "accepted_bytes", rtpSequenceWindow.acceptedBytes).append(',');
        member(out, "accepted_gaps", rtpSequenceWindow.acceptedGaps).append(',');
        member(out, "send_failures", rtpSequenceWindow.sendFailures).append("},");
        out.append("\"flight\":{");
        member(out, "callbacks", flight.callbacks).append(',');
        member(out, "last_callback_age_ns", age(nowNanos, flight.lastCallbackNanos)).append(',');
        member(out, "events_emitted", flight.eventsEmitted).append(',');
        member(out, "last_event_emitted_age_ns", age(nowNanos, flight.lastEventEmittedNanos)).append("},");
        out.append("\"telemetry\":{");
        member(out, "accepted", telemetry.acceptedDatagrams).append(',');
        member(out, "accepted_bytes", telemetry.acceptedBytes).append(',');
        member(out, "queue_depth", telemetry.queueDepth).append(',');
        member(out, "queue_high_water", telemetry.highWaterMark).append(',');
        member(out, "dropped", telemetry.dropped).append(',');
        member(out, "errors", telemetry.errors).append(',');
        member(out, "last_accepted_age_ns", telemetryAge).append(',');
        member(out, "udp_send_last_ns", telemetry.lastSendDurationNanos).append(',');
        member(out, "udp_send_max_ns", telemetry.maxSendDurationNanos).append("}");
        return out.append('}').toString();
    }

    public static String transportTransition(String sessionId, String type, String detail,
            long generatedElapsedRealtimeNanos) {
        StringBuilder out = new StringBuilder(384).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "transport_transition").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "generated_elapsed_realtime_ns", generatedElapsedRealtimeNanos).append(',');
        member(out, "type", type).append(',');
        member(out, "detail", detail);
        return out.append('}').toString();
    }

    private static long age(long nowNanos, long timestampNanos) {
        return timestampNanos <= 0L ? -1L : Math.max(0L, nowNanos - timestampNanos);
    }

    /** Local-only timeline evidence. It is not part of the Android-to-edge UDP contract. */
    public static String sessionTimelineEvent(String sessionId, SessionTimeline.Event event) {
        StringBuilder out = new StringBuilder(512).append('{');
        member(out, "schema_version", TelemetryEvent.SCHEMA_VERSION).append(',');
        member(out, "record_type", "session_timeline").append(',');
        member(out, "session_id", sessionId).append(',');
        member(out, "android_elapsed_ms", event.getElapsedRealtimeMs()).append(',');
        member(out, "time_source", event.getTimeSource().name()).append(',');
        member(out, "event_type", event.getType()).append(',');
        member(out, "detail", event.getDetail());
        return out.append('}').toString();
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
