package com.dji.sdk.sample.transport.telemetry;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.diagnostics.CallbackRateRegistry;
import com.dji.sdk.sample.transport.model.TelemetryEvent;
import com.dji.sdk.sample.transport.model.TelemetryField;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.RTKState;
import dji.common.gimbal.GimbalState;
import dji.common.model.LocationCoordinate2D;

/** Passive hub subscriber that emits only the navigation fields in the Edge contract. */
public final class DjiTelemetryCollector implements DjiDataHub.Listener {
    public static final String SOURCE_FLIGHT_CONTROLLER = "flight_controller";
    public static final String SOURCE_RTK = "rtk";
    public static final String SOURCE_GIMBAL = "gimbal";

    private final String sessionId;
    private final MonotonicClock clock;
    private final TelemetrySink sink;
    private final CallbackRateRegistry rateRegistry;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private CallbackMulticaster.Subscription subscription;
    private boolean running;

    public DjiTelemetryCollector(String sessionId, MonotonicClock clock, TelemetrySink sink,
                                 CallbackRateRegistry rateRegistry) {
        this.sessionId = sessionId;
        this.clock = clock;
        this.sink = sink;
        this.rateRegistry = rateRegistry;
    }

    /** Subscribes to the hub; this collector never owns DJI setter callbacks. */
    public synchronized void start(@NonNull DjiDataHub hub) {
        if (running) return;
        subscription = hub.addListener(this);
        running = true;
    }

    /** Removes only this transport subscription. The hub remains available to visible screens. */
    public synchronized void stop() {
        if (!running) return;
        if (subscription != null) subscription.close();
        subscription = null;
        running = false;
    }

    public synchronized boolean isRunning() { return running; }

    @Override public void onFlightState(@NonNull FlightControllerState state) {
        Map<String, TelemetryField> fields = new LinkedHashMap<>();
        LocationCoordinate3D location = state.getAircraftLocation();
        boolean positionValid = location != null && LocationCoordinate2D.isValid(
                location.getLatitude(), location.getLongitude());
        put(fields, "aircraft.latitude_deg", location == null ? null : location.getLatitude(), positionValid);
        put(fields, "aircraft.longitude_deg", location == null ? null : location.getLongitude(), positionValid);
        put(fields, "aircraft.altitude_m", location == null ? null : location.getAltitude(),
                location != null && finite(location.getAltitude()));
        put(fields, "heading_deg", state.getAircraftHeadDirection(), finite(state.getAircraftHeadDirection()));
        publish(SOURCE_FLIGHT_CONTROLLER, 0, clock.nowNanos(), fields);
    }

    @Override public void onRtkState(@NonNull RTKState state) {
        Map<String, TelemetryField> fields = new LinkedHashMap<>();
        LocationCoordinate2D fusion = state.getFusionMobileStationLocation();
        boolean positionValid = fusion != null && fusion.isValid();
        put(fields, "fusion.latitude_deg", fusion == null ? null : fusion.getLatitude(), positionValid);
        put(fields, "fusion.longitude_deg", fusion == null ? null : fusion.getLongitude(), positionValid);
        put(fields, "is_being_used", state.isRTKBeingUsed(), true);
        publish(SOURCE_RTK, 0, clock.nowNanos(), fields);
    }

    @Override public void onGimbalState(int index, @NonNull GimbalState state) {
        Map<String, TelemetryField> fields = new LinkedHashMap<>();
        dji.common.gimbal.Attitude attitude = state.getAttitudeInDegrees();
        put(fields, "attitude.pitch_deg", attitude == null ? null : attitude.getPitch(),
                attitude != null && finite(attitude.getPitch()));
        publish(SOURCE_GIMBAL, index, clock.nowNanos(), fields);
    }

    private void publish(String source, int index, long timestampNanos,
                         Map<String, TelemetryField> fields) {
        rateRegistry.record(source, index, timestampNanos);
        sink.onTelemetry(new TelemetryEvent(sessionId, source, index,
                sequence(source, index), timestampNanos, fields));
    }

    private long sequence(String source, int index) {
        String key = source + ":" + index;
        return sequences.computeIfAbsent(key, ignored -> new AtomicLong()).getAndIncrement();
    }

    private static void put(Map<String, TelemetryField> fields, String key, Object value,
                            boolean valid) {
        fields.put(key, new TelemetryField(value, valid, "dji", 0));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
