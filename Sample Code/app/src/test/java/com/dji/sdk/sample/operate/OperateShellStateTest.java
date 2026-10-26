package com.dji.sdk.sample.operate;

/** Dependency-free JVM contract for the persistent Operate shell label. */
public final class OperateShellStateTest {
    private OperateShellStateTest() { }

    public static void main(String[] args) {
        startsAtOperate();
        namesEachOverlayDestination();
        returnsToOperateAfterClose();
        System.out.println("Operate shell state tests passed");
    }

    private static void startsAtOperate() {
        OperateShellState state = new OperateShellState();
        equal("OPERATE", state.getTitle(), "initial shell title");
    }

    private static void namesEachOverlayDestination() {
        OperateShellState state = new OperateShellState();
        state.show(OperateShellState.Page.MISSION);
        equal("MISSION", state.getTitle(), "mission title");
        state.show(OperateShellState.Page.TRANSPORT);
        equal("TRANSPORT", state.getTitle(), "transport title");
        state.show(OperateShellState.Page.RTK);
        equal("RTK", state.getTitle(), "rtk title");
        state.show(OperateShellState.Page.SETTINGS);
        equal("SETTINGS", state.getTitle(), "settings title");
    }

    private static void returnsToOperateAfterClose() {
        OperateShellState state = new OperateShellState();
        state.show(OperateShellState.Page.MISSION);
        state.showOperate();
        equal("OPERATE", state.getTitle(), "back title");
    }

    private static void equal(String expected, String actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected
                + ", got " + actual);
    }
}
