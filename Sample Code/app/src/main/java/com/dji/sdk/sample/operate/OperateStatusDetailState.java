package com.dji.sdk.sample.operate;

import androidx.annotation.NonNull;

/** Pure selection state for the four detail tabs and the three top-rail anchors. */
public final class OperateStatusDetailState {
    public enum Group { LINK, NAV, POWER, SAFETY }

    private Group selected;
    private Group anchor;

    public void openFromTopRail(@NonNull Group group) {
        if (group == Group.SAFETY) throw new IllegalArgumentException("Safety has no top-rail anchor");
        selected = group;
        anchor = group;
    }

    public void selectTab(@NonNull Group group) { selected = group; }

    public void close() { selected = null; }

    public Group getSelected() { return selected; }
    public Group getAnchor() { return anchor; }
}
