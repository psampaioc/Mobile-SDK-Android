package com.dji.sdk.sample.djihub;

import androidx.annotation.Nullable;

/**
 * Immutable, current-aircraft-session observation of the DJI Home Point.
 *
 * <p>This is intentionally independent from view state. A valid command or DJI readback remains
 * usable for its connected-aircraft session; a later missing readback cannot erase it. The hub
 * creates a new waiting state when that session ends.</p>
 */
public final class DjiHomeState {
    public enum Availability { WAITING_FOR_SAMPLE, AVAILABLE, UNAVAILABLE }
    public enum Source { NONE, FLIGHT_STATE, READBACK, EXPLICIT_COMMAND }

    private final Availability availability;
    private final Source source;
    private final Double latitude;
    private final Double longitude;
    private final long observedElapsedMs;

    private DjiHomeState(Availability availability, Source source, Double latitude,
            Double longitude, long observedElapsedMs) {
        this.availability = availability;
        this.source = source;
        this.latitude = latitude;
        this.longitude = longitude;
        this.observedElapsedMs = observedElapsedMs;
    }

    public static DjiHomeState waiting() {
        return new DjiHomeState(Availability.WAITING_FOR_SAMPLE, Source.NONE, null, null, -1L);
    }

    public DjiHomeState withConfirmed(double latitude, double longitude, Source source,
            long observedElapsedMs) {
        if (!valid(latitude, longitude)) return withUnavailableReadback(observedElapsedMs);
        return new DjiHomeState(Availability.AVAILABLE, source, latitude, longitude,
                observedElapsedMs);
    }

    /** A failed/invalid request never regresses an already confirmed current-session Home. */
    public DjiHomeState withUnavailableReadback(long observedElapsedMs) {
        if (isAvailable()) return this;
        return new DjiHomeState(Availability.UNAVAILABLE, Source.NONE, null, null,
                observedElapsedMs);
    }

    /** The next connected aircraft session needs fresh Flight Controller evidence. */
    public DjiHomeState newSession() { return waiting(); }

    public Availability getAvailability() { return availability; }
    public Source getSource() { return source; }
    public boolean isAvailable() { return availability == Availability.AVAILABLE; }
    @Nullable public Double getLatitude() { return latitude; }
    @Nullable public Double getLongitude() { return longitude; }
    public long getObservedElapsedMs() { return observedElapsedMs; }

    public static boolean valid(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }
}
