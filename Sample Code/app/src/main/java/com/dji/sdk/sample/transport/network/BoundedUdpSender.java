package com.dji.sdk.sample.transport.network;

import android.os.SystemClock;
import java.io.Closeable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded non-blocking producer queue with an oldest-first drop policy. */
public final class BoundedUdpSender implements Closeable {
    public interface AcceptedListener {
        void onAccepted(int bytes, long acceptedAtNanos);
    }

    private final ArrayBlockingQueue<byte[]> queue;
    private final InetSocketAddress destination;
    private final DatagramSocket socket;
    private final Thread worker;
    private final AcceptedListener acceptedListener;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong acceptedDatagrams = new AtomicLong();
    private final AtomicLong acceptedBytes = new AtomicLong();
    private final AtomicLong highWaterMark = new AtomicLong();
    private final AtomicLong maxSendDurationNanos = new AtomicLong();
    private volatile long lastAcceptedSendNanos;
    private volatile long lastSendDurationNanos;
    private volatile boolean running = true;

    public BoundedUdpSender(String host, int port, int capacity, String threadName) throws Exception {
        this(host, port, capacity, threadName, null);
    }

    public BoundedUdpSender(String host, int port, int capacity, String threadName,
            AcceptedListener acceptedListener) throws Exception {
        queue = new ArrayBlockingQueue<>(capacity);
        destination = new InetSocketAddress(InetAddress.getByName(host), port);
        socket = new DatagramSocket();
        socket.setSendBufferSize(2 * 1024 * 1024);
        this.acceptedListener = acceptedListener;
        worker = new Thread(this::run, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    public boolean offer(byte[] bytes) {
        if (!running || bytes == null) return false;
        if (queue.offer(bytes)) { updateHighWaterMark(queue.size()); return true; }
        queue.poll();
        dropped.incrementAndGet();
        boolean accepted = queue.offer(bytes);
        if (accepted) updateHighWaterMark(queue.size());
        return accepted;
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            try {
                byte[] bytes = queue.poll(100, TimeUnit.MILLISECONDS);
                if (bytes != null) {
                    long started = SystemClock.elapsedRealtimeNanos();
                    socket.send(new DatagramPacket(bytes, bytes.length, destination));
                    long acceptedAt = SystemClock.elapsedRealtimeNanos();
                    long duration = Math.max(0L, acceptedAt - started);
                    lastSendDurationNanos = duration;
                    updateMaximum(maxSendDurationNanos, duration);
                    acceptedDatagrams.incrementAndGet();
                    acceptedBytes.addAndGet(bytes.length);
                    lastAcceptedSendNanos = acceptedAt;
                    if (acceptedListener != null) {
                        try { acceptedListener.onAccepted(bytes.length, acceptedAt); }
                        catch (RuntimeException ignored) { }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                errors.incrementAndGet();
            }
        }
    }

    public long getDroppedCount() { return dropped.get(); }
    public long getErrorCount() { return errors.get(); }
    /** Accepted by Android's UDP socket; this is not an Edge receipt acknowledgement. */
    public long getAcceptedDatagramCount() { return acceptedDatagrams.get(); }
    /** Bytes accepted by Android's UDP socket; this is not an Edge receipt acknowledgement. */
    public long getAcceptedByteCount() { return acceptedBytes.get(); }
    public Snapshot snapshot() {
        return new Snapshot(queue.size(), highWaterMark.get(), dropped.get(), errors.get(),
                acceptedDatagrams.get(), acceptedBytes.get(), lastAcceptedSendNanos,
                lastSendDurationNanos, maxSendDurationNanos.getAndSet(0L));
    }

    private static void updateMaximum(AtomicLong target, long value) {
        long current;
        do { current = target.get(); if (value <= current) return; }
        while (!target.compareAndSet(current, value));
    }
    private void updateHighWaterMark(long value) { updateMaximum(highWaterMark, value); }

    public static final class Snapshot {
        public final int queueDepth;
        public final long highWaterMark, dropped, errors, acceptedDatagrams, acceptedBytes;
        public final long lastAcceptedSendNanos, lastSendDurationNanos, maxSendDurationNanos;
        Snapshot(int queueDepth, long highWaterMark, long dropped, long errors,
                long acceptedDatagrams, long acceptedBytes, long lastAcceptedSendNanos,
                long lastSendDurationNanos, long maxSendDurationNanos) {
            this.queueDepth = queueDepth; this.highWaterMark = highWaterMark;
            this.dropped = dropped; this.errors = errors;
            this.acceptedDatagrams = acceptedDatagrams; this.acceptedBytes = acceptedBytes;
            this.lastAcceptedSendNanos = lastAcceptedSendNanos;
            this.lastSendDurationNanos = lastSendDurationNanos;
            this.maxSendDurationNanos = maxSendDurationNanos;
        }
    }

    @Override public void close() {
        running = false;
        try { worker.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        socket.close();
    }
}
