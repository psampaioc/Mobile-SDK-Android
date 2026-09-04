package com.dji.sdk.sample.transport.network;

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
    private final ArrayBlockingQueue<byte[]> queue;
    private final InetSocketAddress destination;
    private final DatagramSocket socket;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile boolean running = true;

    public BoundedUdpSender(String host, int port, int capacity, String threadName) throws Exception {
        queue = new ArrayBlockingQueue<>(capacity);
        destination = new InetSocketAddress(InetAddress.getByName(host), port);
        socket = new DatagramSocket();
        socket.setSendBufferSize(2 * 1024 * 1024);
        worker = new Thread(this::run, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    public boolean offer(byte[] bytes) {
        if (!running || bytes == null) return false;
        if (queue.offer(bytes)) return true;
        queue.poll();
        dropped.incrementAndGet();
        return queue.offer(bytes);
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            try {
                byte[] bytes = queue.poll(100, TimeUnit.MILLISECONDS);
                if (bytes != null) socket.send(new DatagramPacket(bytes, bytes.length, destination));
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

    @Override public void close() {
        running = false;
        try { worker.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        socket.close();
    }
}
