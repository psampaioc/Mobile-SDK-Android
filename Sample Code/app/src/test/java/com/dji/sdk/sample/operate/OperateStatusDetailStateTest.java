package com.dji.sdk.sample.operate;

/** JVM regression coverage for the top-chevron versus internal-tab anchoring contract. */
public final class OperateStatusDetailStateTest {
    private OperateStatusDetailStateTest() { }

    public static void run() {
        OperateStatusDetailState state = new OperateStatusDetailState();
        state.openFromTopRail(OperateStatusDetailState.Group.LINK);
        equal(OperateStatusDetailState.Group.LINK, state.getSelected(), "Link selects Link");
        equal(OperateStatusDetailState.Group.LINK, state.getAnchor(), "Link anchors Link");
        state.selectTab(OperateStatusDetailState.Group.SAFETY);
        equal(OperateStatusDetailState.Group.SAFETY, state.getSelected(), "Safety tab selects Safety");
        equal(OperateStatusDetailState.Group.LINK, state.getAnchor(), "Safety keeps Link anchor");
        state.openFromTopRail(OperateStatusDetailState.Group.POWER);
        equal(OperateStatusDetailState.Group.POWER, state.getAnchor(), "Power chevron replaces anchor");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected
                + ", got " + actual);
    }
}
