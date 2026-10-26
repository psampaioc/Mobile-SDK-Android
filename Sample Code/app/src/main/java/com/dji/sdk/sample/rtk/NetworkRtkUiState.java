package com.dji.sdk.sample.rtk;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dji.common.flightcontroller.RTKState;
import dji.common.flightcontroller.rtk.NetworkServiceState;
import dji.common.flightcontroller.rtk.ReferenceStationSource;

/** Pure display mapping; connection channel and positioning solution are intentionally separate. */
public final class NetworkRtkUiState {
    private NetworkRtkUiState() { }

    @NonNull public static String channel(@Nullable NetworkServiceState state) {
        if (state == null) return "DISCONNECTED";
        if (state.getError() != null) return "ERROR: " + state.getError().getDescription();
        return String.valueOf(state.getChannelState());
    }

    @NonNull public static String source(@Nullable ReferenceStationSource source) {
        return source == null || source == ReferenceStationSource.UNKNOWN ? "--" : source.name();
    }

    @NonNull public static String solution(@Nullable RTKState state) {
        return state == null || state.getPositioningSolution() == null ? "--"
                : String.valueOf(state.getPositioningSolution());
    }

    @NonNull public static String inUse(@Nullable RTKState state) {
        return state != null && state.isRTKBeingUsed() ? "RTK IN USE" : "RTK NOT IN USE";
    }
}
