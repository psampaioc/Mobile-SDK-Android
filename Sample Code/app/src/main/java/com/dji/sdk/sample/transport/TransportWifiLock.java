package com.dji.sdk.sample.transport;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;

/** One non-reference-counted Wi-Fi performance request owned by passive transport retention. */
public final class TransportWifiLock {
    public enum State { INACTIVE, HELD, UNAVAILABLE, FAILED }

    private WifiManager.WifiLock lock;
    private State state = State.INACTIVE;
    private String requestedMode = "NONE";
    private String detail = "";

    public synchronized Snapshot acquire(Context context) {
        if (lock != null && lock.isHeld()) {
            state = State.HELD;
            return snapshot();
        }
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (manager == null) {
                state = State.UNAVAILABLE;
                requestedMode = "NONE";
                detail = "WifiManager unavailable";
                return snapshot();
            }
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            requestedMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "LOW_LATENCY" : "HIGH_PERF";
            lock = manager.createWifiLock(mode, "matrice:edge-transport");
            lock.setReferenceCounted(false);
            lock.acquire();
            state = lock.isHeld() ? State.HELD : State.UNAVAILABLE;
            detail = lock.isHeld() ? "" : "Wi-Fi lock was not held after acquire";
        } catch (Exception failure) {
            lock = null;
            state = State.FAILED;
            detail = describe(failure);
        }
        return snapshot();
    }

    public synchronized Snapshot release() {
        WifiManager.WifiLock current = lock;
        lock = null;
        if (current != null) try {
            if (current.isHeld()) current.release();
        } catch (Exception failure) {
            state = State.FAILED;
            detail = describe(failure);
            return snapshot();
        }
        state = State.INACTIVE;
        requestedMode = "NONE";
        detail = "";
        return snapshot();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, requestedMode, detail);
    }

    private static String describe(Exception failure) {
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    public static final class Snapshot {
        public final State state;
        public final String requestedMode;
        public final String detail;

        Snapshot(State state, String requestedMode, String detail) {
            this.state = state;
            this.requestedMode = requestedMode;
            this.detail = detail;
        }
    }
}
