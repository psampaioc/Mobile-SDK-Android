package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

/** Small UI-only state for the persistent Operate shell header. */
public final class OperateShellState {
    public enum Page { OPERATE, MISSION, TRANSPORT, RTK, SETTINGS }

    private Page page = Page.OPERATE;

    public void show(@NonNull Page nextPage) {
        page = nextPage;
    }

    public void showOperate() {
        page = Page.OPERATE;
    }

    @NonNull public String getTitle() {
        switch (page) {
            case MISSION: return "MISSION";
            case TRANSPORT: return "TRANSPORT";
            case RTK: return "RTK";
            case SETTINGS: return "SETTINGS";
            case OPERATE:
            default: return "OPERATE";
        }
    }
}
