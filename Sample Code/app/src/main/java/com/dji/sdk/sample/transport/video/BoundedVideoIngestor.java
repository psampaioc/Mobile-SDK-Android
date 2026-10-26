package com.dji.sdk.sample.transport.video;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking boundary for a VideoFeeder callback.
 *
 * <p>When full, the oldest chunk is discarded in favor of the newest data. Chunk sequence gaps let
 * the downstream parser detect the discontinuity and wait for a clean IDR recovery point.</p>
 */
public final class BoundedVideoIngestor {
    private final ArrayBlockingQueue<EncodedVideoChunk> queue;
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicLong acceptedChunks = new AtomicLong();
    private final AtomicLong acceptedBytes = new AtomicLong();
    private final AtomicLong droppedChunks = new AtomicLong();
    private final AtomicLong droppedBytes = new AtomicLong();
    private final AtomicLong highWaterMark = new AtomicLong();
    private final AtomicLong minimumChunkBytes = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maximumChunkBytes = new AtomicLong();
    private final String[] initialHexSamples = new String[4];
    private int initialHexSampleCount;
    private final AtomicLong annexBThreeByteSignatures = new AtomicLong();
    private final AtomicLong annexBFourByteSignatures = new AtomicLong();

    public BoundedVideoIngestor(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Copies and enqueues {@code size} bytes without waiting for queue space. */
    public boolean offer(byte[] source, int size, long receiveTimeNanos) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (size < 0 || size > source.length) {
            throw new IllegalArgumentException("invalid size");
        }
        if (size == 0) {
            return false;
        }

        EncodedVideoChunk chunk = new EncodedVideoChunk(
                Arrays.copyOf(source, size), receiveTimeNanos, nextSequence.getAndIncrement());
        while (!queue.offer(chunk)) {
            EncodedVideoChunk dropped = queue.poll();
            if (dropped != null) {
                droppedChunks.incrementAndGet();
                droppedBytes.addAndGet(dropped.getData().length);
            }
        }
        acceptedChunks.incrementAndGet();
        acceptedBytes.addAndGet(size);
        updateMinimum(minimumChunkBytes, size);
        updateMaximum(maximumChunkBytes, size);
        captureInitialDiagnostics(source, size);
        updateHighWaterMark(queue.size());
        return true;
    }

    public EncodedVideoChunk poll() {
        return queue.poll();
    }

    /** Drops queued callback fragments after an upstream discontinuity. */
    public void discardQueued() {
        EncodedVideoChunk chunk;
        while ((chunk = queue.poll()) != null) {
            droppedChunks.incrementAndGet();
            droppedBytes.addAndGet(chunk.getData().length);
        }
    }

    /** Makes the next offered chunk expose an upstream discontinuity to the drain worker. */
    public void markDiscontinuity() {
        nextSequence.incrementAndGet();
    }

    public int size() {
        return queue.size();
    }

    public Counters snapshotCounters() {
        return snapshotCounters(-1L);
    }

    /**
     * Snapshot the bounded queue. The optional monotonic {@code nowNanos} makes backlog age
     * observable without changing the callback path; pass a negative value when unavailable.
     */
    public Counters snapshotCounters(long nowNanos) {
        long minimum = minimumChunkBytes.get();
        EncodedVideoChunk oldest = queue.peek();
        long oldestQueueAgeNanos = nowNanos < 0L || oldest == null ? -1L
                : Math.max(0L, nowNanos - oldest.getReceiveTimeNanos());
        return new Counters(queue.size(), acceptedChunks.get(), acceptedBytes.get(), droppedChunks.get(),
                droppedBytes.get(), highWaterMark.get(), minimum == Long.MAX_VALUE ? 0 : minimum,
                maximumChunkBytes.get(), initialHexSamples(), annexBThreeByteSignatures.get(),
                annexBFourByteSignatures.get(), oldestQueueAgeNanos);
    }

    private synchronized void captureInitialDiagnostics(byte[] source, int size) {
        if (initialHexSampleCount >= initialHexSamples.length) return;
        int count = Math.min(size, 32);
        StringBuilder out = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            int value = source[i] & 0xff;
            if (value < 0x10) out.append('0');
            out.append(Integer.toHexString(value));
        }
        initialHexSamples[initialHexSampleCount++] = out.toString();
        // Parser diagnostics already count every emitted start code on the worker. Sampling the
        // first four callbacks keeps source framing evidence without scanning every DJI callback.
        countAnnexBSignatures(source, size);
    }

    private synchronized String initialHexSamples() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < initialHexSampleCount; i++) {
            if (i > 0) out.append(" | ");
            out.append(initialHexSamples[i]);
        }
        return out.toString();
    }

    private void countAnnexBSignatures(byte[] source, int size) {
        for (int index = 0; index + 2 < size; index++) {
            if (source[index] != 0 || source[index + 1] != 0) continue;
            if (source[index + 2] == 1) annexBThreeByteSignatures.incrementAndGet();
            else if (index + 3 < size && source[index + 2] == 0 && source[index + 3] == 1) {
                annexBFourByteSignatures.incrementAndGet();
            }
        }
    }

    private static void updateMinimum(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value >= current) return;
        } while (!target.compareAndSet(current, value));
    }

    private static void updateMaximum(AtomicLong target, long value) {
        long current;
        do {
            current = target.get();
            if (value <= current) return;
        } while (!target.compareAndSet(current, value));
    }

    private void updateHighWaterMark(long candidate) {
        long current;
        do {
            current = highWaterMark.get();
            if (candidate <= current) {
                return;
            }
        } while (!highWaterMark.compareAndSet(current, candidate));
    }

    public static final class Counters {
        public final int queueDepth;
        public final long acceptedChunks;
        public final long acceptedBytes;
        public final long droppedChunks;
        public final long droppedBytes;
        public final long highWaterMark;
        public final long minimumChunkBytes;
        public final long maximumChunkBytes;
        public final String initialHexSamples;
        public final long annexBThreeByteSignatures;
        public final long annexBFourByteSignatures;
        /** Age of the oldest queued callback at snapshot time, or -1 when empty/unsampled. */
        public final long oldestQueueAgeNanos;

        Counters(int queueDepth, long acceptedChunks, long acceptedBytes, long droppedChunks, long droppedBytes,
                long highWaterMark, long minimumChunkBytes, long maximumChunkBytes,
                String initialHexSamples, long annexBThreeByteSignatures,
                long annexBFourByteSignatures, long oldestQueueAgeNanos) {
            this.queueDepth = queueDepth; this.acceptedChunks = acceptedChunks;
            this.acceptedBytes = acceptedBytes;
            this.droppedChunks = droppedChunks;
            this.droppedBytes = droppedBytes;
            this.highWaterMark = highWaterMark;
            this.minimumChunkBytes = minimumChunkBytes;
            this.maximumChunkBytes = maximumChunkBytes;
            this.initialHexSamples = initialHexSamples;
            this.annexBThreeByteSignatures = annexBThreeByteSignatures;
            this.annexBFourByteSignatures = annexBFourByteSignatures;
            this.oldestQueueAgeNanos = oldestQueueAgeNanos;
        }
    }
}
