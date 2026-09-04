package com.dji.sdk.sample.operate;

/** Read-only capability state must never masquerade as a validated safety setting. */
public final class FlightSafetyConfigurationTest {
    private FlightSafetyConfigurationTest() { }

    public static void main(String[] args) {
        FlightSafetyConfiguration configuration = FlightSafetyConfiguration.unvalidated();
        if (configuration.canEdit()) throw new AssertionError("unvalidated configuration must be read-only");
        if (configuration.getReadbackState() != FlightSafetyConfiguration.ReadbackState.UNAVAILABLE) {
            throw new AssertionError("unvalidated configuration must be unavailable, not healthy");
        }
        System.out.println("Flight safety configuration tests passed");
    }
}
