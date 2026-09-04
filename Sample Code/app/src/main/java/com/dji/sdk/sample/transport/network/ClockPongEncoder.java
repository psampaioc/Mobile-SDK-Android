package com.dji.sdk.sample.transport.network;

import java.util.LinkedHashMap;

/** Fixed clock-pong payload; Edge owns the request session and correlation values. */
final class ClockPongEncoder {
    private ClockPongEncoder() { }

    static byte[] encode(String edgeSession, long sequence, long edgeSendNanos,
            long androidReceiveNanos, long androidTransmitNanos) {
        LinkedHashMap<String, Object> record = new LinkedHashMap<>();
        record.put("v", TransportConfig.PROTOCOL_VERSION);
        record.put("type", "clock_pong");
        record.put("session", edgeSession == null || edgeSession.isEmpty() ? "edge-clock" : edgeSession);
        record.put("stream", "clock");
        record.put("seq", sequence);
        record.put("t0_edge_send_mono_ns", edgeSendNanos);
        record.put("t1_android_rx_mono_ns", androidReceiveNanos);
        record.put("t2_android_tx_mono_ns", Math.max(androidReceiveNanos, androidTransmitNanos));
        return JsonValueEncoder.toUtf8(record, TransportConfig.MAX_JSON_DATAGRAM_BYTES);
    }
}
