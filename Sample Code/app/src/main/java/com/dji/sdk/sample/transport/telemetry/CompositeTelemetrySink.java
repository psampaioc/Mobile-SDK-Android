package com.dji.sdk.sample.transport.telemetry;

import com.dji.sdk.sample.transport.model.TelemetryEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fans telemetry out to independent diagnostics and network sinks. */
public final class CompositeTelemetrySink implements TelemetrySink {
    private final List<TelemetrySink> sinks;

    public CompositeTelemetrySink(TelemetrySink... sinks) {
        this.sinks = Collections.unmodifiableList(Arrays.asList(sinks.clone()));
    }

    @Override
    public void onTelemetry(TelemetryEvent event) {
        for (TelemetrySink sink : sinks) {
            if (sink == null) continue;
            try {
                sink.onTelemetry(event);
            } catch (RuntimeException ignored) {
                // One observer must not prevent delivery to the remaining observers.
            }
        }
    }
}
