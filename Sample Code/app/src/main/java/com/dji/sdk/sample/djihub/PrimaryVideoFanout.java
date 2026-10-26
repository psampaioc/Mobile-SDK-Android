package com.dji.sdk.sample.djihub;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, per-consumer Primary-video delivery below the single DJI callback. */
final class PrimaryVideoFanout implements AutoCloseable {
    interface Subscription extends AutoCloseable {
        @Override void close();
    }

    interface Listener {
        void onVideo(byte[] data, int size, String source);
        default void onDiscontinuity() { }
    }

    private final int capacity;
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    PrimaryVideoFanout(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    Subscription add(Listener listener) {
        if (listener == null) throw new NullPointerException("listener");
        Entry entry = new Entry(listener, capacity);
        entries.add(entry);
        return () -> {
            if (entries.remove(entry)) entry.close();
        };
    }

    void dispatch(byte[] data, int size, String source) {
        if (data == null) throw new NullPointerException("data");
        if (size < 0 || size > data.length) throw new IllegalArgumentException("invalid size");
        if (size == 0) return;
        Packet packet = new Packet(Arrays.copyOf(data, size), size, source == null ? "UNKNOWN" : source);
        for (Entry entry : entries) entry.offer(packet);
    }

    @Override public void close() {
        for (Entry entry : entries) entry.close();
        entries.clear();
    }

    private static final class Packet {
        final byte[] data;
        final int size;
        final String source;
        Packet(byte[] data, int size, String source) {
            this.data = data;
            this.size = size;
            this.source = source;
        }
    }

    private static final class Entry {
        private final Listener listener;
        private final int capacity;
        private final ArrayDeque<Packet> queue = new ArrayDeque<>();
        private final AtomicLong droppedPackets = new AtomicLong();
        private final Thread worker;
        private boolean running = true;
        private boolean discontinuityPending;

        Entry(Listener listener, int capacity) {
            this.listener = listener;
            this.capacity = capacity;
            worker = new Thread(this::run, "dji-primary-video-consumer");
            worker.setDaemon(true);
            worker.start();
        }

        void offer(Packet packet) {
            synchronized (queue) {
                if (!running) return;
                while (queue.size() >= capacity) {
                    queue.removeFirst();
                    droppedPackets.incrementAndGet();
                    discontinuityPending = true;
                }
                queue.addLast(packet);
                queue.notifyAll();
            }
        }

        void close() {
            synchronized (queue) {
                if (!running) return;
                running = false;
                queue.clear();
                queue.notifyAll();
            }
            if (Thread.currentThread() != worker) {
                try { worker.join(2000L); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        }

        private void run() {
            while (true) {
                Packet packet;
                boolean discontinuity;
                synchronized (queue) {
                    while (running && queue.isEmpty()) {
                        try { queue.wait(); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                    }
                    if (!running) return;
                    packet = queue.removeFirst();
                    discontinuity = discontinuityPending;
                    discontinuityPending = false;
                }
                try {
                    if (discontinuity) listener.onDiscontinuity();
                    listener.onVideo(packet.data, packet.size, packet.source);
                } catch (RuntimeException ignored) {
                    // A consumer failure must not stop Primary delivery to its peers.
                }
            }
        }
    }
}
