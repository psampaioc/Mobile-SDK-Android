package com.dji.sdk.sample.transport.video;

import java.io.ByteArrayOutputStream;

/** Incremental H.264 parser for Annex-B and four-byte length-prefixed streams. */
public final class AnnexBParser {
    public interface NalSink { void onNalUnit(H264NalUnit nalUnit); }

    private enum Format { UNKNOWN, ANNEX_B, LENGTH_PREFIXED }

    private final int maximumNalBytes;
    private final ByteArrayOutputStream probe = new ByteArrayOutputStream(8);
    private long probeReceiveTimeNanos;
    private Format format = Format.UNKNOWN;

    private final ByteArrayOutputStream current = new ByteArrayOutputStream();
    private boolean insideNal;
    private int pendingZeros;
    private long zeroTimeOneBack;
    private long zeroTimeTwoBack;
    private long zeroTimeThreeBack;
    private long nalReceiveTimeNanos;

    private final byte[] lengthPrefix = new byte[4];
    private int lengthPrefixBytes;
    private long lengthPrefixReceiveTimeNanos;
    private int expectedNalBytes = -1;
    private final ByteArrayOutputStream lengthPrefixedNal = new ByteArrayOutputStream();
    private long lengthPrefixedNalReceiveTimeNanos;

    private volatile long emittedNalUnits;
    private volatile long discardedBytes;
    private volatile long malformedNalUnits;
    private volatile long discontinuities;
    private volatile long annexBThreeByteStartCodes;
    private volatile long annexBFourByteStartCodes;
    private volatile long lengthPrefixCandidates;
    private volatile long invalidLengthPrefixes;
    private volatile long parserResets;
    private volatile long vclNalUnits;
    private volatile long spsNalUnits;
    private volatile long ppsNalUnits;
    private volatile long idrNalUnits;

    public AnnexBParser(int maximumNalBytes) {
        if (maximumNalBytes < 1) throw new IllegalArgumentException("maximumNalBytes must be positive");
        this.maximumNalBytes = maximumNalBytes;
    }

    public void accept(byte[] data, long receiveTimeNanos, NalSink sink) {
        accept(data, 0, data.length, receiveTimeNanos, sink);
    }

    public void accept(byte[] data, int offset, int length, long receiveTimeNanos, NalSink sink) {
        if (data == null || sink == null) throw new NullPointerException();
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
        if (length == 0) return;
        if (format == Format.ANNEX_B) acceptAnnexB(data, offset, length, receiveTimeNanos, sink);
        else if (format == Format.LENGTH_PREFIXED) acceptLengthPrefixed(data, offset, length, receiveTimeNanos, sink);
        else acceptUnknown(data, offset, length, receiveTimeNanos, sink);
    }

    private void acceptUnknown(byte[] data, int offset, int length, long receiveTimeNanos, NalSink sink) {
        for (int i = offset; i < offset + length; i++) {
            if (probe.size() == 0) probeReceiveTimeNanos = receiveTimeNanos;
            probe.write(data[i] & 0xff);
            while (probe.size() >= 3) {
                byte[] bytes = probe.toByteArray();
                int startCode = findStartCode(bytes);
                if (startCode >= 0) {
                    discardedBytes += startCode;
                    format = Format.ANNEX_B;
                    probe.reset();
                    acceptAnnexB(bytes, startCode, bytes.length - startCode, probeReceiveTimeNanos, sink);
                    if (i + 1 < offset + length) acceptAnnexB(data, i + 1, offset + length - i - 1, receiveTimeNanos, sink);
                    return;
                }
                if (bytes.length < 5) break;
                long candidate = unsignedLength(bytes, 0);
                int type = bytes[4] & 0x1f;
                if (candidate > 0 && candidate <= maximumNalBytes && type > 0 && type <= 23) {
                    format = Format.LENGTH_PREFIXED;
                    probe.reset();
                    acceptLengthPrefixed(bytes, 0, bytes.length, probeReceiveTimeNanos, sink);
                    if (i + 1 < offset + length) acceptLengthPrefixed(data, i + 1, offset + length - i - 1, receiveTimeNanos, sink);
                    return;
                }
                invalidLengthPrefixes++;
                discardedBytes++;
                probe.reset();
                for (int j = 1; j < bytes.length; j++) probe.write(bytes[j] & 0xff);
                probeReceiveTimeNanos = receiveTimeNanos;
            }
        }
    }

    private void acceptLengthPrefixed(byte[] data, int offset, int length, long receiveTimeNanos, NalSink sink) {
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (expectedNalBytes < 0) {
                if (lengthPrefixBytes == 0) lengthPrefixReceiveTimeNanos = receiveTimeNanos;
                lengthPrefix[lengthPrefixBytes++] = data[i];
                if (lengthPrefixBytes < 4) continue;
                long candidate = unsignedLength(lengthPrefix, 0);
                lengthPrefixBytes = 0;
                if (candidate <= 0 || candidate > maximumNalBytes) {
                    invalidLengthPrefixes++;
                    malformedNalUnits++;
                    resetToUnknown();
                    if (i + 1 < end) acceptUnknown(data, i + 1, end - i - 1, receiveTimeNanos, sink);
                    return;
                }
                lengthPrefixCandidates++;
                expectedNalBytes = (int) candidate;
                lengthPrefixedNal.reset();
                lengthPrefixedNalReceiveTimeNanos = lengthPrefixReceiveTimeNanos;
                continue;
            }
            lengthPrefixedNal.write(data[i] & 0xff);
            if (lengthPrefixedNal.size() < expectedNalBytes) continue;
            byte[] nal = lengthPrefixedNal.toByteArray();
            int type = nal[0] & 0x1f;
            if (type == 0 || type > 23) {
                malformedNalUnits++;
                discardedBytes += nal.length;
                resetToUnknown();
                if (i + 1 < end) acceptUnknown(data, i + 1, end - i - 1, receiveTimeNanos, sink);
                return;
            }
            emitNal(nal, lengthPrefixedNalReceiveTimeNanos, sink);
            expectedNalBytes = -1;
            lengthPrefixedNal.reset();
        }
    }

    private void acceptAnnexB(byte[] data, int offset, int length, long receiveTimeNanos, NalSink sink) {
        for (int i = offset; i < offset + length; i++) {
            int value = data[i] & 0xff;
            if (value == 0) {
                zeroTimeThreeBack = zeroTimeTwoBack;
                zeroTimeTwoBack = zeroTimeOneBack;
                zeroTimeOneBack = receiveTimeNanos;
                pendingZeros++;
                continue;
            }
            if (value == 1 && pendingZeros >= 2) {
                if (pendingZeros >= 3) annexBFourByteStartCodes++;
                else annexBThreeByteStartCodes++;
                if (insideNal) emitCurrent(sink);
                else if (pendingZeros > 3) discardedBytes += pendingZeros - 3L;
                insideNal = true;
                current.reset();
                nalReceiveTimeNanos = pendingZeros >= 3 ? zeroTimeThreeBack : zeroTimeTwoBack;
                pendingZeros = 0;
                continue;
            }
            if (insideNal) {
                appendPendingZeros();
                if (insideNal) append(value);
                else discardedBytes++;
            } else {
                discardedBytes += pendingZeros + 1L;
                pendingZeros = 0;
            }
        }
    }

    public void signalDiscontinuity() {
        if ((insideNal && (current.size() > 0 || pendingZeros > 0)) || lengthPrefixBytes > 0
                || expectedNalBytes >= 0 || probe.size() > 0) malformedNalUnits++;
        clearAllState();
        format = Format.UNKNOWN;
        discontinuities++;
        parserResets++;
    }

    public void endOfStream(NalSink sink) {
        if (format == Format.ANNEX_B && insideNal) emitCurrent(sink);
        if (format == Format.LENGTH_PREFIXED && (lengthPrefixBytes > 0 || expectedNalBytes >= 0)) malformedNalUnits++;
        clearAllState();
        format = Format.UNKNOWN;
    }

    public Counters snapshotCounters() {
        return new Counters(emittedNalUnits, discardedBytes, malformedNalUnits, discontinuities,
                annexBThreeByteStartCodes, annexBFourByteStartCodes, lengthPrefixCandidates,
                invalidLengthPrefixes, parserResets, vclNalUnits, spsNalUnits, ppsNalUnits,
                idrNalUnits, format.name());
    }

    private void resetToUnknown() {
        clearAllState();
        format = Format.UNKNOWN;
        parserResets++;
    }

    private void clearAllState() {
        probe.reset(); current.reset(); insideNal = false; pendingZeros = 0;
        lengthPrefixBytes = 0; expectedNalBytes = -1; lengthPrefixedNal.reset();
    }

    private void appendPendingZeros() {
        int zeros = pendingZeros; pendingZeros = 0;
        for (int i = 0; i < zeros; i++) {
            if (!insideNal) { discardedBytes += zeros - i; return; }
            append(0);
        }
    }

    private void append(int value) {
        if (current.size() >= maximumNalBytes) {
            malformedNalUnits++; discardedBytes += current.size() + 1L;
            current.reset(); insideNal = false; return;
        }
        current.write(value);
    }

    private void emitCurrent(NalSink sink) {
        pendingZeros = 0;
        if (current.size() > 0) emitNal(current.toByteArray(), nalReceiveTimeNanos, sink);
        else malformedNalUnits++;
        current.reset();
    }

    private void emitNal(byte[] nal, long receiveTimeNanos, NalSink sink) {
        int type = nal[0] & 0x1f;
        if (type >= 1 && type <= 5) vclNalUnits++;
        if (type == 5) idrNalUnits++;
        else if (type == 7) spsNalUnits++;
        else if (type == 8) ppsNalUnits++;
        sink.onNalUnit(new H264NalUnit(nal, receiveTimeNanos));
        emittedNalUnits++;
    }

    private static int findStartCode(byte[] bytes) {
        for (int i = 0; i + 2 < bytes.length; i++) {
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1) return i;
            if (i + 3 < bytes.length && bytes[i] == 0 && bytes[i + 1] == 0
                    && bytes[i + 2] == 0 && bytes[i + 3] == 1) return i;
        }
        return -1;
    }

    private static long unsignedLength(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24) | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xffL);
    }

    public static final class Counters {
        public final long emittedNalUnits, discardedBytes, malformedNalUnits, discontinuities;
        public final long annexBThreeByteStartCodes, annexBFourByteStartCodes;
        public final long lengthPrefixCandidates, invalidLengthPrefixes, parserResets;
        public final long vclNalUnits, spsNalUnits, ppsNalUnits, idrNalUnits;
        public final String detectedFormat;

        Counters(long emittedNalUnits, long discardedBytes, long malformedNalUnits,
                long discontinuities, long annexBThreeByteStartCodes,
                long annexBFourByteStartCodes, long lengthPrefixCandidates,
                long invalidLengthPrefixes, long parserResets, long vclNalUnits,
                long spsNalUnits, long ppsNalUnits, long idrNalUnits, String detectedFormat) {
            this.emittedNalUnits = emittedNalUnits; this.discardedBytes = discardedBytes;
            this.malformedNalUnits = malformedNalUnits; this.discontinuities = discontinuities;
            this.annexBThreeByteStartCodes = annexBThreeByteStartCodes;
            this.annexBFourByteStartCodes = annexBFourByteStartCodes;
            this.lengthPrefixCandidates = lengthPrefixCandidates;
            this.invalidLengthPrefixes = invalidLengthPrefixes; this.parserResets = parserResets;
            this.vclNalUnits = vclNalUnits; this.spsNalUnits = spsNalUnits;
            this.ppsNalUnits = ppsNalUnits; this.idrNalUnits = idrNalUnits;
            this.detectedFormat = detectedFormat;
        }
    }
}
