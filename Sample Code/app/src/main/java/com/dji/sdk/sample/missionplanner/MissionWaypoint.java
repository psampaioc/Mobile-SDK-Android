package com.dji.sdk.sample.missionplanner;

import java.util.Objects;

/** A WGS84 point with an optional altitude override relative to takeoff. */
public final class MissionWaypoint {
    private final double latitude;
    private final double longitude;
    private final Float altitudeOverrideMeters;

    public MissionWaypoint(double latitude, double longitude, Float altitudeOverrideMeters) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeOverrideMeters = altitudeOverrideMeters;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Float getAltitudeOverrideMeters() { return altitudeOverrideMeters; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MissionWaypoint)) return false;
        MissionWaypoint that = (MissionWaypoint) other;
        return Double.compare(latitude, that.latitude) == 0
                && Double.compare(longitude, that.longitude) == 0
                && Objects.equals(altitudeOverrideMeters, that.altitudeOverrideMeters);
    }

    @Override public int hashCode() {
        return Objects.hash(latitude, longitude, altitudeOverrideMeters);
    }
}
