package com.dji.sdk.sample.transport.network;

import com.dji.sdk.sample.transport.clock.MonotonicClock;
import com.dji.sdk.sample.transport.TransportEgressMetrics;

import org.json.JSONObject;

import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Replies to edge-originated NTP-style probes without altering either monotonic clock. */
public final class AndroidClockResponder implements Closeable {
    private final MonotonicClock clock;
    private final DatagramSocket socket;
    private final Thread worker;
    private final TransportEgressMetrics egressMetrics;
    private final AtomicLong pongsSent = new AtomicLong();
    private volatile boolean running = true;

    public AndroidClockResponder(MonotonicClock clock, int port) throws Exception {
        this(clock, port, null);
    }

    public AndroidClockResponder(MonotonicClock clock, int port,
            TransportEgressMetrics egressMetrics) throws Exception {
        this.clock = clock;
        this.egressMetrics = egressMetrics;
        socket = new DatagramSocket(port);
        socket.setSoTimeout(250);
        worker = new Thread(this::run, "dji-clock-responder");
        worker.setDaemon(true);
        worker.start();
    }

    /** Package-visible only for the loopback socket contract test. Runtime uses configured UDP 5502. */
    int getLocalPort() {
        return socket.getLocalPort();
    }

    private void run() {
        byte[] buffer = new byte[TransportConfig.MAX_JSON_DATAGRAM_BYTES];
        while (running) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(request);
                long received = clock.nowNanos();
                JSONObject input = new JSONObject(new String(request.getData(), request.getOffset(),
                        request.getLength(), StandardCharsets.UTF_8));
                if (!"clock_ping".equals(input.optString("type"))) continue;
                byte[] bytes = ClockPongEncoder.encode(input.optString("session", "edge-clock"),
                        input.getLong("seq"), input.getLong("t0_edge_send_mono_ns"),
                        received, clock.nowNanos());
                socket.send(ClockReplyTarget.forRequest(request, bytes));
                pongsSent.incrementAndGet();
                if (egressMetrics != null) egressMetrics.recordClock(bytes.length);
            } catch (java.net.SocketTimeoutException ignored) {
                // Periodically observe close().
            } catch (Exception ignored) {
                // Invalid/untrusted UDP input must not stop transport.
            }
        }
    }

    /** Replies accepted by Android's UDP socket; this is not an Edge acknowledgement. */
    public long getPongsSent() { return pongsSent.get(); }

    @Override public void close() {
        running = false;
        socket.close();
        try { worker.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
