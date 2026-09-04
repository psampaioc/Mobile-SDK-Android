package com.dji.sdk.sample.demo.remotecontroller;

import android.content.Context;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.view.BasePushDataView;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;

import dji.common.remotecontroller.HardwareState;

/**
 * Class for getting remote controller information.
 */
public class PushRemoteControllerDataView extends BasePushDataView {

    private CallbackMulticaster.Subscription hubSubscription;

    public PushRemoteControllerDataView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
            @Override
            public void onRemoteControllerState(@NonNull HardwareState rcHardwareState) {
                stringBuffer.delete(0, stringBuffer.length());
                stringBuffer.append("FlightModeSwitch: ")
                        .append(rcHardwareState.getFlightModeSwitch().name()).append("\n");
                stringBuffer.append("OnClickGoHomeBtn: ")
                        .append(rcHardwareState.getGoHomeButton().isClicked()).append("\n");
                stringBuffer.append("RightHorizontalChanged: ")
                        .append(rcHardwareState.getRightStick().getHorizontalPosition())
                        .append("\n");
                showStringBufferResult();
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
    }

    @Override
    public int getDescription() {
        return R.string.remote_controller_listview_push_info;
    }
}
