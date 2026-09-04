package com.dji.sdk.sample.transport.network;

import java.net.DatagramPacket;

/** Preserves the exact UDP source endpoint of the Edge ping. */
final class ClockReplyTarget {
    private ClockReplyTarget() { }

    static DatagramPacket forRequest(DatagramPacket request, byte[] payload) {
        return new DatagramPacket(payload, payload.length, request.getAddress(), request.getPort());
    }
}
