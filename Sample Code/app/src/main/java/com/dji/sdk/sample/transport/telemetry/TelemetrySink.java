package com.dji.sdk.sample.transport.telemetry;

import com.dji.sdk.sample.transport.model.TelemetryEvent;

/** Must return quickly; sinks performing I/O should enqueue bounded work. */
public interface TelemetrySink {
    void onTelemetry(TelemetryEvent event);
}
