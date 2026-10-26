package com.dji.sdk.sample.transport.network;

import androidx.test.runner.AndroidJUnit4;

import com.dji.sdk.sample.transport.clock.MonotonicClock;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies that an Edge socket on an ephemeral port receives its correlated pong on that port. */
@RunWith(AndroidJUnit4.class)
public final class AndroidClockResponderInstrumentedTest {
    @Test public void repliesToTheEdgePingSourcePort() throws Exception {
        AtomicLong ticks = new AtomicLong(1_000L);
        MonotonicClock clock = () -> ticks.getAndAdd(10L);
        try (AndroidClockResponder responder = new AndroidClockResponder(clock, 0);
                DatagramSocket edge = new DatagramSocket(0)) {
            edge.setSoTimeout(2_000);
            int edgeEphemeralPort = edge.getLocalPort();
            byte[] ping = ("{\"v\":1,\"type\":\"clock_ping\",\"session\":\"edge-test\","
                    + "\"stream\":\"clock\",\"seq\":27,\"t0_edge_send_mono_ns\":991}")
                    .getBytes(StandardCharsets.UTF_8);
            edge.send(new DatagramPacket(ping, ping.length, InetAddress.getByName("127.0.0.1"),
                    responder.getLocalPort()));

            byte[] bytes = new byte[TransportConfig.MAX_JSON_DATAGRAM_BYTES];
            DatagramPacket pong = new DatagramPacket(bytes, bytes.length);
            edge.receive(pong);

            JSONObject json = new JSONObject(new String(pong.getData(), pong.getOffset(), pong.getLength(),
                    StandardCharsets.UTF_8));
            assertEquals(edgeEphemeralPort, edge.getLocalPort());
            assertEquals("clock_pong", json.getString("type"));
            assertEquals("edge-test", json.getString("session"));
            assertEquals("clock", json.getString("stream"));
            assertEquals(27L, json.getLong("seq"));
            assertEquals(991L, json.getLong("t0_edge_send_mono_ns"));
            assertTrue(json.getLong("t2_android_tx_mono_ns")
                    >= json.getLong("t1_android_rx_mono_ns"));
        }
    }
}
