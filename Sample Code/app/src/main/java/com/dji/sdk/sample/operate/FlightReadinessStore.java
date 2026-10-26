package com.dji.sdk.sample.operate;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.djihub.DjiHomeState;

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
    private volatile long aircraftSessionGeneration;
    private volatile Boolean lastRthActive;
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
                    aircraftSessionGeneration++;
                    session.beginNewAircraftSession(SystemClock.elapsedRealtime());
                    if (connected) {
                        SessionTimelineStore.android("AIRCRAFT_SESSION",
                                "New aircraft session observed; prior local evidence retained");
                    }
                    SessionTimelineStore.android("AIRCRAFT_LINK", connected
                            ? "Aircraft connection observed" : "Aircraft disconnection observed");
                    publishLatestEvent();
                    lastAircraftConnected = connected;
                }
                latestSnapshot = snapshot;
                FlightControllerState flight = snapshot.flightState;
                if (flight != null && (lastRthActive == null || lastRthActive != flight.isGoingHome())) {
                    boolean active = flight.isGoingHome();
                    SessionTimelineStore.dji("RTH_STATE", active ? "RTH active" : "RTH inactive");
                    lastRthActive = active;
                }
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

    /** Toggles only an operator acknowledgement; it never changes DJI flight-controller state. */
    public void toggleAcknowledgement(@NonNull FlightReadinessSession.ChecklistItem item) {
        long now = SystemClock.elapsedRealtime();
        if (session.getAcknowledgedItems().contains(item)) session.unacknowledge(item, now);
        else session.acknowledge(item, now);
        publishLatestEvent();
        publish();
    }

    @NonNull public CallbackMulticaster.Subscription addAuditListener(@NonNull AuditListener listener) {
        return auditListeners.add(listener);
    }

    @NonNull public FlightReadinessSession getSession() { return session; }
    /** Monotonic identity for app-local evidence tied to an observed aircraft link session. */
    public long getAircraftSessionGeneration() { return aircraftSessionGeneration; }
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
        DjiHomeState djiHome = snapshot.djiHome;
        RTKState rtk = snapshot.rtkState;
        BatteryState b1 = snapshot.aircraftBatteryStates.get(0);
        BatteryState b2 = snapshot.aircraftBatteryStates.get(1);
        int rcPercent = snapshot.remoteControllerBattery == null ? -1
                : snapshot.remoteControllerBattery.getRemainingChargeInPercent();
        int b1Percent = b1 == null ? -1 : b1.getChargeRemainingInPercent();
        int b2Percent = b2 == null ? -1 : b2.getChargeRemainingInPercent();
        FlightReadinessModel.Input input = new FlightReadinessModel.Input(
                snapshot.aircraftConnected,
                snapshot.remoteControllerConnected,
                // Link capability and downlink-quality samples are distinct. The latter is
                // presentation evidence; absence must not invent a disconnected OcuSync link.
                snapshot.ocuSyncAvailable,
                flight != null,
                djiHome != null && djiHome.isAvailable(),
                flight != null && flight.isGoingHome(),
                flight != null,
                rtk != null,
                rtk != null && String.valueOf(rtk.getPositioningSolution()).contains("FIX"),
                rtkRequired,
                safetyConfiguration.getReadbackState() == FlightSafetyConfiguration.ReadbackState.AVAILABLE,
                rcPercent, b1Percent, b2Percent, session.isComplete());
        return FlightReadinessModel.evaluate(input.withEvidence(
                snapshot.ocuSyncAvailable ? FlightReadinessModel.EvidenceState.LIVE
                        : FlightReadinessModel.EvidenceState.UNAVAILABLE,
                OperateStatusModel.evidence(snapshot.flightStateElapsedMs, nowMs, flight != null),
                OperateStatusModel.evidence(snapshot.flightStateElapsedMs, nowMs, flight != null),
                OperateStatusModel.evidence(snapshot.rtkStateElapsedMs, nowMs, rtk != null),
                OperateStatusModel.evidence(snapshot.remoteControllerBatteryElapsedMs, nowMs,
                        snapshot.remoteControllerBattery != null),
                OperateStatusModel.evidence(snapshot.aircraftBatteryElapsedMs.containsKey(0)
                        ? snapshot.aircraftBatteryElapsedMs.get(0) : -1L, nowMs, b1 != null),
                OperateStatusModel.evidence(snapshot.aircraftBatteryElapsedMs.containsKey(1)
                        ? snapshot.aircraftBatteryElapsedMs.get(1) : -1L, nowMs, b2 != null))
                .withDjiHomeEvidence(homeEvidence(djiHome, nowMs)));
    }

    private static FlightReadinessModel.EvidenceState homeEvidence(DjiHomeState home, long nowMs) {
        if (home == null || home.getAvailability() == DjiHomeState.Availability.WAITING_FOR_SAMPLE) {
            return FlightReadinessModel.EvidenceState.WAITING_FOR_SAMPLE;
        }
        if (!home.isAvailable()) return FlightReadinessModel.EvidenceState.UNAVAILABLE;
        return OperateStatusModel.evidence(home.getObservedElapsedMs(), nowMs, true);
    }

    private void publish() {
        FlightReadinessModel.Decision decision = currentDecision();
        listeners.dispatch(listener -> listener.onReadinessChanged(decision));
    }

    private void publishLatestEvent() {
        java.util.List<FlightReadinessSession.Event> events = session.getRecentEvents();
        if (events.isEmpty()) return;
        FlightReadinessSession.Event event = events.get(events.size() - 1);
        String item = event.getItem() == null ? "" : ": " + event.getItem().getLabel();
        SessionTimelineStore.android("READINESS_" + event.getType().toUpperCase(),
                event.getType() + item);
        auditListeners.dispatch(listener -> listener.onReadinessEvent(event));
    }
}
