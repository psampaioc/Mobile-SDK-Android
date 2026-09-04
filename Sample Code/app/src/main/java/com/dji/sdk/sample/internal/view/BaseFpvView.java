package com.dji.sdk.sample.internal.view;

import android.app.Service;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.widget.FrameLayout;
import com.dji.sdk.sample.R;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.codec.DJICodecManager;

/**
 * This class is designed for showing the fpv video feed from the camera or Lightbridge 2.
 */
public class BaseFpvView extends FrameLayout implements TextureView.SurfaceTextureListener{
    private CallbackMulticaster.Subscription hubSubscription;
    private DJICodecManager codecManager = null;

    public BaseFpvView(Context context, AttributeSet attrs) {
        super(context, attrs);

        initUI();
    }

    private void initUI() {
        LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(Service.LAYOUT_INFLATER_SERVICE);

        layoutInflater.inflate(R.layout.view_fpv_and_camera_display, this, true);

        TextureView mVideoSurface = (TextureView) findViewById(R.id.texture_video_previewer_surface);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);

        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (hubSubscription != null) return;
        hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
            @Override public void onVideo(DjiDataHub.Feed feed, byte[] bytes, int size,
                    String source) {
                if (feed == DjiDataHub.Feed.SECONDARY && codecManager != null) {
                    codecManager.sendDataToDecoder(bytes, size,
                            UsbAccessoryService.VideoStreamSource.Fpv.getIndex());
                }
            }
        });
    }

    @Override protected void onDetachedFromWindow() {
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (codecManager == null) {
            codecManager = new DJICodecManager(getContext().getApplicationContext(),
                                               surface,
                                               width,
                                               height,
                                               UsbAccessoryService.VideoStreamSource.Fpv);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = null;
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }
}
