package com.dji.sdk.sample.djihub;

/** Dependency-free contract for the hub-owned DJI Home observation. */
public final class DjiHomeStateTest {
    private DjiHomeStateTest() { }

    public static void run() {
        startsWaitingAndRequiresReadback();
        acceptedCommandBecomesAvailableImmediately();
        invalidReadbackCannotEraseConfirmedHomeInSession();
        disconnectStartsANewUntrustedSession();
    }

    private static void startsWaitingAndRequiresReadback() {
        DjiHomeState state = DjiHomeState.waiting();
        require(state.getAvailability() == DjiHomeState.Availability.WAITING_FOR_SAMPLE,
                "new session must wait for DJI Home evidence");
        require(!state.isAvailable(), "waiting must not pass readiness");
    }

    private static void acceptedCommandBecomesAvailableImmediately() {
        DjiHomeState state = DjiHomeState.waiting().withConfirmed(38.7223, -9.1393,
                DjiHomeState.Source.EXPLICIT_COMMAND, 10L);
        require(state.isAvailable(), "accepted explicit command must become global Home");
        require(state.getSource() == DjiHomeState.Source.EXPLICIT_COMMAND,
                "command source must remain observable");
        require(state.getLatitude() == 38.7223 && state.getLongitude() == -9.1393,
                "command coordinate must be retained");
    }

    private static void invalidReadbackCannotEraseConfirmedHomeInSession() {
        DjiHomeState state = DjiHomeState.waiting().withConfirmed(38.7223, -9.1393,
                DjiHomeState.Source.EXPLICIT_COMMAND, 10L)
                .withUnavailableReadback(20L);
        require(state.isAvailable(), "invalid readback must not erase accepted Home");
        require(state.getSource() == DjiHomeState.Source.EXPLICIT_COMMAND,
                "accepted Home must remain authoritative until a valid DJI readback");
    }

    private static void disconnectStartsANewUntrustedSession() {
        DjiHomeState state = DjiHomeState.waiting().withConfirmed(38.7223, -9.1393,
                DjiHomeState.Source.READBACK, 10L).newSession();
        require(state.getAvailability() == DjiHomeState.Availability.WAITING_FOR_SAMPLE,
                "disconnect must not carry prior Home into a new aircraft session");
        require(state.getLatitude() == null && state.getLongitude() == null,
                "new session must not retain old coordinates");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
