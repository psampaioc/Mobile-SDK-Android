package com.dji.sdk.sample.diagnostics;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dji.common.battery.BatteryState;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.remotecontroller.HardwareState;
import dji.sdk.base.DJIDiagnostics;

/**
 * Passive, bounded evidence recorder for a physical motor-start attempt.
 *
 * <p>It consumes only {@link DjiDataHub} fan-out events. It never attaches a DJI setter
 * callback, sends no aircraft command, and performs no disk I/O on a DJI callback thread.
 * It uses the app-private files directory, which remains retrievable over USB with
 * {@code adb exec-out run-as com.dji.sdk.sample cat files/flight-diagnostics/flight-attempt-current.ndjson}.</p>
 */
public final class FlightAttemptDiagnosticLogger {
    private static final String DIRECTORY = "flight-diagnostics";
    private static final String FILE_NAME = "flight-attempt-current.ndjson";
    private static final int QUEUE_CAPACITY = 256;
    private static final long STATUS_INTERVAL_MS = 1_000L;
    private static final long RC_INPUT_INTERVAL_MS = 250L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;

    private static FlightAttemptDiagnosticLogger instance;

    private final ArrayBlockingQueue<String> records = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final File output;
    private final Thread worker;
    private CallbackMulticaster.Subscription subscription;
    private volatile boolean running = true;
    private volatile long lastStatusElapsedMs = -1L;
    private volatile long lastRcInputElapsedMs = -1L;
    private volatile long droppedRecords;
    /** Active DJI conditions only; unchanged conditions do not flood the local evidence file. */
    private final Map<String, String> activeDiagnostics = new HashMap<>();

    public static synchronized void start(@NonNull Context context) {
        if (instance != null) return;
        instance = new FlightAttemptDiagnosticLogger(context.getApplicationContext());
    }

    private FlightAttemptDiagnosticLogger(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        output = new File(directory, FILE_NAME);
        worker = new Thread(this::writeLoop, "flight-attempt-diagnostics");
        worker.setDaemon(true);
        worker.start();
        subscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
            @Override public void onStatusSnapshot(@NonNull DjiDataHub.StatusSnapshot snapshot) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastStatusElapsedMs < STATUS_INTERVAL_MS) return;
                lastStatusElapsedMs = now;
                offer(statusRecord(snapshot, now));
            }

            @Override public void onRemoteControllerState(@NonNull HardwareState state) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastRcInputElapsedMs < RC_INPUT_INTERVAL_MS) return;
                lastRcInputElapsedMs = now;
                offer(remoteControllerRecord(state, now));
            }

            @Override public void onDiagnostics(@NonNull List<DJIDiagnostics> diagnostics) {
                recordDiagnostics(diagnostics, SystemClock.elapsedRealtime());
            }

            @Override public void onDjiHomeUpdateResult(@NonNull DjiDataHub.DjiHomeUpdateResult result) {
                offer("{\"v\":1,\"type\":\"dji_home_update\",\"elapsed_ms\":"
                        + SystemClock.elapsedRealtime() + ",\"mode\":\""
                        + (result.automaticCurrentAircraftLocation ? "aircraft_current_location" : "custom_coordinate")
                        + "\",\"accepted\":" + result.accepted + ",\"detail\":\""
                        + escape(result.detail) + "\"}");
            }
        });
        offer("{\"v\":1,\"type\":\"logger_started\",\"elapsed_ms\":"
                + SystemClock.elapsedRealtime() + "}");
        DjiDataHub.getInstance().requestStatusSnapshot();
    }

    private void offer(String record) {
        if (!running) return;
        if (!records.offer(record)) {
            records.poll();
            droppedRecords++;
            if (!records.offer(record)) droppedRecords++;
        }
    }

    private void writeLoop() {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            running = false;
            return;
        }
        long written = 0L;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output, false), StandardCharsets.UTF_8), 16 * 1024)) {
            while (running || !records.isEmpty()) {
                String record = records.poll();
                if (record == null) {
                    try { Thread.sleep(100L); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
                byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
                if (written + bytes.length > MAX_FILE_BYTES) {
                    writer.write("{\"v\":1,\"type\":\"logger_capacity_reached\",\"dropped_records\":"
                            + droppedRecords + "}");
                    writer.newLine();
                    writer.flush();
                    running = false;
                    return;
                }
                writer.write(record);
                writer.newLine();
                written += bytes.length;
                writer.flush();
            }
        } catch (IOException ignored) {
            running = false;
        }
    }

    private static String statusRecord(DjiDataHub.StatusSnapshot snapshot, long now) {
        FlightControllerState state = snapshot.flightState;
        LocationCoordinate3D location = state == null ? null : state.getAircraftLocation();
        BatteryState batteryOne = snapshot.aircraftBatteryStates.get(0);
        BatteryState batteryTwo = snapshot.aircraftBatteryStates.get(1);
        int rcPercent = snapshot.remoteControllerBattery == null ? -1
                : snapshot.remoteControllerBattery.getRemainingChargeInPercent();
        return "{\"v\":1,\"type\":\"flight_status\",\"elapsed_ms\":" + now
                + ",\"aircraft_connected\":" + snapshot.aircraftConnected
                + ",\"cendence_connected\":" + snapshot.remoteControllerConnected
                + ",\"ocusync_available\":" + snapshot.ocuSyncAvailable
                + ",\"ocusync_quality\":" + snapshot.ocuSyncDownlinkQuality
                + ",\"flight_sample_age_ms\":" + age(now, snapshot.flightStateElapsedMs)
                + ",\"rtk_sample_age_ms\":" + age(now, snapshot.rtkStateElapsedMs)
                + ",\"rc_battery_percent\":" + rcPercent
                + ",\"battery_1_percent\":" + batteryPercent(batteryOne)
                + ",\"battery_2_percent\":" + batteryPercent(batteryTwo)
                + ",\"motors_on\":" + (state != null && state.areMotorsOn())
                + ",\"is_flying\":" + (state != null && state.isFlying())
                + ",\"going_home\":" + (state != null && state.isGoingHome())
                + ",\"flight_mode\":\"" + escape(state == null ? "" : String.valueOf(state.getFlightMode()))
                + "\",\"gps_level\":\"" + escape(state == null ? "" : String.valueOf(state.getGPSSignalLevel()))
                + "\",\"gps_satellites\":" + (state == null ? -1 : state.getSatelliteCount())
                + ",\"rtk_solution\":\"" + escape(snapshot.rtkState == null ? "" : String.valueOf(snapshot.rtkState.getPositioningSolution()))
                + "\",\"rtk_satellites\":" + (snapshot.rtkState == null ? -1 : snapshot.rtkState.getSatelliteCount())
                + ",\"latitude_deg\":" + coordinate(location, true)
                + ",\"longitude_deg\":" + coordinate(location, false)
                + ",\"altitude_m\":" + altitude(location)
                + ",\"heading_deg\":" + (state == null ? -1 : state.getAircraftHeadDirection()) + "}";
    }

    private static String remoteControllerRecord(HardwareState state, long now) {
        return "{\"v\":1,\"type\":\"rc_input\",\"elapsed_ms\":" + now
                + ",\"flight_mode_switch\":\"" + escape(String.valueOf(state.getFlightModeSwitch()))
                + "\",\"go_home_pressed\":" + state.getGoHomeButton().isClicked()
                + ",\"left_horizontal\":" + state.getLeftStick().getHorizontalPosition()
                + ",\"left_vertical\":" + state.getLeftStick().getVerticalPosition()
                + ",\"right_horizontal\":" + state.getRightStick().getHorizontalPosition()
                + ",\"right_vertical\":" + state.getRightStick().getVerticalPosition() + "}";
    }

    /** Writes every distinct SDK diagnostic as it appears and records when it clears. */
    private synchronized void recordDiagnostics(@NonNull List<DJIDiagnostics> diagnostics, long now) {
        Set<String> current = new HashSet<>();
        for (DJIDiagnostics diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            String type = String.valueOf(diagnostic.getType());
            String code = String.valueOf(diagnostic.getCode());
            String reason = diagnostic.getReason();
            String key = type + "|" + code + "|" + reason;
            current.add(key);
            if (activeDiagnostics.containsKey(key)) continue;
            activeDiagnostics.put(key, reason);
            offer("{\"v\":1,\"type\":\"dji_diagnostic_active\",\"elapsed_ms\":" + now
                    + ",\"diagnostic_type\":\"" + escape(type) + "\",\"code\":\""
                    + escape(code) + "\",\"reason\":\"" + escape(reason) + "\"}");
        }
        for (String key : new HashSet<>(activeDiagnostics.keySet())) {
            if (current.contains(key)) continue;
            String[] fields = key.split("\\|", 3);
            offer("{\"v\":1,\"type\":\"dji_diagnostic_cleared\",\"elapsed_ms\":" + now
                    + ",\"diagnostic_type\":\"" + escape(fields.length > 0 ? fields[0] : "")
                    + "\",\"code\":\"" + escape(fields.length > 1 ? fields[1] : "")
                    + "\",\"reason\":\"" + escape(activeDiagnostics.remove(key)) + "\"}");
        }
    }

    private static long age(long now, long received) { return received < 0L ? -1L : now - received; }
    private static int batteryPercent(BatteryState state) {
        return state == null ? -1 : state.getChargeRemainingInPercent();
    }
    private static String coordinate(LocationCoordinate3D location, boolean latitude) {
        if (location == null) return "null";
        double value = latitude ? location.getLatitude() : location.getLongitude();
        return Double.isFinite(value) ? String.valueOf(value) : "null";
    }
    private static String altitude(LocationCoordinate3D location) {
        if (location == null || !Double.isFinite(location.getAltitude())) return "null";
        return String.valueOf(location.getAltitude());
    }
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
