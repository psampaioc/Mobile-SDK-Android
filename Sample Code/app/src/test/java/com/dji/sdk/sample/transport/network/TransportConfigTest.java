package com.dji.sdk.sample.transport.network;

/** Dependency-free contract for Edge endpoints accepted by the tablet UI. */
public final class TransportConfigTest {
    private TransportConfigTest() { }

    public static void run() {
        acceptsLiteralAddresses();
        rejectsHostnamesAndMalformedAddresses();
    }

    private static void acceptsLiteralAddresses() {
        require(new TransportConfig("192.168.1.151").edgeHost.equals("192.168.1.151"),
                "IPv4 endpoint must be retained");
        require(new TransportConfig("2001:db8::1").edgeHost.equals("2001:db8::1"),
                "IPv6 endpoint must be retained");
    }

    private static void rejectsHostnamesAndMalformedAddresses() {
        rejects("edge.local");
        rejects("999.1.1.1");
        rejects("2001:::1");
    }

    private static void rejects(String endpoint) {
        try {
            new TransportConfig(endpoint);
            throw new AssertionError("endpoint must be rejected: " + endpoint);
        } catch (IllegalArgumentException expected) {
            // Expected: this path must not use DNS to accept hostnames.
        }
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
