package com.dji.sdk.sample.transport.video;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Groups Annex-B NAL units into access units using AUDs and first_mb_in_slice boundaries.
 * After startup or loss, output resumes only at an IDR and cached SPS/PPS are prepended.
 */
public final class H264AccessUnitAssembler {
    public interface AccessUnitSink {
        void onAccessUnit(H264AccessUnit accessUnit);
    }

    private final List<H264NalUnit> current = new ArrayList<>();
    private H264NalUnit latestSps;
    private H264NalUnit latestPps;
    private boolean currentHasVcl;
    private boolean currentHasIdr;
    private long currentReceiveTimeNanos;
    private long currentCompleteReceiveTimeNanos;
    private boolean waitingForIdr = true;
    private volatile long emittedAccessUnits;
    private volatile long droppedAccessUnits;
    private volatile long idrRecoveries;
    private volatile long malformedSlices;
    private volatile long discontinuities;

    public void accept(H264NalUnit nal, AccessUnitSink sink) {
        if (nal == null || sink == null) {
            throw new NullPointerException();
        }
        int type = nal.getType();
        if (type == 7) {
            latestSps = copy(nal);
        } else if (type == 8) {
            latestPps = copy(nal);
        }

        if (type == 9) { // access-unit delimiter
            finishCurrent(sink);
            current.add(nal);
            markComplete(nal);
            return;
        }

        if (nal.isVcl()) {
            int firstMacroblock = readFirstMacroblock(nal.getData());
            if (firstMacroblock < 0) {
                malformedSlices++;
            }
            if (currentHasVcl && firstMacroblock == 0) {
                finishCurrent(sink);
            }
            current.add(nal);
            markComplete(nal);
            if (!currentHasVcl) {
                currentReceiveTimeNanos = nal.getReceiveTimeNanos();
            }
            currentHasVcl = true;
            currentHasIdr |= type == 5;
            return;
        }

        // These non-VCL units conventionally precede the next primary coded picture.
        if (currentHasVcl && (type == 6 || type == 7 || type == 8 || (type >= 14 && type <= 18))) {
            finishCurrent(sink);
        }
        current.add(nal);
        markComplete(nal);
        if (type == 10 || type == 11) {
            finishCurrent(sink);
        }
    }

    public void signalDiscontinuity() {
        if (currentHasVcl) {
            droppedAccessUnits++;
        }
        clearCurrent();
        waitingForIdr = true;
        discontinuities++;
    }

    public void endOfStream(AccessUnitSink sink) {
        finishCurrent(sink);
    }

    public Counters snapshotCounters() {
        return new Counters(emittedAccessUnits, droppedAccessUnits, idrRecoveries,
                malformedSlices, discontinuities);
    }

    private void finishCurrent(AccessUnitSink sink) {
        if (!currentHasVcl) {
            // Keep prefix NALs for the following picture.
            return;
        }
        if (waitingForIdr && !currentHasIdr) {
            droppedAccessUnits++;
            clearCurrent();
            return;
        }

        List<H264NalUnit> output = new ArrayList<>();
        if (currentHasIdr) {
            prependIfAbsent(output, latestSps, 7);
            prependIfAbsent(output, latestPps, 8);
        }
        output.addAll(current);
        sink.onAccessUnit(new H264AccessUnit(output, currentReceiveTimeNanos,
                currentCompleteReceiveTimeNanos, currentHasIdr));
        emittedAccessUnits++;
        if (waitingForIdr) {
            waitingForIdr = false;
            idrRecoveries++;
        }
        clearCurrent();
    }

    private void prependIfAbsent(List<H264NalUnit> output, H264NalUnit parameterSet, int type) {
        if (parameterSet == null) {
            return;
        }
        for (H264NalUnit nal : current) {
            if (nal.getType() == type) {
                return;
            }
        }
        output.add(parameterSet);
    }

    private void clearCurrent() {
        current.clear();
        currentHasVcl = false;
        currentHasIdr = false;
        currentReceiveTimeNanos = 0;
        currentCompleteReceiveTimeNanos = 0;
    }

    private void markComplete(H264NalUnit nal) {
        currentCompleteReceiveTimeNanos = Math.max(
                currentCompleteReceiveTimeNanos, nal.getReceiveTimeNanos());
    }

    private static H264NalUnit copy(H264NalUnit nal) {
        return new H264NalUnit(Arrays.copyOf(nal.getData(), nal.getData().length),
                nal.getReceiveTimeNanos());
    }

    /** Returns first_mb_in_slice, or -1 for malformed/truncated RBSP. */
    static int readFirstMacroblock(byte[] nal) {
        if (nal.length < 2) {
            return -1;
        }
        int bitOffset = 8; // skip one-byte NAL header
        int leadingZeros = 0;
        while (bitOffset < nal.length * 8 && readBit(nal, bitOffset) == 0) {
            leadingZeros++;
            bitOffset++;
            if (leadingZeros > 30) {
                return -1;
            }
        }
        if (bitOffset >= nal.length * 8) {
            return -1;
        }
        bitOffset++; // delimiter one
        int value = (1 << leadingZeros) - 1;
        for (int i = leadingZeros - 1; i >= 0; i--) {
            if (bitOffset >= nal.length * 8) {
                return -1;
            }
            value |= readBit(nal, bitOffset++) << i;
        }
        return value;
    }

    private static int readBit(byte[] data, int bitOffset) {
        return (data[bitOffset / 8] >> (7 - (bitOffset % 8))) & 1;
    }

    public static final class Counters {
        public final long emittedAccessUnits;
        public final long droppedAccessUnits;
        public final long idrRecoveries;
        public final long malformedSlices;
        public final long discontinuities;

        Counters(long emittedAccessUnits, long droppedAccessUnits, long idrRecoveries,
                long malformedSlices, long discontinuities) {
            this.emittedAccessUnits = emittedAccessUnits;
            this.droppedAccessUnits = droppedAccessUnits;
            this.idrRecoveries = idrRecoveries;
            this.malformedSlices = malformedSlices;
            this.discontinuities = discontinuities;
        }
    }
}
