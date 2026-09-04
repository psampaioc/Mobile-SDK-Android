package com.dji.sdk.sample.internal.utils;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.NonNull;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import dji.common.airlink.PhysicalSource;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.thirdparty.rx.Observable;
import dji.thirdparty.rx.Subscription;
import dji.thirdparty.rx.android.schedulers.AndroidSchedulers;
import dji.thirdparty.rx.functions.Action1;

/**
 * VideoView will show the live video for the given video feed.
 */
public class VideoFeedView extends SurfaceView {
    //region Properties
    private final static String TAG = "DULFpvWidget";
    private DJICodecManager codecManager = null;
    private VideoFeeder.VideoDataListener videoDataListener = null;
    private CallbackMulticaster.Subscription hubSubscription;
    private DjiDataHub.Feed hubFeed = DjiDataHub.Feed.PRIMARY;
    private SourceListener sourceListener;
    private int videoWidth;
    private int videoHeight;
    private boolean isPrimaryVideoFeed;
    private View coverView;
    private final long WAIT_TIME = 500; // Half of a second
    private AtomicLong lastReceivedFrameTime = new AtomicLong(0);
    private Observable timer =
        Observable.timer(100, TimeUnit.MICROSECONDS).observeOn(AndroidSchedulers.mainThread()).repeat();
    private Subscription subscription;
    private SurfaceHolder surfaceHolder;

    //endregion

    //region Life-Cycle
    public VideoFeedView(Context context) {
        this(context, null, 0);
    }

    public VideoFeedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoFeedView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    public void setCoverView(View view) {
        coverView = view;
    }

    /** Receives source updates from the shared hub instead of VideoFeeder directly. */
    public void setSourceListener(SourceListener listener) {
        sourceListener = listener;
        if (listener != null) listener.onSourceChanged(DjiDataHub.getInstance().getSource(hubFeed));
    }

    public interface SourceListener {
        void onSourceChanged(String source);
    }

    private void init(Context context) {
        // Avoid the rending exception in the Android Studio Preview view.
        if (isInEditMode()) {
            return;
        }

        surfaceHolder = getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
                if (codecManager == null) {
                    codecManager = new DJICodecManager(context,
                            surfaceHolder,
                            getWidth(),
                            getHeight(),
                            isPrimaryVideoFeed
                                    ? UsbAccessoryService.VideoStreamSource.Camera
                                    : UsbAccessoryService.VideoStreamSource.Fpv);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
                if (codecManager != null) {
                    codecManager.cleanSurface();
                    codecManager.destroyCodec();
                    codecManager = null;
                }
            }
        });

        subscription = timer.subscribe(new Action1() {
            @Override
            public void call(Object o) {
                final long now = System.currentTimeMillis();
                final long ellapsedTime = now - lastReceivedFrameTime.get();
                if (coverView != null) {
                    if (ellapsedTime > WAIT_TIME && !ModuleVerificationUtil.isMavic2Product()) {
                        if (coverView.getVisibility() == INVISIBLE) {
                            coverView.setVisibility(VISIBLE);
                        }
                    } else {
                        if (coverView.getVisibility() == VISIBLE) {
                            coverView.setVisibility(INVISIBLE);
                        }
                    }
                }
            }
        });
    }

    public VideoFeeder.VideoDataListener registerLiveVideo(VideoFeeder.VideoFeed videoFeed, boolean isPrimary) {
        isPrimaryVideoFeed = isPrimary;
        hubFeed = isPrimary ? DjiDataHub.Feed.PRIMARY : DjiDataHub.Feed.SECONDARY;
        attachHub();
        // Kept for source compatibility. Video delivery is now exclusively owned by DjiDataHub.
        return null;
    }

    private void attachHub() {
        if (hubSubscription != null) return;
        DjiDataHub hub = DjiDataHub.getInstance();
        hubSubscription = hub.addListener(new DjiDataHub.Listener() {
            @Override public void onVideo(@NonNull DjiDataHub.Feed feed, byte[] videoBuffer,
                    int size, @NonNull String source) {
                if (feed != hubFeed) return;
                lastReceivedFrameTime.set(System.currentTimeMillis());
                DJICodecManager decoder = codecManager;
                if (decoder != null) decoder.sendDataToDecoder(videoBuffer, size,
                        isPrimaryVideoFeed ? UsbAccessoryService.VideoStreamSource.Camera.getIndex()
                                : UsbAccessoryService.VideoStreamSource.Fpv.getIndex());
            }

            @Override public void onPhysicalSource(@NonNull DjiDataHub.Feed feed,
                    @NonNull PhysicalSource source) {
                if (feed == hubFeed && sourceListener != null) {
                    sourceListener.onSourceChanged(String.valueOf(source));
                }
            }
        });
        if (sourceListener != null) sourceListener.onSourceChanged(hub.getSource(hubFeed));
    }

    public void changeSourceResetKeyFrame() {
        if (codecManager != null) {
            codecManager.resetKeyFrame();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachHub();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
        super.onDetachedFromWindow();
        if (subscription != null && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
    }
}
