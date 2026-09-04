package com.dji.sdk.sample.operate;

/**
 * Holds the last explicitly confirmed DJI Home for one connected-aircraft UI session.
 *
 * <p>A delayed FlightControllerState is observational, so an unavailable/invalid readback must
 * not erase a Home location that DJI has just confirmed through setHomeLocation. A later valid
 * readback is authoritative and refreshes the stored coordinate.</p>
 */
public final class ConfirmedDjiHomeState {
    private Double latitude;
    private Double longitude;

    public void confirm(double latitude, double longitude) {
        if (!valid(latitude, longitude)) return;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** Applies a FlightControllerState Home observation without regressing a confirmed Home. */
    public void reconcileReadback(boolean reportedValid, double reportedLatitude,
            double reportedLongitude) {
        if (reportedValid && valid(reportedLatitude, reportedLongitude)) {
            confirm(reportedLatitude, reportedLongitude);
        }
    }

    public boolean isAvailable() { return latitude != null && longitude != null; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }

    private static boolean valid(double latitude, double longitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
                && !Double.isNaN(longitude) && !Double.isInfinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }
}
