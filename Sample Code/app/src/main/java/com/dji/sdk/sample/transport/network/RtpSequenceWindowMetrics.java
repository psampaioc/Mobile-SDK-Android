package com.dji.sdk.sample.transport.network;

/** In-memory one-second RTP continuity evidence; never serialized onto the Edge wire contract. */
public final class RtpSequenceWindowMetrics {
    private int previousGenerated = -1;
    private int previousAccepted = -1;
    private int firstGenerated = -1;
    private int lastGenerated = -1;
    private int firstAccepted = -1;
    private int lastAccepted = -1;
    private long generatedPackets;
    private long generatedGaps;
    private long acceptedPackets;
    private long acceptedBytes;
    private long acceptedGaps;
    private long sendFailures;

    public synchronized void recordGenerated(int sequence) {
        int value = sequence & 0xffff;
        if (firstGenerated < 0) firstGenerated = value;
        if (previousGenerated >= 0 && value != next(previousGenerated)) generatedGaps++;
        previousGenerated = value;
        lastGenerated = value;
        generatedPackets++;
    }

    public synchronized void recordAccepted(int sequence, int bytes) {
        int value = sequence & 0xffff;
        if (firstAccepted < 0) firstAccepted = value;
        if (previousAccepted >= 0 && value != next(previousAccepted)) acceptedGaps++;
        previousAccepted = value;
        lastAccepted = value;
        acceptedPackets++;
        acceptedBytes += Math.max(0, bytes);
    }

    public synchronized void recordSendFailure() {
        sendFailures++;
    }

    /** Drains only the one-second aggregate; continuity baselines intentionally survive windows. */
    public synchronized Snapshot drain() {
        Snapshot value = new Snapshot(firstGenerated, lastGenerated, generatedPackets, generatedGaps,
                firstAccepted, lastAccepted, acceptedPackets, acceptedBytes, acceptedGaps, sendFailures);
        firstGenerated = -1;
        lastGenerated = -1;
        firstAccepted = -1;
        lastAccepted = -1;
        generatedPackets = 0L;
        generatedGaps = 0L;
        acceptedPackets = 0L;
        acceptedBytes = 0L;
        acceptedGaps = 0L;
        sendFailures = 0L;
        return value;
    }

    private static int next(int sequence) { return (sequence + 1) & 0xffff; }

    public static final class Snapshot {
        public final int firstGeneratedSequence;
        public final int lastGeneratedSequence;
        public final long generatedPackets;
        public final long generatedGaps;
        public final int firstAcceptedSequence;
        public final int lastAcceptedSequence;
        public final long acceptedPackets;
        public final long acceptedBytes;
        public final long acceptedGaps;
        public final long sendFailures;

        Snapshot(int firstGeneratedSequence, int lastGeneratedSequence, long generatedPackets,
                long generatedGaps, int firstAcceptedSequence, int lastAcceptedSequence,
                long acceptedPackets, long acceptedBytes, long acceptedGaps, long sendFailures) {
            this.firstGeneratedSequence = firstGeneratedSequence;
            this.lastGeneratedSequence = lastGeneratedSequence;
            this.generatedPackets = generatedPackets;
            this.generatedGaps = generatedGaps;
            this.firstAcceptedSequence = firstAcceptedSequence;
            this.lastAcceptedSequence = lastAcceptedSequence;
            this.acceptedPackets = acceptedPackets;
            this.acceptedBytes = acceptedBytes;
            this.acceptedGaps = acceptedGaps;
            this.sendFailures = sendFailures;
        }
    }
}
