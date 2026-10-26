package com.dji.sdk.sample.djihub;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import dji.common.airlink.PhysicalSource;
import dji.common.battery.BatteryState;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.RTKState;
import dji.common.flightcontroller.rtk.NetworkServiceState;
import dji.common.flightcontroller.rtk.ReferenceStationSource;
import dji.common.gimbal.GimbalState;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks;
import dji.common.remotecontroller.HardwareState;
import dji.sdk.battery.Battery;
import dji.sdk.base.DJIDiagnostics;
import dji.sdk.airlink.AirLink;
import dji.sdk.airlink.OcuSyncLink;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.flightcontroller.RTK;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.network.RTKNetworkServiceProvider;
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
        long generation;
        synchronized (statusLock) {
            current = flightController;
            generation = aircraftSessionGeneration.get();
        }
        if (current == null || !location.isValid()) return false;
        current.setHomeLocation(location, error -> {
            boolean currentSession;
            synchronized (statusLock) {
                currentSession = current == flightController
                        && generation == aircraftSessionGeneration.get();
                if (error == null && currentSession) {
                    latestDjiHome = latestDjiHome.withConfirmed(location.getLatitude(),
                            location.getLongitude(), DjiHomeState.Source.EXPLICIT_COMMAND,
                            SystemClock.elapsedRealtime());
                }
            }
            if (error == null && currentSession) {
                publishStatusSnapshot();
            }
            publishDjiHomeUpdateResult(false, error);
            callback.onResult(error);
        });
        return true;
    }

    /** Delivers the immutable cached snapshot without registering a new DJI callback. */
    public void requestStatusSnapshot() { publishStatusSnapshot(); }

    /**
     * Requests one authoritative Home Point readback through the sole DJI callback owner.
     * It intentionally does not replace the recurring Flight Controller state callback.
     */
    public boolean requestDjiHomeReadback() {
        final FlightController current;
        final long generation;
        synchronized (statusLock) {
            current = flightController;
            generation = aircraftSessionGeneration.get();
        }
        if (current == null) {
            noteDjiHomeUnavailable();
            publishStatusSnapshot();
            return false;
        }
        current.getHomeLocation(new CommonCallbacks.CompletionCallbackWith<LocationCoordinate2D>() {
            @Override public void onSuccess(LocationCoordinate2D location) {
                synchronized (statusLock) {
                    if (current != flightController || generation != aircraftSessionGeneration.get()) return;
                    if (location != null && location.isValid()) {
                        latestDjiHome = latestDjiHome.withConfirmed(location.getLatitude(),
                                location.getLongitude(), DjiHomeState.Source.READBACK,
                                SystemClock.elapsedRealtime());
                    } else {
                        latestDjiHome = latestDjiHome.withUnavailableReadback(SystemClock.elapsedRealtime());
                    }
                }
                publishStatusSnapshot();
            }

            @Override public void onFailure(dji.common.error.DJIError error) {
                synchronized (statusLock) {
                    if (current != flightController || generation != aircraftSessionGeneration.get()) return;
                    latestDjiHome = latestDjiHome.withUnavailableReadback(SystemClock.elapsedRealtime());
                }
                publishStatusSnapshot();
            }
        });
        return true;
    }

    public interface Listener {
        default void onVideo(@NonNull Feed feed, byte[] data, int size, @NonNull String source) { }
        /** Ownership transition only; emitted when this hub attaches or detaches its DJI listener. */
        default void onVideoBinding(@NonNull Feed feed, boolean attached) { }
        default void onFeedAvailability(@NonNull Feed feed, boolean available) { }
        default void onPhysicalSource(@NonNull Feed feed, @NonNull PhysicalSource source) { }
        default void onFlightState(@NonNull FlightControllerState state) { }
        default void onRtkState(@NonNull RTKState state) { }
        /** Selected DJI RTK reference source, observed through this hub. */
        default void onRtkReferenceStationSource(@NonNull ReferenceStationSource source) { }
        /** DJI network-RTK channel state, observed through this hub. */
        default void onRtkNetworkServiceState(@NonNull NetworkServiceState state) { }
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
        /** Current DJI health/diagnostic conditions, fanned out from the sole SDK callback owner. */
        default void onDiagnostics(@NonNull List<DJIDiagnostics> diagnostics) { }
        /** Result of a Home-point command; emitted so local diagnostics retain DJI's exact reason. */
        default void onDjiHomeUpdateResult(@NonNull DjiHomeUpdateResult result) { }
        /** Immutable, timestamped passive status sample for consumers that render an aggregate. */
        default void onStatusSnapshot(@NonNull StatusSnapshot snapshot) { }
    }

    /** Primary H.264 delivery below the one DJI callback; each consumer has a bounded worker. */
    public interface PrimaryVideoListener {
        void onVideo(byte[] data, int size, @NonNull String source);
        default void onDiscontinuity() { }
    }

    /** Closes one Primary-video consumer without affecting the DJI feed or its peers. */
    public interface PrimaryVideoSubscription extends AutoCloseable {
        @Override void close();
    }

    /** Immutable result of one requested Home-point update. */
    public static final class DjiHomeUpdateResult {
        public final boolean automaticCurrentAircraftLocation;
        public final boolean accepted;
        @NonNull public final String detail;

        private DjiHomeUpdateResult(boolean automaticCurrentAircraftLocation, boolean accepted,
                @NonNull String detail) {
            this.automaticCurrentAircraftLocation = automaticCurrentAircraftLocation;
            this.accepted = accepted;
            this.detail = detail;
        }
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
        public final ReferenceStationSource rtkReferenceStationSource;
        public final NetworkServiceState rtkNetworkServiceState;
        public final Map<Integer, GimbalState> gimbalStates;
        public final dji.common.remotecontroller.BatteryState remoteControllerBattery;
        public final Map<Integer, BatteryState> aircraftBatteryStates;
        public final long flightStateElapsedMs;
        public final long rtkStateElapsedMs;
        public final long rtkReferenceStationSourceElapsedMs;
        public final long rtkNetworkServiceStateElapsedMs;
        public final long gimbalStateElapsedMs;
        public final long remoteControllerBatteryElapsedMs;
        public final long ocuSyncElapsedMs;
        public final Map<Integer, Long> aircraftBatteryElapsedMs;
        /** Current connected-aircraft Home Point evidence, independent from view state. */
        public final DjiHomeState djiHome;

        private StatusSnapshot(boolean aircraftConnected, boolean remoteControllerConnected,
                boolean ocuSyncAvailable, int ocuSyncDownlinkQuality,
                FlightControllerState flightState, RTKState rtkState,
                ReferenceStationSource rtkReferenceStationSource,
                NetworkServiceState rtkNetworkServiceState,
                Map<Integer, GimbalState> gimbalStates,
                dji.common.remotecontroller.BatteryState remoteControllerBattery,
                Map<Integer, BatteryState> aircraftBatteryStates, long flightStateElapsedMs,
                long rtkStateElapsedMs, long rtkReferenceStationSourceElapsedMs,
                long rtkNetworkServiceStateElapsedMs, long gimbalStateElapsedMs,
                long remoteControllerBatteryElapsedMs, long ocuSyncElapsedMs,
                Map<Integer, Long> aircraftBatteryElapsedMs, DjiHomeState djiHome) {
            this.aircraftConnected = aircraftConnected;
            this.remoteControllerConnected = remoteControllerConnected;
            this.ocuSyncAvailable = ocuSyncAvailable;
            this.ocuSyncDownlinkQuality = ocuSyncDownlinkQuality;
            this.flightState = flightState;
            this.rtkState = rtkState;
            this.rtkReferenceStationSource = rtkReferenceStationSource;
            this.rtkNetworkServiceState = rtkNetworkServiceState;
            this.gimbalStates = Collections.unmodifiableMap(new LinkedHashMap<>(gimbalStates));
            this.remoteControllerBattery = remoteControllerBattery;
            this.aircraftBatteryStates = Collections.unmodifiableMap(
                    new LinkedHashMap<>(aircraftBatteryStates));
            this.flightStateElapsedMs = flightStateElapsedMs;
            this.rtkStateElapsedMs = rtkStateElapsedMs;
            this.rtkReferenceStationSourceElapsedMs = rtkReferenceStationSourceElapsedMs;
            this.rtkNetworkServiceStateElapsedMs = rtkNetworkServiceStateElapsedMs;
            this.gimbalStateElapsedMs = gimbalStateElapsedMs;
            this.remoteControllerBatteryElapsedMs = remoteControllerBatteryElapsedMs;
            this.ocuSyncElapsedMs = ocuSyncElapsedMs;
            this.aircraftBatteryElapsedMs = Collections.unmodifiableMap(
                    new LinkedHashMap<>(aircraftBatteryElapsedMs));
            this.djiHome = djiHome;
        }
    }

    private static final DjiDataHub INSTANCE = new DjiDataHub();
    private final CallbackMulticaster<Listener> listeners = new CallbackMulticaster<>();
    private final PrimaryVideoFanout primaryVideoFanout = new PrimaryVideoFanout(64);
    // Primary callbacks are dispatched synchronously by DJI. Keep only timing/counter evidence
    // here so field diagnostics can distinguish an upstream pause from a slow hub consumer.
    private final VideoDispatchMetrics primaryVideoDispatchMetrics = new VideoDispatchMetrics();
    private final Object statusLock = new Object();
    private final AtomicLong aircraftSessionGeneration = new AtomicLong();
    private DjiHomeState latestDjiHome = DjiHomeState.waiting();
    // Enabled only for the current field-test build at the operator's explicit request.
    private final Map<Integer, Gimbal> gimbals = new LinkedHashMap<>();
    private final Map<Integer, Battery> batteries = new LinkedHashMap<>();
    private Aircraft aircraft;
    private FlightController flightController;
    private RTK rtk;
    private ReferenceStationSource.Callback rtkReferenceStationSourceCallback;
    private RTKNetworkServiceProvider rtkNetworkServiceProvider;
    private NetworkServiceState.Callback rtkNetworkServiceStateCallback;
    private RemoteController remoteController;
    private OcuSyncLink ocuSyncLink;
    private Aircraft diagnosticsAircraft;
    private DJIDiagnostics.DiagnosticsInformationCallback diagnosticsCallback;
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
    private ReferenceStationSource latestRtkReferenceStationSource = ReferenceStationSource.UNKNOWN;
    private NetworkServiceState latestRtkNetworkServiceState;
    private final Map<Integer, GimbalState> latestGimbalStates = new LinkedHashMap<>();
    private dji.common.remotecontroller.BatteryState latestRemoteControllerBattery;
    private final Map<Integer, BatteryState> latestAircraftBatteryStates = new LinkedHashMap<>();
    private final Map<Integer, Long> latestAircraftBatteryElapsedMs = new LinkedHashMap<>();
    private int latestOcuSyncDownlinkQuality = -1;
    private long latestFlightStateElapsedMs = -1L;
    private long latestRtkStateElapsedMs = -1L;
    private long latestRtkReferenceStationSourceElapsedMs = -1L;
    private long latestRtkNetworkServiceStateElapsedMs = -1L;
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

    public PrimaryVideoSubscription addPrimaryVideoListener(@NonNull PrimaryVideoListener listener) {
        PrimaryVideoFanout.Subscription subscription = primaryVideoFanout.add(
                new PrimaryVideoFanout.Listener() {
                    @Override public void onVideo(byte[] data, int size, String source) {
                        listener.onVideo(data, size, source);
                    }

                    @Override public void onDiscontinuity() { listener.onDiscontinuity(); }
                });
        return subscription::close;
    }

    /** Starts or refreshes ownership for the currently connected product. Safe to call repeatedly. */
    public synchronized void start(@NonNull Aircraft nextAircraft) {
        if (aircraft != nextAircraft) {
            detachLocked();
            aircraft = nextAircraft;
        }
        attachVideoLocked();
        attachTelemetryLocked();
        attachNetworkRtkLocked();
        attachRemoteControllerLocked();
        attachAirLinkLocked();
        refreshComponentsLocked();
        attachDiagnosticsLocked();
        publishStatusSnapshot();
    }

    /** Reconciles every owned binding after product or component connectivity changes. */
    public synchronized void refresh(@NonNull Aircraft expectedAircraft) {
        if (aircraft == expectedAircraft) {
            attachVideoLocked();
            attachTelemetryLocked();
        attachNetworkRtkLocked();
        attachRemoteControllerLocked();
        attachAirLinkLocked();
        refreshComponentsLocked();
        attachDiagnosticsLocked();
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

    /** Local diagnostic snapshot; no callback ownership or dispatch behavior changes. */
    public VideoDispatchSnapshot getPrimaryVideoDispatchSnapshot() {
        return primaryVideoDispatchMetrics.snapshot();
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
            publishVideoBinding(Feed.PRIMARY, false);
            primaryVideoListener = null;
            primaryFeed = null;
        }
        if (secondaryFeed != secondary && secondaryVideoListener != null && secondaryFeed != null) {
            secondaryFeed.removeVideoDataListener(secondaryVideoListener);
            publishVideoBinding(Feed.SECONDARY, false);
            secondaryVideoListener = null;
            secondaryFeed = null;
        }
        if (primary != null && primaryVideoListener == null) {
            primaryFeed = primary;
            primarySource = String.valueOf(primary.getVideoSource());
            primaryVideoListener = (data, size) -> {
                long startedNanos = SystemClock.elapsedRealtimeNanos();
                primaryVideoDispatchMetrics.recordIngress(startedNanos);
                try {
                    primaryVideoFanout.dispatch(data, size, primarySource);
                } finally {
                    long completedNanos = SystemClock.elapsedRealtimeNanos();
                    primaryVideoDispatchMetrics.recordFanoutComplete(
                            completedNanos - startedNanos, completedNanos);
                }
            };
            primary.addVideoDataListener(primaryVideoListener);
            publishVideoBinding(Feed.PRIMARY, true);
        }
        if (secondary != null && secondaryVideoListener == null) {
            secondaryFeed = secondary;
            secondarySource = String.valueOf(secondary.getVideoSource());
            secondaryVideoListener = (data, size) -> listeners.dispatch(listener ->
                    listener.onVideo(Feed.SECONDARY, data, size, secondarySource));
            secondary.addVideoDataListener(secondaryVideoListener);
            publishVideoBinding(Feed.SECONDARY, true);
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
            if (rtk != null) {
                rtk.setStateCallback(null);
                if (rtkReferenceStationSourceCallback != null) {
                    rtk.removeReferenceStationSourceCallback(rtkReferenceStationSourceCallback);
                }
            }
            flightController = currentFlightController;
            rtk = null;
            rtkReferenceStationSourceCallback = null;
            clearFlightAndRtkStatus();
        }
        // DJI component reconnects can preserve the Java object while clearing setter callbacks.
        // Reassert the hub-owned callback on every refresh; no other component owns this setter.
        if (flightController != null) flightController.setStateCallback(state -> {
            if (flightController != currentFlightController) return;
            noteFlightState(state);
            listeners.dispatch(listener -> listener.onFlightState(state));
        });
        RTK currentRtk = flightController == null ? null : flightController.getRTK();
        if (currentRtk != rtk) {
            if (rtk != null) {
                rtk.setStateCallback(null);
                if (rtkReferenceStationSourceCallback != null) {
                    rtk.removeReferenceStationSourceCallback(rtkReferenceStationSourceCallback);
                }
            }
            rtk = currentRtk;
            rtkReferenceStationSourceCallback = null;
        }
        if (rtk != null) rtk.setStateCallback(state -> {
            if (rtk != currentRtk) return;
            listeners.dispatch(listener -> listener.onRtkState(state));
            noteRtkState(state);
        });
    }

    /** Owns additive RTK source/provider observers; UI never binds DJI RTK directly. */
    private void attachNetworkRtkLocked() {
        if (rtk != null && rtkReferenceStationSourceCallback == null) {
            final RTK observedRtk = rtk;
            rtkReferenceStationSourceCallback = source -> {
                if (rtk != observedRtk) return;
                noteRtkReferenceStationSource(source);
                listeners.dispatch(listener -> listener.onRtkReferenceStationSource(source));
            };
            rtk.addReferenceStationSourceCallback(rtkReferenceStationSourceCallback);
        }
        if (rtkNetworkServiceProvider == null) {
            rtkNetworkServiceProvider = RTKNetworkServiceProvider.getInstance();
            rtkNetworkServiceStateCallback = state -> {
                noteRtkNetworkServiceState(state);
                listeners.dispatch(listener -> listener.onRtkNetworkServiceState(state));
            };
            rtkNetworkServiceProvider.addNetworkServiceStateCallback(rtkNetworkServiceStateCallback);
        }
    }

    private void refreshComponentsLocked() {
        if (aircraft == null) return;
        reconcileGimbals(aircraft.getGimbals());
        reconcileBatteries(aircraft.getBatteries());
    }

    private void attachRemoteControllerLocked() {
        RemoteController current = aircraft == null ? null : aircraft.getRemoteController();
        if (current != remoteController) {
            if (remoteController != null) {
                remoteController.setHardwareStateCallback(null);
                remoteController.setChargeRemainingCallback(null);
            }
            remoteController = current;
            clearRemoteControllerBatteryStatus();
        }
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
        if (current != ocuSyncLink) {
            if (ocuSyncLink != null) ocuSyncLink.setDownlinkSignalQualityCallback(null);
            ocuSyncLink = current;
            clearOcuSyncQuality();
            listeners.dispatch(listener -> listener.onOcuSyncAvailability(current != null));
        }
        if (ocuSyncLink != null) {
            ocuSyncLink.setDownlinkSignalQualityCallback(quality -> {
                if (ocuSyncLink != current) return;
                listeners.dispatch(listener -> listener.onOcuSyncDownlinkSignalQuality(quality));
                noteOcuSyncQuality(quality);
            });
        }
    }

    /**
     * DJI diagnostics use a setter callback, so it belongs in the hub just like FlightController
     * and VideoFeeder state. Consumers receive fan-out and never replace this callback.
     */
    private void attachDiagnosticsLocked() {
        final Aircraft current = aircraft;
        if (diagnosticsAircraft != current) {
            if (diagnosticsAircraft != null) {
                diagnosticsAircraft.setDiagnosticsInformationCallback(null);
            }
            diagnosticsAircraft = current;
            diagnosticsCallback = current == null ? null : diagnostics -> {
                if (diagnosticsAircraft != current) return;
                List<DJIDiagnostics> copy = diagnostics == null
                        ? Collections.emptyList() : new ArrayList<>(diagnostics);
                listeners.dispatch(listener -> listener.onDiagnostics(
                        Collections.unmodifiableList(copy)));
            };
        }
        // A component reconnect can clear SDK setter callbacks while retaining its Java object.
        if (current != null && diagnosticsCallback != null) {
            current.setDiagnosticsInformationCallback(diagnosticsCallback);
        }
    }

    private void reconcileGimbals(List<Gimbal> connected) {
        Map<Integer, Gimbal> current = new LinkedHashMap<>();
        if (connected != null) for (Gimbal gimbal : connected) {
            if (gimbal == null || !gimbal.isConnected()) continue;
            int index = gimbal.getIndex();
            current.put(index, gimbal);
            if (gimbals.get(index) != gimbal) {
                Gimbal replaced = gimbals.put(index, gimbal);
                if (replaced != null) replaced.setStateCallback(null);
                clearGimbalStatus(index);
            }
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
            if (batteries.get(index) != battery) {
                Battery replaced = batteries.put(index, battery);
                if (replaced != null) replaced.setStateCallback(null);
                clearAircraftBatteryStatus(index);
            }
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
        synchronized (statusLock) { aircraftSessionGeneration.incrementAndGet(); }
        if (primaryVideoListener != null && primaryFeed != null) {
            primaryFeed.removeVideoDataListener(primaryVideoListener);
            publishVideoBinding(Feed.PRIMARY, false);
        }
        if (secondaryVideoListener != null && secondaryFeed != null) {
            secondaryFeed.removeVideoDataListener(secondaryVideoListener);
            publishVideoBinding(Feed.SECONDARY, false);
        }
        if (boundFeeder != null && physicalSourceListener != null) {
            boundFeeder.removePhysicalSourceListener(physicalSourceListener);
        }
        if (flightController != null) flightController.setStateCallback(null);
        if (rtk != null) {
            rtk.setStateCallback(null);
            if (rtkReferenceStationSourceCallback != null) {
                rtk.removeReferenceStationSourceCallback(rtkReferenceStationSourceCallback);
            }
        }
        if (rtkNetworkServiceProvider != null && rtkNetworkServiceStateCallback != null) {
            rtkNetworkServiceProvider.removeNetworkServiceStateCallback(rtkNetworkServiceStateCallback);
        }
        if (remoteController != null) {
            remoteController.setHardwareStateCallback(null);
            remoteController.setChargeRemainingCallback(null);
        }
        if (ocuSyncLink != null) ocuSyncLink.setDownlinkSignalQualityCallback(null);
        if (diagnosticsAircraft != null) diagnosticsAircraft.setDiagnosticsInformationCallback(null);
        for (Gimbal gimbal : gimbals.values()) gimbal.setStateCallback(null);
        for (Battery battery : batteries.values()) battery.setStateCallback(null);
        gimbals.clear(); batteries.clear(); flightController = null; rtk = null;
        rtkReferenceStationSourceCallback = null;
        rtkNetworkServiceStateCallback = null;
        rtkNetworkServiceProvider = null;
        remoteController = null;
        ocuSyncLink = null;
        diagnosticsAircraft = null;
        diagnosticsCallback = null;
        primaryVideoListener = null; secondaryVideoListener = null; physicalSourceListener = null;
        primaryFeed = null; secondaryFeed = null;
        boundFeeder = null;
        aircraft = null;
        synchronized (statusLock) {
            latestDjiHome = latestDjiHome.newSession();
        }
        clearStatusSnapshot();
        publishStatusSnapshot();
        publishAvailability(Feed.PRIMARY, false);
        publishAvailability(Feed.SECONDARY, false);
        listeners.dispatch(listener -> listener.onOcuSyncAvailability(false));
    }

    private void publishVideoBinding(Feed feed, boolean attached) {
        listeners.dispatch(listener -> listener.onVideoBinding(feed, attached));
    }

    private void noteFlightState(FlightControllerState state) {
        synchronized (statusLock) {
            latestFlightState = state;
            latestFlightStateElapsedMs = SystemClock.elapsedRealtime();
            LocationCoordinate2D home = state.getHomeLocation();
            if (state.isHomeLocationSet() && home != null && home.isValid()) {
                latestDjiHome = latestDjiHome.withConfirmed(home.getLatitude(), home.getLongitude(),
                        DjiHomeState.Source.FLIGHT_STATE, latestFlightStateElapsedMs);
            } else {
                latestDjiHome = latestDjiHome.withUnavailableReadback(latestFlightStateElapsedMs);
            }
        }
        publishStatusSnapshot();
    }

    private void publishDjiHomeUpdateResult(boolean automaticCurrentAircraftLocation,
            dji.common.error.DJIError error) {
        String detail = error == null ? "accepted" : String.valueOf(error.getDescription());
        DjiHomeUpdateResult result = new DjiHomeUpdateResult(automaticCurrentAircraftLocation,
                error == null, detail);
        listeners.dispatch(listener -> listener.onDjiHomeUpdateResult(result));
    }

    private void noteDjiHome(double latitude, double longitude, DjiHomeState.Source source) {
        synchronized (statusLock) {
            latestDjiHome = latestDjiHome.withConfirmed(latitude, longitude, source,
                    SystemClock.elapsedRealtime());
        }
    }

    private void noteDjiHomeUnavailable() {
        synchronized (statusLock) {
            latestDjiHome = latestDjiHome.withUnavailableReadback(SystemClock.elapsedRealtime());
        }
    }

    private void noteRtkState(RTKState state) {
        synchronized (statusLock) {
            latestRtkState = state;
            latestRtkStateElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteRtkReferenceStationSource(ReferenceStationSource source) {
        synchronized (statusLock) {
            latestRtkReferenceStationSource = source == null ? ReferenceStationSource.UNKNOWN : source;
            latestRtkReferenceStationSourceElapsedMs = SystemClock.elapsedRealtime();
        }
        publishStatusSnapshot();
    }

    private void noteRtkNetworkServiceState(NetworkServiceState state) {
        synchronized (statusLock) {
            latestRtkNetworkServiceState = state;
            latestRtkNetworkServiceStateElapsedMs = SystemClock.elapsedRealtime();
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

    private void clearOcuSyncQuality() {
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
            latestRtkReferenceStationSource = ReferenceStationSource.UNKNOWN;
            latestRtkNetworkServiceState = null;
            latestFlightStateElapsedMs = -1L;
            latestRtkStateElapsedMs = -1L;
            latestRtkReferenceStationSourceElapsedMs = -1L;
            latestRtkNetworkServiceStateElapsedMs = -1L;
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
            latestRtkReferenceStationSource = ReferenceStationSource.UNKNOWN;
            latestRtkNetworkServiceState = null;
            latestGimbalStates.clear();
            latestRemoteControllerBattery = null;
            latestAircraftBatteryStates.clear();
            latestAircraftBatteryElapsedMs.clear();
            latestOcuSyncDownlinkQuality = -1;
            latestFlightStateElapsedMs = -1L;
            latestRtkStateElapsedMs = -1L;
            latestRtkReferenceStationSourceElapsedMs = -1L;
            latestRtkNetworkServiceStateElapsedMs = -1L;
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
                    latestRtkReferenceStationSource, latestRtkNetworkServiceState,
                    latestGimbalStates, latestRemoteControllerBattery, latestAircraftBatteryStates,
                    latestFlightStateElapsedMs, latestRtkStateElapsedMs,
                    latestRtkReferenceStationSourceElapsedMs, latestRtkNetworkServiceStateElapsedMs,
                    latestGimbalStateElapsedMs,
                    latestRemoteControllerBatteryElapsedMs, latestOcuSyncElapsedMs,
                    latestAircraftBatteryElapsedMs, latestDjiHome);
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

    public static final class VideoDispatchSnapshot {
        public final long ingressCallbacks;
        public final long lastIngressNanos;
        public final long lastFanoutCompleteNanos;
        public final long lastFanoutDurationNanos;
        public final long maxFanoutDurationNanos;

        private VideoDispatchSnapshot(long ingressCallbacks, long lastIngressNanos,
                long lastFanoutCompleteNanos, long lastFanoutDurationNanos,
                long maxFanoutDurationNanos) {
            this.ingressCallbacks = ingressCallbacks;
            this.lastIngressNanos = lastIngressNanos;
            this.lastFanoutCompleteNanos = lastFanoutCompleteNanos;
            this.lastFanoutDurationNanos = lastFanoutDurationNanos;
            this.maxFanoutDurationNanos = maxFanoutDurationNanos;
        }
    }

    private static final class VideoDispatchMetrics {
        private final AtomicLong ingressCallbacks = new AtomicLong();
        private final AtomicLong maxFanoutDurationNanos = new AtomicLong();
        private volatile long lastIngressNanos;
        private volatile long lastFanoutCompleteNanos;
        private volatile long lastFanoutDurationNanos;

        void recordIngress(long nowNanos) {
            ingressCallbacks.incrementAndGet();
            lastIngressNanos = nowNanos;
        }

        void recordFanoutComplete(long durationNanos, long completedNanos) {
            long boundedDuration = Math.max(0L, durationNanos);
            lastFanoutDurationNanos = boundedDuration;
            lastFanoutCompleteNanos = completedNanos;
            updateMaximum(maxFanoutDurationNanos, boundedDuration);
        }

        VideoDispatchSnapshot snapshot() {
            return new VideoDispatchSnapshot(ingressCallbacks.get(), lastIngressNanos,
                    lastFanoutCompleteNanos, lastFanoutDurationNanos,
                    maxFanoutDurationNanos.getAndSet(0L));
        }

        private static void updateMaximum(AtomicLong target, long value) {
            long current;
            do {
                current = target.get();
                if (value <= current) return;
            } while (!target.compareAndSet(current, value));
        }
    }

}
