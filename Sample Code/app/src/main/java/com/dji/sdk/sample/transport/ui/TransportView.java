package com.dji.sdk.sample.transport.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.transport.TransportRuntime;
import com.dji.sdk.sample.transport.TransportSessionManager;
import com.dji.sdk.sample.transport.diagnostics.CallbackRateSnapshot;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Endpoint selection and read-only status for the process-wide passive transport. */
public final class TransportView extends LinearLayout implements PresentableView {
    private static final String SAVED_IPS = "saved_edge_ips";
    private final EditText host;
    private final TextView status;
    private final LinearLayout ipList;
    private final SharedPreferences preferences;
    private final Set<String> selectedIps = new LinkedHashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TransportSessionManager sessionManager = TransportSessionManager.getInstance();

    public TransportView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_transport, this, true);
        host = findViewById(R.id.transport_edge_host);
        status = findViewById(R.id.transport_status);
        ipList = findViewById(R.id.transport_ip_list);
        preferences = context.getSharedPreferences(TransportSessionManager.PREFERENCES, Context.MODE_PRIVATE);
        String active = preferences.getString(TransportSessionManager.ACTIVE_IP, "");
        if (!active.isEmpty()) host.setText(active);
        findViewById(R.id.transport_add_ip).setOnClickListener(view -> addIp());
        findViewById(R.id.transport_remove_ip).setOnClickListener(view -> removeSelectedIps());
        findViewById(R.id.transport_use_ip).setOnClickListener(view -> useSelectedIp());
        renderIpList();
        renderStatus();
    }

    private void addIp() {
        String value = host.getText().toString().trim();
        if (value.isEmpty()) {
            status.setText(R.string.transport_configuration_required);
            return;
        }
        LinkedHashSet<String> ips = new LinkedHashSet<>(preferences.getStringSet(SAVED_IPS,
                new LinkedHashSet<>()));
        ips.add(value);
        preferences.edit().putStringSet(SAVED_IPS, ips).apply();
        selectedIps.clear();
        selectedIps.add(value);
        renderIpList();
        status.setText(R.string.transport_configuration_saved);
    }

    private void removeSelectedIps() {
        if (selectedIps.isEmpty()) {
            Toast.makeText(getContext(), R.string.transport_select_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        LinkedHashSet<String> ips = new LinkedHashSet<>(preferences.getStringSet(SAVED_IPS,
                new LinkedHashSet<>()));
        String active = preferences.getString(TransportSessionManager.ACTIVE_IP, "");
        boolean removeActive = selectedIps.contains(active);
        ips.removeAll(selectedIps);
        preferences.edit().putStringSet(SAVED_IPS, ips).apply();
        if (removeActive) sessionManager.clearEndpoint(getContext());
        selectedIps.clear();
        renderIpList();
        renderStatus();
    }

    private void useSelectedIp() {
        if (selectedIps.size() != 1) {
            Toast.makeText(getContext(), R.string.transport_select_one_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        String value = selectedIps.iterator().next();
        try {
            sessionManager.selectEndpoint(getContext(), value, DJISampleApplication.getAircraftInstance());
            host.setText(value);
            renderIpList();
            renderStatus();
        } catch (Exception failure) {
            status.setText(getContext().getString(R.string.transport_start_failed,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage()));
        }
    }

    private void renderIpList() {
        ipList.removeAllViews();
        String active = preferences.getString(TransportSessionManager.ACTIVE_IP, "");
        for (String ip : preferences.getStringSet(SAVED_IPS, new LinkedHashSet<>())) {
            TextView row = new TextView(getContext());
            row.setText((selectedIps.contains(ip) ? "☑ " : "☐ ") + ip
                    + (ip.equals(active) ? "  • IN USE" : ""));
            row.setTextSize(17f);
            row.setTextColor(Color.BLACK);
            row.setPadding(12, 18, 12, 18);
            row.setOnClickListener(view -> {
                if (!selectedIps.add(ip)) selectedIps.remove(ip);
                renderIpList();
            });
            ipList.addView(row);
        }
    }

    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, 1000);
        }
    };

    private void renderStatus() {
        TransportRuntime runtime = sessionManager.getRuntime();
        String active = preferences.getString(TransportSessionManager.ACTIVE_IP, "").trim();
        if (active.isEmpty()) {
            host.setEnabled(true);
            status.setText("SELECT AN EDGE ENDPOINT\nTransport starts automatically after IN USE is selected.");
            return;
        }
        if (runtime == null) {
            host.setEnabled(true);
            status.setText("AUTO-START ENABLED\nEndpoint: " + active
                    + "\nWaiting for a connected aircraft.");
            return;
        }
        host.setEnabled(false);
        StringBuilder text = new StringBuilder("RUNNING\nendpoint: ").append(active)
                .append("\nsession: ").append(runtime.getSessionId()).append("\ncallback rates:");
        List<CallbackRateSnapshot> snapshots = runtime.getRates().snapshots();
        if (snapshots.isEmpty()) text.append(" waiting for DJI callbacks");
        for (CallbackRateSnapshot item : snapshots) {
            text.append('\n').append(item.getSourceKey()).append(": ")
                    .append(String.format(Locale.US, "%.2f Hz", item.getMeanHz()));
        }
        text.append("\ntelemetry queue drops: ").append(runtime.getTelemetryDrops());
        text.append("\nvideo callback drops primary/secondary: ")
                .append(runtime.getPrimaryVideoDrops()).append('/').append(runtime.getSecondaryVideoDrops());
        text.append("\nvideo socket errors: ").append(runtime.getVideoSocketErrors());
        status.setText(text.toString());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try { sessionManager.reconcile(getContext(), DJISampleApplication.getAircraftInstance()); }
        catch (Exception ignored) { }
        renderStatus();
        handler.post(statusRefresh);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(statusRefresh);
        super.onDetachedFromWindow();
    }

    @Override public int getDescription() { return R.string.transport_title; }
    @NonNull @Override public String getHint() { return getClass().getSimpleName() + ".java"; }
}
