package com.dji.sdk.sample.djihub;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dji.common.airlink.PhysicalSource;
import dji.common.battery.BatteryState;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.RTKState;
import dji.common.gimbal.GimbalState;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks;
import dji.common.remotecontroller.HardwareState;
import dji.sdk.battery.Battery;
import dji.sdk.airlink.AirLink;
import dji.sdk.airlink.OcuSyncLink;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.flightcontroller.RTK;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;

/**
 * The sole process-wide owner of DJI callback registration for data consumed by this app.
 * UI and transport subscribe below this boundary; they never call a DJI setter callback directly.
 */
public final class DjiDataHub {
    public enum Feed { PRIMARY, SECONDARY }

    /** Explicit flight-control action; it never registers or replaces a DJI callback. */
    public boolean setDjiHomeLocation(@NonNull LocationCoordinate2D location,
            @NonNull CommonCallbacks.CompletionCallback callback) {
        FlightController current;
        synchronized (statusLock) { current = flightController; }
        if (current == null || !location.isValid()) return false;
        current.setHomeLocation(location, error -> {
            if (error == null) synchronized (statusLock) {
                confirmedDjiHome = new LocationCoordinate2D(location.getLatitude(), location.getLongitude());
                confirmedDjiHomeElapsedMs = SystemClock.elapsedRealtime();
            }
            callback.onResult(error);
        });
        return true;
    }

    /** Last successful explicit Home command for this connected-aircraft session. */
    public LocationCoordinate2D getConfirmedDjiHomeLocation() {
        synchronized (statusLock) {
            return confirmedDjiHome == null ? null
                    : new LocationCoordinate2D(confirmedDjiHome.getLatitude(), confirmedDjiHome.getLongitude());
        }
    }

    public interface Listener {
        default void onVideo(@NonNull Feed feed, byte[] data, int size, @NonNull String source) { }
        default void onFeedAvailability(@NonNull Feed feed, boolean available) { }
        default void onPhysicalSource(@NonNull Feed feed, @NonNull PhysicalSource source) { }
        default void onFlightState(@NonNull FlightControllerState state) { }
        default void onRtkState(@NonNull RTKState state) { }
        default void onGimbalState(int index, @NonNull GimbalState state) { }
        default void onBatteryState(int index, @NonNull BatteryState state) { }
        /** Observational RC state only. This hub never consumes or changes RC commands. */
        default void onRemoteControllerState(@NonNull HardwareState state) { }
        /** Passive Cendence charge state. This is distinct from aircraft Intelligent Flight Batteries. */
        default void onRemoteControllerBattery(@NonNull dji.common.remotecontroller.BatteryState state) { }
        /** Downlink quality reported by the DJI OcuSync link, from 0 through 100. */
        default void onOcuSyncDownlinkSignalQuality(int quality) { }
        /** Whether the currently connected product exposes a usable OcuSync link capability. */
        default void onOcuSyncAvailability(boolean available) { }
        /** Immutable, timestamped passive status sample for consumers that render an aggregate. */
        default void onStatusSnapshot(@NonNull StatusSnapshot snapshot) { }
    }

    /**
     * A coherent point-in-time copy of passive operational status. It carries no flight command
     * capability and is deliberately delivered only when DJI produces a new event.
     */
    public static final class StatusSnapshot {
        public final boolean aircraftConnected;
        public final boolean remoteControllerConnected;
        public final boolean ocuSyncAvailable;
        public final int ocuSyncDownlinkQuality;
        public final FlightControllerState flightState;
        public final RTKState rtkState;
        public final Map<Integer, GimbalState> gimbalStates;
        public final dji.common.remotecontroller.BatteryState remoteControllerBattery;
        public final Map<Integer, BatteryState> aircraftBatteryStates;
        public final long flightStateElapsedMs;
        public final long rtkStateElapsedMs;
        public final long gimbalStateElapsedMs;
        public final long remoteControllerBatteryElapsedMs;
        public final long ocuSyncElapsedMs;
        public final Map<Integer, Long> aircraftBatteryElapsedMs;

        private StatusSnapshot(boolean aircraftConnected, boolean remoteControllerConnected,
                boolean ocuSyncAvailable, int ocuSyncDownlinkQuality,
                FlightControllerState flightState, RTKState rtkState,
                Map<Integer, GimbalState> gimbalStates,
                dji.common.remotecontroller.BatteryState remoteControllerBattery,
                Map<Integer, BatteryState> aircraftBatteryStates, long flightStateElapsedMs,
                long rtkStateElapsedMs, long gimbalStateElapsedMs,
                long remoteControllerBatteryElapsedMs, long ocuSyncElapsedMs,
                Map<Integer, Long> aircraftBatteryElapsedMs) {
            this.aircraftConnected = aircraftConnected;
            this.remoteControllerConnected = remoteControllerConnected;
            this.ocuSyncAvailable = ocuSyncAvailable;
            this.ocuSyncDownlinkQuality = ocuSyncDownlinkQuality;
            this.flightState = flightState;
            this.rtkState = rtkState;
            this.gimbalStates = Collections.unmodifiableMap(new LinkedHashMap<>(gimbalStates));
            this.remoteControllerBattery = remoteControllerBattery;
            this.aircraftBatteryStates = Collections.unmodifiableMap(
                    new LinkedHashMap<>(aircraftBatteryStates));
            this.flightStateElapsedMs = flightStateElapsedMs;
            this.rtkStateElapsedMs = rtkStateElapsedMs;
            this.gimbalStateElapsedMs = gimbalStateElapsedMs;
            this.remoteControllerBatteryElapsedMs = remoteControllerBatteryElapsedMs;
            this.ocuSyncElapsedMs = ocuSyncElapsedMs;
            this.aircraftBatteryElapsedMs = Collections.unmodifiableMap(
                    new LinkedHashMap<>(aircraftBatteryElapsedMs));
        }
    }

    private static final DjiDataHub INSTANCE = new DjiDataHub();
    private final CallbackMulticaster<Listener> listeners = new CallbackMulticaster<>();
    private final Object statusLock = new Object();
    private LocationCoordinate2D confirmedDjiHome;
    private long confirmedDjiHomeElapsedMs;
    private final Map<Integer, Gimbal> gimbals = new LinkedHashMap<>();
    private final Map<Integer, Battery> batteries = new LinkedHashMap<>();
    private Aircraft aircraft;
    private FlightController flightController;
    private RTK rtk;
    private RemoteController remoteController;
    private OcuSyncLink ocuSyncLink;
    private VideoFeeder.VideoFeed primaryFeed;
    private VideoFeeder.VideoFeed secondaryFeed;
    private VideoFeeder boundFeeder;
    private VideoFeeder.VideoDataListener primaryVideoListener;
    private VideoFeeder.VideoDataListener secondaryVideoListener;
    private VideoFeeder.PhysicalSourceListener physicalSourceListener;
    private volatile String primarySource = "UNKNOWN";
    private volatile String secondarySource = "UNKNOWN";
    private volatile boolean primaryAvailable;
    private volatile boolean secondaryAvailable;
    private FlightControllerState latestFlightState;
    private RTKState latestRtkState;
    private final Map<Integer, GimbalState> latestGimbalStates = new LinkedHashMap<>();
    private dji.common.remotecontroller.BatteryState latestRemoteControllerBattery;
    private final Map<Integer, BatteryState> latestAircraftBatteryStates = new LinkedHashMap<>();
    private final Map<Integer, Long> latestAircraftBatteryElapsedMs = new LinkedHashMap<>();
    private int latestOcuSyncDownlinkQuality = -1;
    private long latestFlightStateElapsedMs = -1L;
    private long latestRtkStateElapsedMs = -1L;
    private long latestGimbalStateElapsedMs = -1L;
    private long latestRemoteControllerBatteryElapsedMs = -1L;
    private long latestOcuSyncElapsedMs = -1L;

    private DjiDataHub() { }

    public static DjiDataHub getInstance() { return INSTANCE; }

    public CallbackMulticaster.Subscription addListener(@NonNull Listener listener) {
        // DJI state callbacks recur. Avoid replaying a stale snapshot after a newly delivered
        // native event; subscribers begin at the next ordered callback instead.
        return listeners.add(listener);
    }

    /** Starts or refreshes ownership for the currently connected product. Safe to call repeatedly. */
    public synchronized void start(@NonNull Aircraft nextAircraft) {
        if (aircraft != nextAircraft) {
            detachLocked();
            aircraft = nextAircraft;
        }
        attachVideoLocked();
        attachTelemetryLocked();
        attachRemoteControllerLocked();
        attachAirLinkLocked();
        refreshComponentsLocked();
        publishStatusSnapshot();
    }

    /** Reconciles every owned binding after product or component connectivity changes. */
    public synchronized void refresh(@NonNull Aircraft expectedAircraft) {
        if (aircraft == expectedAircraft) {
            attachVideoLocked();
            attachTelemetryLocked();
            attachRemoteControllerLocked();
            attachAirLinkLocked();
            refreshComponentsLocked();
            publishStatusSnapshot();
        }
    }

    /** Clears only callbacks and listeners installed by this hub. */
    public synchronized void stop() { detachLocked(); }

    public synchronized String getSource(@NonNull Feed feed) {
        return feed == Feed.PRIMARY ? primarySource : secondarySource;
    }

    public boolean isAvailable(@NonNull Feed feed) {
        return feed == Feed.PRIMARY ? primaryAvailable : secondaryAvailable;
    }

    private void attachVideoLocked() {
        VideoFeeder feeder = VideoFeeder.getInstance();
        if (feeder == null) {
            publishAvailability(Feed.PRIMARY, false);
            publishAvailability(Feed.SECONDARY, false);
            return;
        }
        if (boundFeeder != feeder) {
            if (boundFeeder != null && physicalSourceListener != null) {
                boundFeeder.removePhysicalSourceListener(physicalSourceListener);
            }
            boundFeeder = feeder;
            physicalSourceListener = null;
        }
        VideoFeeder.VideoFeed primary = feeder.getPrimaryVideoFeed();
        VideoFeeder.VideoFeed secondary = feeder.getSecondaryVideoFeed();
        if (primaryFeed != primary && primaryVideoListener != null && primaryFeed != null) {
            primaryFeed.removeVideoDataListener(primaryVideoListener);
            primaryVideoListener = null;
            primaryFeed = null;
        }
        if (secondaryFeed != secondary && secondaryVideoListener != null && secondaryFeed != null) {
            secondaryFeed.removeVideoDataListener(secondaryVideoListener);
            secondaryVideoListener = null;
            secondaryFeed = null;
        }
        if (primary != null && primaryVideoListener == null) {
            primaryFeed = primary;
            primarySource = String.valueOf(primary.getVideoSource());
            primaryVideoListener = (data, size) -> listeners.dispatch(listener ->
                    listener.onVideo(Feed.PRIMARY, data, size, primarySource));
            primary.addVideoDataListener(primaryVideoListener);
        }
        if (secondary != null && secondaryVideoListener == null) {
            secondaryFeed = secondary;
            secondarySource = String.valueOf(secondary.getVideoSource());
            secondaryVideoListener = (data, size) -> listeners.dispatch(listener ->
                    listener.onVideo(Feed.SECONDARY, data, size, secondarySource));
            secondary.addVideoDataListener(secondaryVideoListener);
        }
        if (physicalSourceListener == null) {
            physicalSourceListener = (feed, source) -> {
                Feed target;
                if (feed == primaryFeed) target = Feed.PRIMARY;
                else if (feed == secondaryFeed) target = Feed.SECONDARY;
                else return;
                if (target == Feed.PRIMARY) primarySource = String.valueOf(source);
                else secondarySource = String.valueOf(source);
                listeners.dispatch(listener -> listener.onPhysicalSource(target, source));
            };
            feeder.addPhysicalSourceListener(physicalSourceListener);
        }
        publishAvailability(Feed.PRIMARY, primaryFeed != null);
        publishAvailability(Feed.SECONDARY, secondaryFeed != null);
    }

    private void attachTelemetryLocked() {
        FlightController currentFlightController = aircraft == null ? null : aircraft.getFlightController();
        if (currentFlightController != flightController) {
            if (flightController != null) flightController.setStateCallback(null);
            if (rtk != null) rtk.setStateCallback(null);
            flightController = currentFlightController;
            rtk = null;
            clearFlightAndRtkStatus();
            if (flightController != null) flightController.setStateCallback(state -> {
                if (flightController != currentFlightController) return;
                listeners.dispatch(listener -> listener.onFlightState(state));
                noteFlightState(state);
            });
        }
        RTK currentRtk = flightController == null ? null : flightController.getRTK();
        if (currentRtk != rtk) {
            if (rtk != null) rtk.setStateCallback(null);
            rtk = currentRtk;
            if (rtk != null) rtk.setStateCallback(state -> {
                if (rtk != currentRtk) return;
                listeners.dispatch(listener -> listener.onRtkState(state));
                noteRtkState(state);
            });
        }
    }

    private void refreshComponentsLocked() {
        if (aircraft == null) return;
        reconcileGimbals(aircraft.getGimbals());
        reconcileBatteries(aircraft.getBatteries());
    }

    private void attachRemoteControllerLocked() {
        RemoteController current = aircraft == null ? null : aircraft.getRemoteController();
        if (current == remoteController) return;
        if (remoteController != null) {
            remoteController.setHardwareStateCallback(null);
            remoteController.setChargeRemainingCallback(null);
        }
        remoteController = current;
        clearRemoteControllerBatteryStatus();
        if (remoteController != null) {
            remoteController.setHardwareStateCallback(state -> {
                if (remoteController != current) return;
                listeners.dispatch(listener -> listener.onRemoteControllerState(state));
            });
            remoteController.setChargeRemainingCallback(state -> {
                if (remoteController != current) return;
                listeners.dispatch(listener -> listener.onRemoteControllerBattery(state));
                noteRemoteControllerBattery(state);
            });
        }
    }

    private void attachAirLinkLocked() {
        AirLink airLink = aircraft == null ? null : aircraft.getAirLink();
        OcuSyncLink current = airLink != null && airLink.isOcuSyncLinkSupported()
                ? airLink.getOcuSyncLink() : null;
        if (current == ocuSyncLink) {
            listeners.dispatch(listener -> listener.onOcuSyncAvailability(current != null));
            return;
        }
        if (ocuSyncLink != null) ocuSyncLink.setDownlinkSignalQualityCallback(null);
        ocuSyncLink = current;
        if (ocuSyncLink != null) {
            ocuSyncLink.setDownlinkSignalQualityCallback(quality -> {
                if (ocuSyncLink != current) return;
                listeners.dispatch(listener -> listener.onOcuSyncDownlinkSignalQuality(quality));
                noteOcuSyncQuality(quality);
            });
        }
        listeners.dispatch(listener -> listener.onOcuSyncAvailability(current != null));
        noteOcuSyncAvailability(current != null);
    }

    private void reconcileGimbals(List<Gimbal> connected) {
        Map<Integer, Gimbal> current = new LinkedHashMap<>();
        if (connected != null) for (Gimbal gimbal : connected) {
            if (gimbal == null || !gimbal.isConnected()) continue;
            int index = gimbal.getIndex();
            current.put(index, gimbal);
            if (gimbals.get(index) == gimbal) continue;
            Gimbal replaced = gimbals.put(index, gimbal);
            if (replaced != null) replaced.setStateCallback(null);
            clearGimbalStatus(index);
            gimbal.setStateCallback(state -> {
                if (gimbals.get(index) != gimbal) return;
                listeners.dispatch(listener -> listener.onGimbalState(index, state));
                noteGimbalState(index, state);
            });
        }
        for (Integer index : new java.util.ArrayList<>(gimbals.keySet())) {
            if (current.containsKey(index)) continue;
            Gimbal removed = gimbals.remove(index);
            if (removed != null) removed.setStateCallback(null);
            clearGimbalStatus(index);
        }
    }

    private void reconcileBatteries(List<Battery> connected) {
        Map<Integer, Battery> current = new LinkedHashMap<>();
        if (connected != null) for (Battery battery : connected) {
            if (battery == null || !battery.isConnected()) continue;
            int index = battery.getIndex();
            current.put(index, battery);
            if (batteries.get(index) == battery) continue;
            Battery replaced = batteries.put(index, battery);
            if (replaced != null) replaced.setStateCallback(null);
            clearAircraftBatteryStatus(index);
            battery.setStateCallback(state -> {
                if (batteries.get(index) != battery) return;
                listeners.dispatch(listener -> listener.onBatteryState(index, state));
                noteAircraftBatteryState(index, state);
            });
        }
        for (Integer index : new java.util.ArrayList<>(batteries.keySet())) {
            if (current.containsKey(index)) continue;
            Battery removed = batteries.remove(index);
            if (removed != null) removed.setStateCallback(null);
            clearAircraftBatteryStatus(index);
        }
    }

    private void detachLocked() {
        if (primaryVideoListener != null && primaryFeed != null) {
            primaryFeed.removeVideoDataListener(primaryVideoListener);
        }
        if (secondaryVideoListener != null && secondaryFeed != null) {
            secondaryFeed.removeVideoDataListener(secondaryVideoListener);
        }
        if (boundFeeder != null && physicalSourceListener != null) {
            boundFeeder.removePhysicalSourceListener(physicalSourceListener);
        }
        if (flightController != null) flightController.setStateCallback(null);
        if (rtk != null) rtk.setStateCallback(null);
        if (remoteController != null) {
            remoteController.setHardwareStateCallback(null);
            remoteController.setChargeRemainingCallback(null);
        }
        if (ocuSyncLink != null) ocuSyncLink.setDownlinkSignalQualityCallback(null);
        for (Gimbal gimbal : gimbals.values()) gimbal.setStateCallback(null);
        for (Battery battery : batteries.values()) battery.setStateCallback(null);
        gimbals.clear(); batteries.clear(); flightController = null; rtk = null;
        remoteController = null;
        ocuSyncLink = null;
        primaryVideoListener = null; secondaryVideoListener = null; physicalSourceListener = null;
        primaryFeed = null; secondaryFeed = null;
        boundFeeder = null;
        aircraft = null;
        synchronized (statusLock) {
            confirmedDjiHome = null;
            confirmedDjiHomeElapsedMs = 0L;
        }
        clearStatusSnapshot();
        publishStatusSnapshot();
        publishAvailability(Feed.PRIMARY, false);
        publishAvailability(Feed.SECONDARY, false);
        listeners.dispatch(listener -> listener.onOcuSyncAvailability(false));
    }

    private void noteFlightState(FlightControllerState state) {
        synchronized (statusLock) {
            latestFlightState = state;
            latestFlightStateElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteRtkState(RTKState state) {
        synchronized (statusLock) {
            latestRtkState = state;
            latestRtkStateElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteGimbalState(int index, GimbalState state) {
        synchronized (statusLock) {
            latestGimbalStates.put(index, state);
            latestGimbalStateElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteAircraftBatteryState(int index, BatteryState state) {
        synchronized (statusLock) {
            latestAircraftBatteryStates.put(index, state);
            latestAircraftBatteryElapsedMs.put(index, SystemClock.elapsedRealtime());
        }
        publishStatusSnapshot();
    }

    private void noteRemoteControllerBattery(dji.common.remotecontroller.BatteryState state) {
        synchronized (statusLock) {
            latestRemoteControllerBattery = state;
            latestRemoteControllerBatteryElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteOcuSyncQuality(int quality) {
        synchronized (statusLock) {
            latestOcuSyncDownlinkQuality = quality;
            latestOcuSyncElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteOcuSyncAvailability(boolean available) {
        synchronized (statusLock) {
            latestOcuSyncDownlinkQuality = -1;
            latestOcuSyncElapsedMs = -1L;
        }
        publishStatusSnapshot();
    }

    private void clearFlightAndRtkStatus() {
        synchronized (statusLock) {
            latestFlightState = null;
            latestRtkState = null;
            latestFlightStateElapsedMs = -1L;
            latestRtkStateElapsedMs = -1L;
        }
    }

    private void clearRemoteControllerBatteryStatus() {
        synchronized (statusLock) {
            latestRemoteControllerBattery = null;
            latestRemoteControllerBatteryElapsedMs = -1L;
        }
    }

    private void clearGimbalStatus(int index) {
        synchronized (statusLock) {
            latestGimbalStates.remove(index);
            // A topology change invalidates the aggregate until DJI reports the remaining gimbal
            // again; never relabel an old gimbal event as freshly observed.
            latestGimbalStateElapsedMs = -1L;
        }
        publishStatusSnapshot();
    }

    private void clearAircraftBatteryStatus(int index) {
        synchronized (statusLock) {
            latestAircraftBatteryStates.remove(index);
            latestAircraftBatteryElapsedMs.remove(index);
        }
        publishStatusSnapshot();
    }

    private void clearStatusSnapshot() {
        synchronized (statusLock) {
            latestFlightState = null;
            latestRtkState = null;
            latestGimbalStates.clear();
            latestRemoteControllerBattery = null;
            latestAircraftBatteryStates.clear();
            latestAircraftBatteryElapsedMs.clear();
            latestOcuSyncDownlinkQuality = -1;
            latestFlightStateElapsedMs = -1L;
            latestRtkStateElapsedMs = -1L;
            latestGimbalStateElapsedMs = -1L;
            latestRemoteControllerBatteryElapsedMs = -1L;
            latestOcuSyncElapsedMs = -1L;
        }
    }

    private void publishStatusSnapshot() {
        final StatusSnapshot snapshot;
        synchronized (statusLock) {
            boolean connected = aircraft != null && aircraft.isConnected();
            boolean controllerConnected = remoteController != null && remoteController.isConnected();
            snapshot = new StatusSnapshot(connected, controllerConnected, ocuSyncLink != null,
                    latestOcuSyncDownlinkQuality, latestFlightState, latestRtkState,
                    latestGimbalStates, latestRemoteControllerBattery, latestAircraftBatteryStates,
                    latestFlightStateElapsedMs, latestRtkStateElapsedMs, latestGimbalStateElapsedMs,
                    latestRemoteControllerBatteryElapsedMs, latestOcuSyncElapsedMs,
                    latestAircraftBatteryElapsedMs);
        }
        listeners.dispatch(listener -> listener.onStatusSnapshot(snapshot));
    }

    private void publishAvailability(Feed feed, boolean available) {
        boolean changed = feed == Feed.PRIMARY ? primaryAvailable != available
                : secondaryAvailable != available;
        if (!changed) return;
        if (feed == Feed.PRIMARY) primaryAvailable = available;
        else secondaryAvailable = available;
        listeners.dispatch(listener -> listener.onFeedAvailability(feed, available));
    }

}
