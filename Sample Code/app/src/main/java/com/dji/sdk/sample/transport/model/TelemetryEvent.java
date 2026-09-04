package com.dji.sdk.sample.transport.model;

import com.dji.sdk.sample.transport.diagnostics.JsonEncoder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable telemetry sample captured from one DJI callback invocation. */
public final class TelemetryEvent {
    public static final int SCHEMA_VERSION = 1;

    private final String sessionId;
    private final String source;
    private final int componentIndex;
    private final long sequence;
    private final long receivedElapsedRealtimeNanos;
    private final Map<String, TelemetryField> fields;

    public TelemetryEvent(String sessionId, String source, int componentIndex, long sequence,
                          long receivedElapsedRealtimeNanos, Map<String, TelemetryField> fields) {
        this.sessionId = sessionId;
        this.source = source;
        this.componentIndex = componentIndex;
        this.sequence = sequence;
        this.receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public String getSessionId() { return sessionId; }
    public String getSource() { return source; }
    public int getComponentIndex() { return componentIndex; }
    public long getSequence() { return sequence; }
    public long getReceivedElapsedRealtimeNanos() { return receivedElapsedRealtimeNanos; }
    public Map<String, TelemetryField> getFields() { return fields; }

    public String toNdjsonLine() {
        return JsonEncoder.telemetryEvent(this);
    }
}
