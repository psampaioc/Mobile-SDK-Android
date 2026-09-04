package com.dji.sdk.sample.demo.gimbal;

import android.content.Context;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.view.BasePushDataView;

import dji.common.gimbal.GimbalState;

/**
 * Class for getting gimbal information.
 */
public class PushGimbalDataView extends BasePushDataView {
    private CallbackMulticaster.Subscription hubSubscription;

    public PushGimbalDataView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (ModuleVerificationUtil.isGimbalModuleAvailable()) {
            hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
                @Override public void onGimbalState(int index, @NonNull GimbalState gimbalState) {
                    stringBuffer.delete(0, stringBuffer.length());

                    stringBuffer.append("PitchInDegrees: ").
                            append(gimbalState.getAttitudeInDegrees().getPitch()).append("\n");
                    stringBuffer.append("RollInDegrees: ").
                            append(gimbalState.getAttitudeInDegrees().getRoll()).append("\n");
                    stringBuffer.append("YawInDegrees: ").
                            append(gimbalState.getAttitudeInDegrees().getYaw()).append("\n");

                    showStringBufferResult();
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
    }

    @Override
    public int getDescription() {
        return R.string.gimbal_listview_push_info;
    }
}
