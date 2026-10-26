package com.dji.sdk.sample.rtk;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.internal.controller.DJISampleApplication;

import dji.common.error.DJIError;
import dji.common.flightcontroller.rtk.ReferenceStationSource;
import dji.sdk.flightcontroller.RTK;
import dji.sdk.network.RTKNetworkServiceProvider;
import dji.sdk.products.Aircraft;

/** Explicit NTRIP operations only. State observation remains owned by DjiDataHub. */
public final class NetworkRtkController {
    public interface Result { void onComplete(boolean accepted, @NonNull String detail); }

    public void connect(@NonNull NetworkRtkProfile profile, @NonNull Result result) {
        String invalid = profile.validationError();
        if (!invalid.isEmpty()) { result.onComplete(false, invalid); return; }
        Aircraft aircraft = DJISampleApplication.getAircraftInstance();
        RTK rtk = aircraft == null || aircraft.getFlightController() == null
                ? null : aircraft.getFlightController().getRTK();
        if (rtk == null || !rtk.isConnected()) {
            result.onComplete(false, "Aircraft RTK is unavailable."); return;
        }
        RTKNetworkServiceProvider provider = RTKNetworkServiceProvider.getInstance();
        provider.setCustomNetworkSettings(profile.toDjiSettings());
        rtk.setRtkEnabled(true, enableError -> {
            if (enableError != null) { complete(result, enableError); return; }
            rtk.setReferenceStationSource(ReferenceStationSource.CUSTOM_NETWORK_SERVICE, sourceError -> {
                if (sourceError != null) { complete(result, sourceError); return; }
                provider.startNetworkService(startError -> complete(result, startError));
            });
        });
    }

    public void disconnect(@NonNull Result result) {
        RTKNetworkServiceProvider.getInstance().stopNetworkService(error -> complete(result, error));
    }

    private static void complete(@NonNull Result result, DJIError error) {
        result.onComplete(error == null, error == null ? "RTK network service request accepted."
                : String.valueOf(error.getDescription()));
    }
}
