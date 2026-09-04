package com.dji.sdk.sample.transport;

/** Dependency-free lifecycle coverage for persistent selected-endpoint semantics. */
public final class TransportEndpointPolicyTest {
    private TransportEndpointPolicyTest() { }

    public static void run() {
        require(TransportEndpointPolicy.decide("", null, false) == TransportEndpointPolicy.Action.STOP,
                "empty endpoint stops transport");
        require(TransportEndpointPolicy.decide("192.168.1.10", null, false)
                        == TransportEndpointPolicy.Action.WAIT_FOR_AIRCRAFT,
                "selected endpoint waits without aircraft");
        require(TransportEndpointPolicy.decide("192.168.1.10", null, true)
                        == TransportEndpointPolicy.Action.START,
                "connected aircraft starts selected endpoint");
        require(TransportEndpointPolicy.decide("192.168.1.10", "192.168.1.10", true)
                        == TransportEndpointPolicy.Action.KEEP,
                "same endpoint preserves the existing session across views");
        require(TransportEndpointPolicy.decide("192.168.1.11", "192.168.1.10", false)
                        == TransportEndpointPolicy.Action.REPLACE,
                "changing endpoint stops old runtime even while aircraft is unavailable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
