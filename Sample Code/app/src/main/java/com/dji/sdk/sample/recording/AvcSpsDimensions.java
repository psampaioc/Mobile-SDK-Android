package com.dji.sdk.sample.recording;

/** Minimal AVC SPS reader used only to avoid guessing MP4 track dimensions. */
final class AvcSpsDimensions {
    final int width;
    final int height;

    private AvcSpsDimensions(int width, int height) { this.width = width; this.height = height; }

    static AvcSpsDimensions read(byte[] nal) {
        if (nal == null || nal.length < 5 || (nal[0] & 0x1f) != 7) return null;
        try {
            BitReader bits = new BitReader(removeEmulationPrevention(nal, 1));
            int profile = bits.readBits(8); bits.readBits(8); bits.readBits(8); bits.ue();
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                    || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                    || profile == 139 || profile == 134) {
                chroma = bits.ue();
                if (chroma == 3) bits.readBit();
                bits.ue(); bits.ue(); bits.readBit();
                if (bits.readBit()) {
                    int count = chroma == 3 ? 12 : 8;
                    for (int i = 0; i < count; i++) if (bits.readBit()) skipScalingList(bits, i < 6 ? 16 : 64);
                }
            }
            bits.ue();
            int picOrderCntType = bits.ue();
            if (picOrderCntType == 0) bits.ue();
            else if (picOrderCntType == 1) {
                bits.readBit(); bits.se(); bits.se();
                int cycle = bits.ue(); for (int i = 0; i < cycle; i++) bits.se();
            }
            bits.ue(); bits.readBit();
            int widthMbs = bits.ue() + 1;
            int heightMapUnits = bits.ue() + 1;
            boolean frameMbsOnly = bits.readBit();
            if (!frameMbsOnly) bits.readBit();
            bits.readBit();
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (bits.readBit()) { cropLeft = bits.ue(); cropRight = bits.ue(); cropTop = bits.ue(); cropBottom = bits.ue(); }
            int cropUnitX = chroma == 0 ? 1 : (chroma == 3 ? 1 : 2);
            int cropUnitY = chroma == 0 ? (frameMbsOnly ? 1 : 2) : (chroma == 3 ? 1 : 2) * (frameMbsOnly ? 1 : 2);
            int width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX;
            int height = heightMapUnits * 16 * (frameMbsOnly ? 1 : 2) - (cropTop + cropBottom) * cropUnitY;
            return width > 0 && height > 0 ? new AvcSpsDimensions(width, height) : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static void skipScalingList(BitReader bits, int size) {
        int last = 8, next = 8;
        for (int i = 0; i < size; i++) { if (next != 0) next = (last + bits.se() + 256) & 0xff; last = next == 0 ? last : next; }
    }

    private static byte[] removeEmulationPrevention(byte[] source, int offset) {
        byte[] result = new byte[source.length - offset]; int length = 0, zeros = 0;
        for (int i = offset; i < source.length; i++) {
            int value = source[i] & 0xff;
            if (zeros >= 2 && value == 3) { zeros = 0; continue; }
            result[length++] = source[i]; zeros = value == 0 ? zeros + 1 : 0;
        }
        byte[] exact = new byte[length]; System.arraycopy(result, 0, exact, 0, length); return exact;
    }

    private static final class BitReader {
        private final byte[] bytes; private int bit;
        BitReader(byte[] bytes) { this.bytes = bytes; }
        boolean readBit() { return readBits(1) != 0; }
        int readBits(int count) {
            if (count < 0 || count > 24 || bit + count > bytes.length * 8) throw new IllegalArgumentException("truncated SPS");
            int value = 0; for (int i = 0; i < count; i++) value = (value << 1) | ((bytes[bit / 8] >> (7 - bit++ % 8)) & 1); return value;
        }
        int ue() { int zeros = 0; while (!readBit()) { if (++zeros > 30) throw new IllegalArgumentException("invalid Exp-Golomb"); } return ((1 << zeros) - 1) + (zeros == 0 ? 0 : readBits(zeros)); }
        int se() { int value = ue(); return (value & 1) == 0 ? -(value / 2) : (value + 1) / 2; }
    }
}
