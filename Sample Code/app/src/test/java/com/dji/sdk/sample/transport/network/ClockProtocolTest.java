package com.dji.sdk.sample.transport.network;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Dependency-free contract coverage for Edge-originated clock synchronization replies. */
public final class ClockProtocolTest {
    private ClockProtocolTest() { }

    public static void run() throws Exception {
        byte[] ping = "clock_ping".getBytes(StandardCharsets.UTF_8);
        DatagramPacket request = new DatagramPacket(ping, ping.length,
                InetAddress.getByName("127.0.0.1"), 43123);
        byte[] pong = ClockPongEncoder.encode("edge-session", 8, 100, 200, 199);
        DatagramPacket reply = ClockReplyTarget.forRequest(request, pong);
        String json = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
        require(reply.getAddress().equals(request.getAddress()), "reply address must be ping origin");
        require(reply.getPort() == 43123, "reply port must be ping origin");
        require(json.contains("\"session\":\"edge-session\""), "edge session preserved");
        require(json.contains("\"seq\":8"), "sequence preserved");
        require(json.contains("\"t0_edge_send_mono_ns\":100"), "edge timestamp preserved");
        require(json.contains("\"t1_android_rx_mono_ns\":200"), "android receive timestamp included");
        require(json.contains("\"t2_android_tx_mono_ns\":200"), "transmit timestamp ordered");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
