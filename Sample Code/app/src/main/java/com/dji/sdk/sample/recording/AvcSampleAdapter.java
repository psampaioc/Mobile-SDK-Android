package com.dji.sdk.sample.recording;

import com.dji.sdk.sample.transport.video.H264AccessUnit;
import com.dji.sdk.sample.transport.video.H264NalUnit;

import java.nio.ByteBuffer;
import java.util.List;

/** Adapts assembled Annex-B NALs to length-prefixed AVC samples required by MP4. */
final class AvcSampleAdapter {
    private AvcSampleAdapter() { }

    static byte[] withStartCode(H264NalUnit nal) {
        byte[] raw = nal.getData();
        byte[] result = new byte[raw.length + 4];
        result[3] = 1;
        System.arraycopy(raw, 0, result, 4, raw.length);
        return result;
    }

    static byte[] mediaSample(H264AccessUnit accessUnit) {
        List<H264NalUnit> nals = accessUnit.getNalUnits();
        int total = 0;
        for (H264NalUnit nal : nals) {
            int type = nal.getType();
            if (type != 7 && type != 8 && type != 9) total += 4 + nal.getData().length;
        }
        ByteBuffer output = ByteBuffer.allocate(total);
        for (H264NalUnit nal : nals) {
            int type = nal.getType();
            if (type == 7 || type == 8 || type == 9) continue;
            byte[] bytes = nal.getData();
            output.putInt(bytes.length);
            output.put(bytes);
        }
        return output.array();
    }

    static H264NalUnit firstOfType(H264AccessUnit accessUnit, int type) {
        for (H264NalUnit nal : accessUnit.getNalUnits()) if (nal.getType() == type) return nal;
        return null;
    }
}
