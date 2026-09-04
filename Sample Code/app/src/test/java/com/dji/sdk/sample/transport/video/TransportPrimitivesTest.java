package com.dji.sdk.sample.transport.video;

import com.dji.sdk.sample.transport.rtp.H264RtpPacketizer;
import com.dji.sdk.sample.transport.rtp.RtpPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free JVM unit tests; run via this class's main method. */
public final class TransportPrimitivesTest {
    public static void main(String[] args) {
        testBoundedIngestionCopiesAndDropsOldest();
        testAnnexBStartCodesAcrossChunkBoundaries();
        testLengthPrefixedNalUnits();
        testLengthPrefixedPrefixAcrossCallbacks();
        testLengthPrefixedPayloadAcrossCallbacks();
        testLengthPrefixedMultipleUnitsAndNextPrefix();
        testLengthPrefixedDiscontinuityClearsPartialState();
        testLengthPrefixedInvalidLengthsRecover();
        testLengthPrefixedPipelineProducesPacketizableAccessUnit();
        testNonVclUnitsDoNotProduceRtpMarker();
        testAnnexBRecoversAfterOversizedNal();
        testAccessUnitAssemblyAndIdrRecovery();
        testSingleNalRtpAndClock();
        testFuAPacketizationIsMtuSafe();
        testPipelineAcrossArbitraryChunks();
        System.out.println("TransportPrimitivesTest: all tests passed");
    }

    private static void testBoundedIngestionCopiesAndDropsOldest() {
        BoundedVideoIngestor queue = new BoundedVideoIngestor(2);
        byte[] first = {1, 2};
        queue.offer(first, first.length, 10);
        first[0] = 99;
        EncodedVideoChunk copied = queue.poll();
        equal(1, copied.getData()[0], "callback bytes are copied");
        queue.offer(new byte[] {3}, 1, 20);
        queue.offer(new byte[] {4}, 1, 30);
        queue.offer(new byte[] {5}, 1, 40);

        EncodedVideoChunk retained = queue.poll();
        // Sequence 1 was dropped; sequence 2 is now the oldest queued chunk.
        equal(4, retained.getData()[0], "drop-oldest policy");
        equal(2L, retained.getSequence(), "sequence exposes discontinuity");
        BoundedVideoIngestor.Counters counters = queue.snapshotCounters();
        equal(4L, counters.acceptedChunks, "accepted chunks");
        equal(1L, counters.droppedChunks, "dropped chunks");
        equal(1L, counters.droppedBytes, "dropped bytes");
        equal(2L, counters.highWaterMark, "queue high-water mark");
    }

    private static void testAnnexBStartCodesAcrossChunkBoundaries() {
        AnnexBParser parser = new AnnexBParser(1024);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(new byte[] {55, 0}, 100, output::add);
        parser.accept(new byte[] {0}, 110, output::add);
        parser.accept(new byte[] {0, 1, 0x67, 0x11, 0}, 120, output::add);
        parser.accept(new byte[] {0, 1, 0x68, 0x22, 0, 0}, 130, output::add);
        parser.accept(new byte[] {0, 1, 0x65, (byte) 0x80}, 140, output::add);
        parser.endOfStream(output::add);

        equal(3, output.size(), "NAL count");
        equal(7, output.get(0).getType(), "SPS type");
        equal(8, output.get(1).getType(), "PPS type");
        equal(5, output.get(2).getType(), "IDR type");
        equal(100L, output.get(0).getReceiveTimeNanos(), "split start-code timestamp");
        arrayEqual(new byte[] {0x67, 0x11}, output.get(0).getData(), "SPS payload");
    }

    private static void testAnnexBRecoversAfterOversizedNal() {
        AnnexBParser parser = new AnnexBParser(3);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(bytes(0, 0, 1, 0x61, 1, 2, 3, 0, 0, 1, 0x65, 0x80),
                500, output::add);
        parser.endOfStream(output::add);
        equal(1, output.size(), "parser resynchronizes at start code after oversized NAL");
        equal(5, output.get(0).getType(), "recovered NAL type");
        equal(1L, parser.snapshotCounters().malformedNalUnits, "oversized NAL counter");
    }

    private static void testLengthPrefixedNalUnits() {
        AnnexBParser parser = new AnnexBParser(1024);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(bytes(0, 0, 0, 2, 0x67, 0x11, 0, 0, 0, 2, 0x65, 0x80),
                600, output::add);
        equal(2, output.size(), "length-prefixed NAL count");
        equal(7, output.get(0).getType(), "length-prefixed SPS type");
        equal(5, output.get(1).getType(), "length-prefixed IDR type");
    }

    private static void testLengthPrefixedPrefixAcrossCallbacks() {
        for (int split = 1; split <= 3; split++) {
            AnnexBParser parser = new AnnexBParser(1024);
            List<H264NalUnit> output = new ArrayList<>();
            byte[] stream = bytes(0, 0, 0, 3, 0x67, 0x11, 0x22);
            parser.accept(Arrays.copyOfRange(stream, 0, split), 610 + split, output::add);
            parser.accept(Arrays.copyOfRange(stream, split, stream.length), 620 + split,
                    output::add);
            equal(1, output.size(), "length prefix split after " + split + " byte(s)");
            equal(7, output.get(0).getType(), "split prefix NAL type");
        }
    }

    private static void testLengthPrefixedPayloadAcrossCallbacks() {
        AnnexBParser parser = new AnnexBParser(1024);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(bytes(0, 0, 0, 4, 0x65), 630, output::add);
        parser.accept(bytes(0x11, 0x22), 640, output::add);
        equal(0, output.size(), "partial NAL payload is retained");
        parser.accept(bytes(0x33), 650, output::add);
        equal(1, output.size(), "split NAL payload emitted when complete");
        arrayEqual(bytes(0x65, 0x11, 0x22, 0x33), output.get(0).getData(),
                "split NAL payload preserved");
    }

    private static void testLengthPrefixedMultipleUnitsAndNextPrefix() {
        AnnexBParser parser = new AnnexBParser(1024);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(bytes(0, 0, 0, 2, 0x67, 0x11,
                0, 0, 0, 2, 0x68, 0x22,
                0, 0), 660, output::add);
        equal(2, output.size(), "multiple complete NALs emitted before partial next prefix");
        parser.accept(bytes(0, 2, 0x65, 0x33), 670, output::add);
        equal(3, output.size(), "partial next prefix resumes on following callback");
        equal(5, output.get(2).getType(), "resumed next NAL type");
    }

    private static void testLengthPrefixedDiscontinuityClearsPartialState() {
        AnnexBParser parser = new AnnexBParser(1024);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(bytes(0, 0, 0, 4, 0x65, 0x11), 680, output::add);
        parser.signalDiscontinuity();
        parser.accept(bytes(0, 0, 0, 2, 0x67, 0x22), 690, output::add);
        equal(1, output.size(), "discontinuity discards partial AVCC payload");
        equal(7, output.get(0).getType(), "recovery NAL after discontinuity");
    }

    private static void testLengthPrefixedInvalidLengthsRecover() {
        AnnexBParser parser = new AnnexBParser(8);
        List<H264NalUnit> output = new ArrayList<>();
        parser.accept(bytes(0, 0, 0, 0, 0x65), 700, output::add);
        parser.accept(bytes(0, 0, 0, 9, 0x65), 710, output::add);
        parser.signalDiscontinuity();
        parser.accept(bytes(0, 0, 0, 2, 0x65, 0x55), 720, output::add);
        equal(1, output.size(), "parser recovers after zero and oversized lengths");
    }

    private static void testLengthPrefixedPipelineProducesPacketizableAccessUnit() {
        H264RtpPacketizer packetizer = new H264RtpPacketizer(1200, 96, 3, 1, 1);
        EncodedVideoRtpPipeline pipeline = new EncodedVideoRtpPipeline(8, 4096, packetizer);
        List<RtpPacket> packets = new ArrayList<>();
        byte[] stream = bytes(
                0, 0, 0, 2, 0x67, 0x11,
                0, 0, 0, 2, 0x68, 0x22,
                0, 0, 0, 2, 0x65, 0x80);
        for (int offset = 0; offset < stream.length; offset += 3) {
            int count = Math.min(3, stream.length - offset);
            pipeline.offer(Arrays.copyOfRange(stream, offset, offset + count), count,
                    1_000L + offset);
        }
        pipeline.drain(32, packets::add);
        pipeline.endOfStream(packets::add);
        equal(3, packets.size(), "SPS/PPS/IDR AVCC access unit is packetizable");
        check(packets.get(2).isMarker(), "final IDR packet has RTP marker");
    }

    private static void testNonVclUnitsDoNotProduceRtpMarker() {
        H264RtpPacketizer packetizer = new H264RtpPacketizer(1200, 96, 4, 1, 1);
        EncodedVideoRtpPipeline pipeline = new EncodedVideoRtpPipeline(8, 4096, packetizer);
        List<RtpPacket> packets = new ArrayList<>();
        pipeline.offer(bytes(0, 0, 0, 2, 0x67, 0x11,
                0, 0, 0, 2, 0x68, 0x22), 12, 2_000L);
        pipeline.drain(8, packets::add);
        pipeline.endOfStream(packets::add);
        equal(0, packets.size(), "parameter sets alone do not produce an RTP frame marker");
    }

    private static void testAccessUnitAssemblyAndIdrRecovery() {
        H264AccessUnitAssembler assembler = new H264AccessUnitAssembler();
        List<H264AccessUnit> output = new ArrayList<>();
        assembler.accept(nal(0x67, 1, 0x11), output::add);
        assembler.accept(nal(0x68, 2, 0x22), output::add);
        assembler.accept(nal(0x41, 3, 0x80), output::add); // non-IDR first slice
        assembler.accept(nal(0x65, 4, 0x80), output::add); // IDR first slice
        assembler.accept(nal(0x65, 5, 0x40), output::add); // same picture, first_mb=1
        assembler.endOfStream(output::add);

        equal(1, output.size(), "only decodable IDR access unit emitted");
        H264AccessUnit accessUnit = output.get(0);
        check(accessUnit.isKeyFrame(), "IDR flag");
        equal(4L, accessUnit.getReceiveTimeNanos(), "first VCL timestamp");
        equal(4, accessUnit.getNalUnits().size(), "cached SPS/PPS plus two slices");
        equal(7, accessUnit.getNalUnits().get(0).getType(), "prepended SPS");
        equal(8, accessUnit.getNalUnits().get(1).getType(), "prepended PPS");
        H264AccessUnitAssembler.Counters counters = assembler.snapshotCounters();
        equal(1L, counters.droppedAccessUnits, "pre-IDR dropped AU");
        equal(1L, counters.idrRecoveries, "IDR recovery count");

        assembler.signalDiscontinuity();
        assembler.accept(nal(0x41, 6, 0x80), output::add);
        assembler.accept(nal(0x65, 7, 0x80), output::add);
        assembler.endOfStream(output::add);
        equal(2, output.size(), "output resumes at next IDR");
        equal(2L, assembler.snapshotCounters().idrRecoveries, "second IDR recovery");
    }

    private static void testSingleNalRtpAndClock() {
        H264RtpPacketizer packetizer = new H264RtpPacketizer(
                1200, 96, 0x10203040, 65535, 0xfffffff0L);
        List<RtpPacket> packets = new ArrayList<>();
        packetizer.packetize(accessUnit(1_000_000_000L, nal(0x65, 1_000_000_000L, 0x80)),
                packets::add);
        packetizer.packetize(accessUnit(2_000_000_000L, nal(0x41, 2_000_000_000L, 0x80)),
                packets::add);

        equal(2, packets.size(), "single-NAL RTP count");
        RtpPacket first = packets.get(0);
        equal(65535, first.getSequenceNumber(), "initial RTP sequence");
        equal(0xfffffff0L, first.getTimestamp(), "initial RTP timestamp");
        check(first.isMarker(), "single final NAL marker");
        equal(0x10203040L, Integer.toUnsignedLong(first.getSsrc()), "RTP SSRC metadata");
        equal(1_000_000_000L, first.getAccessUnitFirstByteNanos(), "AU receive metadata");
        check(first.isKeyFrame(), "AU keyframe metadata");
        equal(0x80, first.getBytes()[0] & 0xff, "RTP v2 header");
        equal(0xe0, first.getBytes()[1] & 0xff, "marker plus payload type");
        equal(0, packets.get(1).getSequenceNumber(), "sequence wraps at 16 bits");
        equal((0xfffffff0L + 90_000L) & 0xffffffffL, packets.get(1).getTimestamp(),
                "90 kHz monotonic clock");
    }

    private static void testFuAPacketizationIsMtuSafe() {
        int mtu = 40;
        byte[] largeNal = new byte[100];
        largeNal[0] = 0x65;
        for (int i = 1; i < largeNal.length; i++) {
            largeNal[i] = (byte) i;
        }
        H264RtpPacketizer packetizer = new H264RtpPacketizer(mtu, 96, 7, 10, 20);
        List<RtpPacket> packets = new ArrayList<>();
        packetizer.packetize(accessUnit(99, new H264NalUnit(largeNal, 99)), packets::add);

        check(packets.size() > 1, "large NAL fragmented");
        for (RtpPacket packet : packets) {
            check(packet.getBytes().length <= mtu, "RTP datagram respects MTU");
            equal(28, packet.getBytes()[12] & 0x1f, "FU-A indicator type");
        }
        check((packets.get(0).getBytes()[13] & 0x80) != 0, "FU-A start bit");
        check(!packets.get(0).isMarker(), "first FU-A has no marker");
        RtpPacket last = packets.get(packets.size() - 1);
        check((last.getBytes()[13] & 0x40) != 0, "FU-A end bit");
        check(last.isMarker(), "last packet of access unit has marker");
        equal(1L, packetizer.snapshotCounters().fragmentedNalUnits, "fragment counter");

        byte[] rebuilt = new byte[largeNal.length];
        rebuilt[0] = (byte) ((packets.get(0).getBytes()[12] & 0xe0)
                | (packets.get(0).getBytes()[13] & 0x1f));
        int rebuiltOffset = 1;
        for (RtpPacket packet : packets) {
            int payloadBytes = packet.getBytes().length - 14;
            System.arraycopy(packet.getBytes(), 14, rebuilt, rebuiltOffset, payloadBytes);
            rebuiltOffset += payloadBytes;
        }
        equal(largeNal.length, rebuiltOffset, "FU-A reconstructed length");
        arrayEqual(largeNal, rebuilt, "FU-A preserves encoded NAL bytes");
    }

    private static void testPipelineAcrossArbitraryChunks() {
        H264RtpPacketizer packetizer = new H264RtpPacketizer(1200, 96, 1, 1, 1);
        EncodedVideoRtpPipeline pipeline = new EncodedVideoRtpPipeline(8, 4096, packetizer);
        List<RtpPacket> packets = new ArrayList<>();
        byte[] stream = concat(
                bytes(0, 0, 0, 1, 0x67, 0x11),
                bytes(0, 0, 1, 0x68, 0x22),
                bytes(0, 0, 1, 0x65, 0x80));
        for (int offset = 0; offset < stream.length; offset += 2) {
            int count = Math.min(2, stream.length - offset);
            pipeline.offer(Arrays.copyOfRange(stream, offset, offset + count), count,
                    1_000L + offset);
        }
        equal(8, pipeline.drain(32, packets::add), "all callback chunks drained");
        pipeline.endOfStream(packets::add);
        equal(3, packets.size(), "SPS/PPS/IDR packetized without re-encoding");
        check(packets.get(2).isMarker(), "IDR closes RTP access unit");
        equal(1L, pipeline.accessUnitCounters().emittedAccessUnits, "pipeline AU counter");
    }

    private static H264NalUnit nal(int header, long timestamp, int... payload) {
        byte[] data = new byte[payload.length + 1];
        data[0] = (byte) header;
        for (int i = 0; i < payload.length; i++) {
            data[i + 1] = (byte) payload[i];
        }
        return new H264NalUnit(data, timestamp);
    }

    private static H264AccessUnit accessUnit(long timestamp, H264NalUnit... nals) {
        return new H264AccessUnit(Arrays.asList(nals), timestamp, timestamp,
                nals[0].getType() == 5);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int size = 0;
        for (byte[] array : arrays) {
            size += array.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void arrayEqual(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + Arrays.toString(expected)
                    + ", got " + Arrays.toString(actual));
        }
    }
}
