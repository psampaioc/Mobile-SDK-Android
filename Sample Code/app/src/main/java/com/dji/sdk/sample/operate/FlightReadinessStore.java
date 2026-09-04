package com.dji.sdk.sample.operate;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;

import dji.common.battery.BatteryState;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.RTKState;

/**
 * Process-local bridge from the hub's passive snapshots to the shared readiness decision.
 * It subscribes to DjiDataHub only; it never attaches a DJI callback itself.
 */
public final class FlightReadinessStore {
    private static final FlightReadinessStore INSTANCE = new FlightReadinessStore();
    private final FlightReadinessSession session = new FlightReadinessSession(64);
    private final FlightSafetyConfiguration safetyConfiguration = FlightSafetyConfiguration.unvalidated();
    private final CallbackMulticaster<Listener> listeners = new CallbackMulticaster<>();
    private final CallbackMulticaster<AuditListener> auditListeners = new CallbackMulticaster<>();
    private volatile DjiDataHub.StatusSnapshot latestSnapshot;
    private volatile boolean lastAircraftConnected;
    private CallbackMulticaster.Subscription hubSubscription;

    public interface Listener {
        void onReadinessChanged(@NonNull FlightReadinessModel.Decision decision);
    }
    public interface AuditListener {
        void onReadinessEvent(@NonNull FlightReadinessSession.Event event);
    }

    private FlightReadinessStore() { }

    @NonNull public static FlightReadinessStore getInstance() { return INSTANCE; }

    /** Safe to call from any visible consumer; one hub subscription is retained for the process. */
    public synchronized void start() {
        if (hubSubscription != null) return;
        hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
            @Override public void onStatusSnapshot(@NonNull DjiDataHub.StatusSnapshot snapshot) {
                boolean connected = snapshot.aircraftConnected;
                if (connected != lastAircraftConnected) {
                    session.beginNewAircraftSession(SystemClock.elapsedRealtime());
                    publishLatestEvent();
                    lastAircraftConnected = connected;
                }
                latestSnapshot = snapshot;
                publish();
            }
        });
    }

    @NonNull public CallbackMulticaster.Subscription addListener(@NonNull Listener listener) {
        return listeners.add(listener);
    }

    public void acknowledge(@NonNull FlightReadinessSession.ChecklistItem item) {
        session.acknowledge(item, SystemClock.elapsedRealtime());
        publishLatestEvent();
        publish();
    }

    @NonNull public CallbackMulticaster.Subscription addAuditListener(@NonNull AuditListener listener) {
        return auditListeners.add(listener);
    }

    @NonNull public FlightReadinessSession getSession() { return session; }
    @NonNull public FlightSafetyConfiguration getSafetyConfiguration() { return safetyConfiguration; }

    @NonNull public FlightReadinessModel.Decision currentDecision() {
        return decisionFor(SystemClock.elapsedRealtime(), false);
    }

    @NonNull public FlightReadinessModel.Decision decisionFor(long nowMs, boolean rtkRequired) {
        DjiDataHub.StatusSnapshot snapshot = latestSnapshot;
        if (snapshot == null) return FlightReadinessModel.evaluate(new FlightReadinessModel.Input(
                false, false, false, false, false, false, false, false, false, rtkRequired,
                false, -1, -1, -1, session.isComplete()));
        FlightControllerState flight = snapshot.flightState;
        RTKState rtk = snapshot.rtkState;
        BatteryState b1 = snapshot.aircraftBatteryStates.get(0);
        BatteryState b2 = snapshot.aircraftBatteryStates.get(1);
        int rcPercent = snapshot.remoteControllerBattery == null ? -1
                : snapshot.remoteControllerBattery.getRemainingChargeInPercent();
        return FlightReadinessModel.evaluate(new FlightReadinessModel.Input(
                snapshot.aircraftConnected,
                snapshot.remoteControllerConnected,
                fresh(snapshot.ocuSyncElapsedMs, nowMs, snapshot.ocuSyncAvailable),
                fresh(snapshot.flightStateElapsedMs, nowMs, flight != null),
                flight != null && flight.isHomeLocationSet(),
                flight != null && flight.isGoingHome(),
                fresh(snapshot.flightStateElapsedMs, nowMs, flight != null),
                fresh(snapshot.rtkStateElapsedMs, nowMs, rtk != null),
                rtk != null && String.valueOf(rtk.getPositioningSolution()).contains("FIX"),
                rtkRequired,
                safetyConfiguration.getReadbackState() == FlightSafetyConfiguration.ReadbackState.AVAILABLE,
                rcPercent,
                b1 == null ? -1 : b1.getChargeRemainingInPercent(),
                b2 == null ? -1 : b2.getChargeRemainingInPercent(),
                session.isComplete()));
    }

    private static boolean fresh(long receivedAtMs, long nowMs, boolean supported) {
        return OperateStatusModel.freshness(receivedAtMs, nowMs, supported)
                == OperateStatusModel.Severity.HEALTHY;
    }

    private void publish() {
        FlightReadinessModel.Decision decision = currentDecision();
        listeners.dispatch(listener -> listener.onReadinessChanged(decision));
    }

    private void publishLatestEvent() {
        java.util.List<FlightReadinessSession.Event> events = session.getRecentEvents();
        if (events.isEmpty()) return;
        FlightReadinessSession.Event event = events.get(events.size() - 1);
        auditListeners.dispatch(listener -> listener.onReadinessEvent(event));
    }
}
