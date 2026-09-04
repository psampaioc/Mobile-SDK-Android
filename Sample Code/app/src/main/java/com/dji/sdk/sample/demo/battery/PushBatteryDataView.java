package com.dji.sdk.sample.demo.battery;

import android.content.Context;
import com.dji.sdk.sample.R;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.internal.view.BasePushDataView;
import dji.common.battery.BatteryState;

/**
 * Class for getting the battery information.
 */
public class PushBatteryDataView extends BasePushDataView {
    private CallbackMulticaster.Subscription hubSubscription;
    public PushBatteryDataView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        try {
            hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
                @Override public void onBatteryState(int index, BatteryState djiBatteryState) {
                    stringBuffer.delete(0, stringBuffer.length());

                    stringBuffer.append("BatteryEnergyRemainingPercent: ").
                        append(djiBatteryState.getChargeRemainingInPercent()).
                                    append("%\n");
                    stringBuffer.append("CurrentVoltage: ").
                        append(djiBatteryState.getVoltage()).append("mV\n");
                    stringBuffer.append("CurrentCurrent: ").
                        append(djiBatteryState.getCurrent()).append("mA\n");

                    showStringBufferResult();
                }
            });
        } catch (Exception ignored) {

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
        return R.string.battery_listview_push_info;
    }
}
