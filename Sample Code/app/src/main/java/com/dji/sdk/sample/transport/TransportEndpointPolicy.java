package com.dji.sdk.sample.transport;

import androidx.annotation.Nullable;

/** Pure lifecycle policy for the one operator-selected passive transport endpoint. */
final class TransportEndpointPolicy {
    enum Action { STOP, WAIT_FOR_AIRCRAFT, KEEP, START, REPLACE }

    private TransportEndpointPolicy() { }

    static Action decide(@Nullable String activeHost, @Nullable String runtimeHost,
            boolean aircraftConnected) {
        String selected = activeHost == null ? "" : activeHost.trim();
        if (selected.isEmpty()) return Action.STOP;
        if (runtimeHost != null && !selected.equals(runtimeHost)) return Action.REPLACE;
        if (!aircraftConnected) return Action.WAIT_FOR_AIRCRAFT;
        return runtimeHost == null ? Action.START : Action.KEEP;
    }
}
