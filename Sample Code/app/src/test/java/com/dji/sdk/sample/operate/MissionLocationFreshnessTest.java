package com.dji.sdk.sample.operate;

/** Keeps map centering on current locations rather than historical provider cache entries. */
public final class MissionLocationFreshnessTest {
    private MissionLocationFreshnessTest() { }

    public static void run() {
        long now = 20_000L;
        require(OperateStatusModel.isFresh(now - OperateStatusModel.STATUS_STALE_AFTER_MS, now),
                "freshness threshold is inclusive");
        require(!OperateStatusModel.isFresh(now - OperateStatusModel.STATUS_STALE_AFTER_MS - 1L, now),
                "older location must be stale");
        require(!OperateStatusModel.isFresh(-1L, now), "missing location must not center map");
        require(!OperateStatusModel.isFresh(now + 1L, now), "future location must not center map");
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
