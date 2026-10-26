package com.dji.sdk.sample.recording;

import com.dji.sdk.sample.transport.video.H264AccessUnit;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous sidecar writer; hub callbacks never write to SAF storage directly. */
final class RecordingTelemetryWriter implements Closeable {
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong dropped = new AtomicLong();
    private final BufferedWriter writer;
    private final Thread worker;
    private volatile boolean running = true;

    RecordingTelemetryWriter(OutputStream output) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 32 * 1024);
        worker = new Thread(this::writeLoop, "dji-recording-sidecar"); worker.setDaemon(true); worker.start();
    }
    void lifecycle(String event, long monoNanos, String detail) { offer("{\"v\":1,\"type\":\"recording_" + event + "\",\"mono_ns\":" + monoNanos + ",\"detail\":\"" + escape(detail) + "\"}"); }
    void flight(long monoNanos, Double lat, boolean positionValid, Double lon, Double altitude, boolean altitudeValid, Float heading, boolean headingValid) {
        offer("{\"v\":1,\"type\":\"flight\",\"mono_ns\":" + monoNanos + ",\"fields\":{\"aircraft.latitude_deg\":" + field(lat, positionValid) + ",\"aircraft.longitude_deg\":" + field(lon, positionValid) + ",\"aircraft.altitude_m\":" + field(altitude, altitudeValid) + ",\"heading_deg\":" + field(heading, headingValid) + "}}");
    }
    void rtk(long monoNanos, Double lat, boolean valid, Double lon, boolean beingUsed) {
        offer("{\"v\":1,\"type\":\"rtk\",\"mono_ns\":" + monoNanos + ",\"fields\":{\"fusion.latitude_deg\":" + field(lat, valid) + ",\"fusion.longitude_deg\":" + field(lon, valid) + ",\"is_being_used\":{\"value\":" + beingUsed + ",\"valid\":true}}}");
    }
    long dropped() { return dropped.get(); }
    private static String field(Object value, boolean valid) { return "{\"value\":" + (valid && value != null ? value : "null") + ",\"valid\":" + valid + "}"; }
    private void offer(String value) { if (!running) return; if (!queue.offer(value)) { queue.poll(); dropped.incrementAndGet(); if (!queue.offer(value)) dropped.incrementAndGet(); } }
    private void writeLoop() { try { while (running || !queue.isEmpty()) { String line = queue.poll(250, TimeUnit.MILLISECONDS); if (line != null) { writer.write(line); writer.newLine(); } writer.flush(); } } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } catch (IOException ignored) { dropped.incrementAndGet(); } finally { try { writer.close(); } catch (IOException ignored) { } } }
    @Override public void close() throws IOException { running = false; try { worker.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); } }
    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
