package com.dji.sdk.sample.rtk;

import androidx.annotation.NonNull;

import dji.common.flightcontroller.rtk.NetworkServiceSettings;

/** One operator-supplied NTRIP profile. It never logs or renders its password. */
public final class NetworkRtkProfile {
    @NonNull public final String host;
    public final int port;
    @NonNull public final String user;
    @NonNull public final String password;
    @NonNull public final String mountpoint;

    public NetworkRtkProfile(@NonNull String host, int port, @NonNull String user,
            @NonNull String password, @NonNull String mountpoint) {
        this.host = host.trim(); this.port = port; this.user = user.trim();
        this.password = password; this.mountpoint = mountpoint.trim();
    }

    @NonNull public String validationError() {
        if (host.isEmpty()) return "NTRIP Host is required.";
        if (port < 1 || port > 65535) return "Port must be between 1 and 65535.";
        if (mountpoint.isEmpty()) return "Mountpoint is required.";
        return "";
    }

    public boolean isValid() { return validationError().isEmpty(); }

    @NonNull public NetworkServiceSettings toDjiSettings() {
        return new NetworkServiceSettings.Builder().ip(host).port(port).userName(user)
                .password(password).mountPoint(mountpoint).build();
    }
}
