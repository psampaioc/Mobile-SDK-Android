package com.dji.sdk.sample.transport.rtp;

import com.dji.sdk.sample.transport.video.H264AccessUnit;
import com.dji.sdk.sample.transport.video.H264NalUnit;

/** RFC 6184 packetizer using single-NAL and FU-A packetization modes. */
public final class H264RtpPacketizer {
    public interface PacketSink {
        void onRtpPacket(RtpPacket packet);
    }

    public static final int RTP_HEADER_BYTES = 12;
    private static final int FU_A_HEADER_BYTES = 2;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long H264_CLOCK_RATE = 90_000L;

    private final int mtu;
    private final int payloadType;
    private final int ssrc;
    private int sequenceNumber;
    private final long initialTimestamp;
    private long clockOriginNanos = Long.MIN_VALUE;
    private volatile long emittedPackets;
    private volatile long fragmentedNalUnits;
    private volatile long rejectedNalUnits;

    public H264RtpPacketizer(int mtu, int payloadType, int ssrc, int initialSequenceNumber,
            long initialTimestamp) {
        if (mtu < RTP_HEADER_BYTES + FU_A_HEADER_BYTES + 1) {
            throw new IllegalArgumentException("MTU too small");
        }
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("invalid payload type");
        }
        this.mtu = mtu;
        this.payloadType = payloadType;
        this.ssrc = ssrc;
        this.sequenceNumber = initialSequenceNumber & 0xffff;
        this.initialTimestamp = initialTimestamp & 0xffffffffL;
    }

    public void packetize(H264AccessUnit accessUnit, PacketSink sink) {
        if (accessUnit == null || sink == null) {
            throw new NullPointerException();
        }
        if (clockOriginNanos == Long.MIN_VALUE) {
            clockOriginNanos = accessUnit.getReceiveTimeNanos();
        }
        long timestamp = rtpTimestamp(accessUnit.getReceiveTimeNanos());
        for (int nalIndex = 0; nalIndex < accessUnit.getNalUnits().size(); nalIndex++) {
            H264NalUnit nal = accessUnit.getNalUnits().get(nalIndex);
            byte[] data = nal.getData();
            boolean finalNal = nalIndex == accessUnit.getNalUnits().size() - 1;
            if (data.length <= mtu - RTP_HEADER_BYTES) {
                emitSingle(data, timestamp, finalNal, accessUnit, sink);
            } else if (data.length > 1) {
                emitFuA(data, timestamp, finalNal, accessUnit, sink);
                fragmentedNalUnits++;
            } else {
                rejectedNalUnits++;
            }
        }
    }

    public Counters snapshotCounters() {
        return new Counters(emittedPackets, fragmentedNalUnits, rejectedNalUnits);
    }

    private long rtpTimestamp(long receiveTimeNanos) {
        long delta = receiveTimeNanos - clockOriginNanos;
        if (delta < 0) {
            delta = 0;
        }
        long wholeSeconds = delta / NANOS_PER_SECOND;
        long remainder = delta % NANOS_PER_SECOND;
        long ticks = wholeSeconds * H264_CLOCK_RATE
                + remainder * H264_CLOCK_RATE / NANOS_PER_SECOND;
        return (initialTimestamp + ticks) & 0xffffffffL;
    }

    private void emitSingle(byte[] nal, long timestamp, boolean marker,
            H264AccessUnit accessUnit, PacketSink sink) {
        byte[] packet = new byte[RTP_HEADER_BYTES + nal.length];
        int sequence = writeHeader(packet, timestamp, marker);
        System.arraycopy(nal, 0, packet, RTP_HEADER_BYTES, nal.length);
        sink.onRtpPacket(new RtpPacket(packet, sequence, timestamp, marker, ssrc,
                accessUnit.getReceiveTimeNanos(), accessUnit.getCompleteReceiveTimeNanos(),
                accessUnit.isKeyFrame()));
        emittedPackets++;
    }

    private void emitFuA(byte[] nal, long timestamp, boolean finalNal,
            H264AccessUnit accessUnit, PacketSink sink) {
        int maxFragmentBytes = mtu - RTP_HEADER_BYTES - FU_A_HEADER_BYTES;
        int nalHeader = nal[0] & 0xff;
        int fuIndicator = (nalHeader & 0xe0) | 28;
        int nalType = nalHeader & 0x1f;
        int offset = 1;
        boolean start = true;
        while (offset < nal.length) {
            int fragmentBytes = Math.min(maxFragmentBytes, nal.length - offset);
            boolean end = offset + fragmentBytes == nal.length;
            boolean marker = finalNal && end;
            byte[] packet = new byte[RTP_HEADER_BYTES + FU_A_HEADER_BYTES + fragmentBytes];
            int sequence = writeHeader(packet, timestamp, marker);
            packet[RTP_HEADER_BYTES] = (byte) fuIndicator;
            packet[RTP_HEADER_BYTES + 1] = (byte) (nalType
                    | (start ? 0x80 : 0) | (end ? 0x40 : 0));
            System.arraycopy(nal, offset, packet, RTP_HEADER_BYTES + FU_A_HEADER_BYTES,
                    fragmentBytes);
            sink.onRtpPacket(new RtpPacket(packet, sequence, timestamp, marker, ssrc,
                    accessUnit.getReceiveTimeNanos(), accessUnit.getCompleteReceiveTimeNanos(),
                    accessUnit.isKeyFrame()));
            emittedPackets++;
            offset += fragmentBytes;
            start = false;
        }
    }

    private int writeHeader(byte[] packet, long timestamp, boolean marker) {
        int sequence = sequenceNumber;
        packet[0] = (byte) 0x80; // RTP version 2, no extensions/CSRCs
        packet[1] = (byte) ((marker ? 0x80 : 0) | payloadType);
        packet[2] = (byte) (sequence >>> 8);
        packet[3] = (byte) sequence;
        packet[4] = (byte) (timestamp >>> 24);
        packet[5] = (byte) (timestamp >>> 16);
        packet[6] = (byte) (timestamp >>> 8);
        packet[7] = (byte) timestamp;
        packet[8] = (byte) (ssrc >>> 24);
        packet[9] = (byte) (ssrc >>> 16);
        packet[10] = (byte) (ssrc >>> 8);
        packet[11] = (byte) ssrc;
        sequenceNumber = (sequenceNumber + 1) & 0xffff;
        return sequence;
    }

    public static final class Counters {
        public final long emittedPackets;
        public final long fragmentedNalUnits;
        public final long rejectedNalUnits;

        Counters(long emittedPackets, long fragmentedNalUnits, long rejectedNalUnits) {
            this.emittedPackets = emittedPackets;
            this.fragmentedNalUnits = fragmentedNalUnits;
            this.rejectedNalUnits = rejectedNalUnits;
        }
    }
}
