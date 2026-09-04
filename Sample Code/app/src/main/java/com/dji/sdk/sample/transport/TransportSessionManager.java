package com.dji.sdk.sample.transport;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.dji.sdk.sample.transport.network.TransportConfig;

import dji.sdk.products.Aircraft;

/**
 * Process-wide owner for the passive transport session selected by the active Edge endpoint.
 *
 * <p>The transport is intentionally independent of any individual view lifecycle. A screen may
 * attach or detach while the same session continues until its active endpoint is removed or
 * replaced. Views never own the runtime lifecycle.
 */
public final class TransportSessionManager {
    private static final TransportSessionManager INSTANCE = new TransportSessionManager();
    public static final String PREFERENCES = "matrice_transport";
    public static final String ACTIVE_IP = "active_edge_ip";
    @Nullable private TransportRuntime runtime;
    @Nullable private String runtimeHost;

    private TransportSessionManager() { }

    public static TransportSessionManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean isRunning() {
        return runtime != null;
    }

    /** The persisted endpoint is the operator's explicit background-transport intent. */
    public static boolean hasSelectedEndpoint(Context context) {
        return !preferences(context).getString(ACTIVE_IP, "").trim().isEmpty();
    }

    @Nullable
    public synchronized TransportRuntime getRuntime() {
        return runtime;
    }

    /** Starts one session, replacing it only when the selected endpoint changed. */
    public synchronized TransportRuntime start(Context context, Aircraft aircraft,
                                                TransportConfig config) throws Exception {
        if (runtime != null && config.edgeHost.equals(runtimeHost)) return runtime;
        if (runtime != null) stopLocked();
        if (runtime == null) {
            runtime = new TransportRuntime(context.getApplicationContext(), aircraft, config);
            runtimeHost = config.edgeHost;
        }
        return runtime;
    }

    /**
     * Reconciles the one persistent operator-selected endpoint with product connection state.
     * There is deliberately no guessed broadcast/default host.
     */
    public synchronized boolean reconcile(Context context, @Nullable Aircraft aircraft) throws Exception {
        String host = preferences(context).getString(ACTIVE_IP, "").trim();
        TransportEndpointPolicy.Action action = TransportEndpointPolicy.decide(host, runtimeHost,
                aircraft != null && aircraft.isConnected());
        switch (action) {
            case STOP:
                stopLocked();
                return false;
            case WAIT_FOR_AIRCRAFT:
                return false;
            case KEEP:
                return true;
            case REPLACE:
                // The previous endpoint is no longer operator-authorized. Stop it before
                // waiting for reconnection or creating the replacement runtime.
                stopLocked();
                if (aircraft == null || !aircraft.isConnected()) return false;
                // Falls through intentionally to create one replacement session.
            case START:
                start(context, aircraft, new TransportConfig(host));
                return true;
            default:
                throw new IllegalStateException("Unhandled transport endpoint action: " + action);
        }
    }

    public synchronized void selectEndpoint(Context context, String host, @Nullable Aircraft aircraft)
            throws Exception {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("edge host is required");
        preferences(context).edit().putString(ACTIVE_IP, host.trim()).apply();
        TransportForegroundService.startForSelectedEndpoint(context);
        reconcile(context, aircraft);
    }

    public synchronized void clearEndpoint(Context context) {
        preferences(context).edit().remove(ACTIVE_IP).apply();
        stopLocked();
        TransportForegroundService.stopForNoEndpoint(context);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private void stopLocked() {
        if (runtime == null) return;
        runtime.close();
        runtime = null;
        runtimeHost = null;
    }
}
