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
import com.dji.sdk.sample.transport.TransportForegroundService;
import com.dji.sdk.sample.transport.TransportEgressMetrics;
import com.dji.sdk.sample.transport.TransportSessionManager;
import com.dji.sdk.sample.transport.diagnostics.CallbackRateSnapshot;
import com.dji.sdk.sample.transport.video.PrimaryLiveFeedMetrics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Endpoint selection and runtime status for the process-wide passive transport. */
public final class TransportView extends LinearLayout implements PresentableView {
    private static final String SAVED_IPS = "saved_edge_ips";
    private final EditText host;
    private final TextView status;
    private final TextView primaryState;
    private final TextView inputMetrics;
    private final TextView pipelineMetrics;
    private final TextView udpMetrics;
    private final TextView evidenceMetrics;
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
        primaryState = findViewById(R.id.transport_primary_state);
        inputMetrics = findViewById(R.id.transport_input_metrics);
        pipelineMetrics = findViewById(R.id.transport_pipeline_metrics);
        udpMetrics = findViewById(R.id.transport_udp_metrics);
        evidenceMetrics = findViewById(R.id.transport_evidence_metrics);
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
        try {
            new com.dji.sdk.sample.transport.network.TransportConfig(value);
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
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
            renderInactiveDashboard("NO ENDPOINT", "Select an Edge IP to arm passive transport.");
            return;
        }
        if (runtime == null) {
            host.setEnabled(true);
            status.setText("AUTO-START ENABLED\nEndpoint: " + active
                    + "\nWaiting for a connected aircraft.");
            renderInactiveDashboard("WAITING FOR AIRCRAFT", "The app will start transport when DJI connects.");
            return;
        }
        // Keep the editor available while packets are flowing. The operator may add a new
        // endpoint, select it, then press USE; TransportSessionManager performs the bounded
        // stop-and-replace transition instead of changing a live socket destination in place.
        host.setEnabled(true);
        StringBuilder text = new StringBuilder("RUNNING  •  ").append(active)
                .append("\nSession ").append(runtime.getSessionId());
        List<CallbackRateSnapshot> snapshots = runtime.getRates().snapshots();
        text.append("\nCallback rates:");
        if (snapshots.isEmpty()) text.append(" waiting for DJI callbacks");
        for (CallbackRateSnapshot item : snapshots) {
            text.append('\n').append(item.getSourceKey()).append(": ")
                    .append(String.format(Locale.US, "%.2f Hz", item.getMeanHz()));
        }
        text.append("\nTelemetry queue drops: ").append(runtime.getTelemetryDrops());
        status.setText(text.toString());
        renderLiveDashboard(runtime);
    }

    private void renderInactiveDashboard(String state, String detail) {
        primaryState.setText(state + "\n" + detail);
        primaryState.setTextColor(Color.rgb(58, 77, 94));
        inputMetrics.setText("INPUT\nCallbacks —\nAccepted —\nDrops —");
        pipelineMetrics.setText("H.264\nNALs —\nAccess units —\nNative cadence —\nWorker —");
        udpMetrics.setText("UDP 5600\nRTP accepted —\nMetadata 5501 —\nSocket errors —");
        evidenceMetrics.setText("LOCAL EVIDENCE\nNo local stream is active. Edge receipt is shown only on the Edge dashboard.");
    }

    private void renderLiveDashboard(TransportRuntime runtime) {
        com.dji.sdk.sample.transport.network.VideoStreamSender.Diagnostics data = runtime.getPrimaryDiagnostics();
        String state = runtime.getPrimaryTransportState();
        boolean fault = "OUTAGE".equals(state);
        primaryState.setText("PRIMARY • " + state + "\n" + runtime.getPrimaryTransportDetail());
        primaryState.setTextColor(fault ? Color.rgb(184, 54, 44) : Color.rgb(45, 128, 73));
        inputMetrics.setText("INPUT\nCallbacks " + data.callbacks + "\nAccepted " + data.acceptedChunks
                + "\nDrops " + data.ingestion.droppedChunks + "  • queue peak " + data.ingestion.highWaterMark);
        pipelineMetrics.setText("H.264\nNALs " + data.parser.emittedNalUnits + "  • IDR " + data.parser.idrNalUnits
                + "\nAccess units " + data.accessUnits + "\nNative cadence "
                + observedFps(runtime.getPrimaryLiveFeedSnapshot()) + "\nWorker failures " + data.workerFailures);
        TransportEgressMetrics.Snapshot egress = runtime.getEgressSnapshot();
        udpMetrics.setText("TOTAL EGRESS → EDGE\n" + megabitsPerSecond(egress.totalRate)
                + "  • local socket accepted\nRTP 5600 " + bytes(egress.primaryRtpAcceptedBytes)
                + "  • metadata 5501 " + bytes(egress.metadataAcceptedBytes)
                + "\nTelemetry/health 5500 " + bytes(egress.telemetryAcceptedBytes)
                + "  • clock 5502 " + bytes(egress.clockAcceptedBytes)
                + "\nTotal " + bytes(egress.totalAcceptedBytes) + "  • video socket errors "
                + runtime.getVideoSocketErrors());
        evidenceMetrics.setText("LOCAL EVIDENCE\nLast accepted RTP " + ago(data.socket.lastRtpAcceptedNanos)
                + "\nWi-Fi performance request " + wifiState()
                + "\nLast socket error " + empty(data.socket.lastRtpError)
                + "\nRuntime reconcile " + empty(sessionManager.getLastReconcileFailure())
                + "\nEdge receipt, decode and ROS publication are measured on the Edge dashboard.");
    }

    private static String ago(long nanos) {
        if (nanos <= 0) return "never";
        long seconds = Math.max(0L, (android.os.SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000_000L);
        return seconds + " s ago";
    }

    private static String empty(String value) { return value == null || value.isEmpty() ? "none" : value; }

    private static String wifiState() {
        com.dji.sdk.sample.transport.TransportWifiLock.Snapshot value =
                TransportForegroundService.getWifiLockSnapshot();
        return value.state + ("NONE".equals(value.requestedMode) ? "" : " • " + value.requestedMode)
                + (value.detail.isEmpty() ? "" : " • " + value.detail);
    }

    private static String bytes(long value) {
        return String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0));
    }

    private static String megabitsPerSecond(com.dji.sdk.sample.transport.metrics.ByteRateMeter.Snapshot rate) {
        return rate.available ? String.format(Locale.US, "%.2f Mb/s", rate.bitsPerSecond / 1_000_000.0d)
                : "Measuring…";
    }

    private static String observedFps(PrimaryLiveFeedMetrics.Snapshot sample) {
        return sample.observed ? String.format(Locale.US, "%.2f fps observed", sample.framesPerSecond)
                : "UNOBSERVED";
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
