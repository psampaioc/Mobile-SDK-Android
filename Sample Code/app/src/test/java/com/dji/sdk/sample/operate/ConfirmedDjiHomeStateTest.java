package com.dji.sdk.sample.operate;

/** Dependency-free regression coverage for confirmed DJI Home/readback ordering. */
public final class ConfirmedDjiHomeStateTest {
    private ConfirmedDjiHomeStateTest() { }

    public static void run() {
        retainsSuccessfulCommandUntilReadbackIsUsable();
        validReadbackRefreshesConfirmedHome();
        rejectsInvalidCommandCoordinates();
    }

    private static void retainsSuccessfulCommandUntilReadbackIsUsable() {
        ConfirmedDjiHomeState state = new ConfirmedDjiHomeState();
        state.confirm(38.7223, -9.1393);
        state.reconcileReadback(false, 0.0, 0.0);
        check(state.isAvailable(), "unavailable readback must not erase confirmed home");
        check(state.getLatitude() == 38.7223 && state.getLongitude() == -9.1393,
                "confirmed coordinates must remain visible");
    }

    private static void validReadbackRefreshesConfirmedHome() {
        ConfirmedDjiHomeState state = new ConfirmedDjiHomeState();
        state.confirm(38.7223, -9.1393);
        state.reconcileReadback(true, 38.7230, -9.1400);
        check(state.getLatitude() == 38.7230 && state.getLongitude() == -9.1400,
                "valid DJI readback must refresh confirmed home");
    }

    private static void rejectsInvalidCommandCoordinates() {
        ConfirmedDjiHomeState state = new ConfirmedDjiHomeState();
        state.confirm(91.0, 0.0);
        check(!state.isAvailable(), "invalid coordinate must not become a home location");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
