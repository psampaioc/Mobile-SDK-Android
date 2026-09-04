package com.dji.sdk.sample.operate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.controller.MainActivity;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.transport.ui.TransportView;
import com.dji.sdk.sample.transport.TransportSessionManager;
import com.dji.sdk.sample.operate.fpv.FloatingFpvView;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.flightcontroller.FlightControllerState;
import dji.common.airlink.PhysicalSource;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.RTKState;
import dji.common.battery.BatteryState;
import dji.common.gimbal.GimbalState;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.sdk.airlink.AirLink;
import dji.keysdk.AirLinkKey;
import dji.keysdk.KeyManager;
import dji.keysdk.callback.ActionCallback;

/**
 * Passive landscape operator-shell MVP.
 *
 * <p>This view owns only passive DJI listeners while visible: primary video, flight state, RTK,
 * and gimbal state. It sends no aircraft, gimbal, mission, or virtual-stick command. The menu
 * callback is navigation-only; the hosting activity decides which view to show.</p>
 */
public final class OperateMvpView extends LinearLayout implements PresentableView {
    private enum DetailGroup { CONNECTION, POSITIONING, POWER, SAFETY }
    /** Navigation requests emitted by the top-left menu. */
    public enum Destination { OPERATE, MISSION, TRANSPORT_CONFIGURATION, DJI_CONNECTION, SETTINGS, DEMOS }

    /** Host callback for handling an operator menu selection. */
    public interface MenuListener {
        void onDestinationSelected(@NonNull Destination destination);
    }

    private MenuListener menuListener;
    private final LinearLayout operatorMenu;
    private final LinearLayout sourcePanel;
    private final LinearLayout gimbalPanel;
    private final ScrollView detailPanel;
    private final View menuButton;
    private final TextView primarySource;
    private final TextView primaryStreamStatus;
    private final TextView primaryFrameAge;
    private final TextView fpvSource;
    private final TextView fpvStreamStatus;
    private final TextView fpvFrameAge;
    private final TextView cendenceStatus;
    private final TextView aircraftStatus;
    private final TextView linkStatus;
    private final TextView rtkStatus;
    private final TextView gpsStatus;
    private final TextView rcBatteryStatus;
    private final TextView batteryOneStatus;
    private final TextView batteryTwoStatus;
    private final TextView detailTitle;
    private final TextView detailBody;
    private final LinearLayout detailChecklist;
    private final TextView connectionAttention;
    private final TextView positioningAttention;
    private final TextView powerAttention;
    private final TextView noSignalTitle;
    private final TextView noSignalDetail;
    private final TextView latitude;
    private final TextView longitude;
    private final TextView altitude;
    private final TextView heading;
    private final TextView velocity;
    private final TextView gimbalSummary;
    private final TextView gimbalPitch;
    private final TextView gimbalRoll;
    private final TextView gimbalYaw;
    private final SurfaceView videoSurface;
    private final SurfaceView fpvVideoSurface;
    private final TextView fpvWarning;
    private final FrameLayout videoContent;
    private final FrameLayout pageOverlay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DJICodecManager codecManager;
    private DJICodecManager fpvCodecManager;
    private CallbackMulticaster.Subscription hubSubscription;
    private CallbackMulticaster.Subscription readinessSubscription;
    private final AtomicBoolean primarySignalPostPending = new AtomicBoolean();
    private final AtomicBoolean telemetryRenderPosted = new AtomicBoolean();
    private int displayedGimbalIndex = -1;
    private volatile FlightControllerState pendingFlightState;
    private volatile RTKState pendingRtkState;
    private volatile GimbalState pendingGimbalState;
    private volatile DjiDataHub.StatusSnapshot pendingStatusSnapshot;
    private final Map<Integer, BatteryState> pendingBatteryStates = new LinkedHashMap<>();
    private final Map<Integer, Long> batteryReceivedAtMs = new LinkedHashMap<>();
    private volatile dji.common.remotecontroller.BatteryState pendingRemoteControllerBattery;
    private volatile int pendingOcuSyncQuality = -1;
    private volatile boolean ocuSyncSupported;
    private volatile long lastFlightStateElapsedMs = -1L;
    private volatile long lastRtkStateElapsedMs = -1L;
    private volatile long lastGimbalStateElapsedMs = -1L;
    private volatile long lastRemoteControllerBatteryElapsedMs = -1L;
    private volatile long lastOcuSyncElapsedMs = -1L;
    private volatile boolean secondaryFeedAvailable;
    private boolean viewAttached;
    private volatile long lastVideoFrameElapsedMs = -1L;
    private volatile long lastFpvFrameElapsedMs = -1L;
    private String controllerConnection = "CONTROLLER --";
    private DetailGroup expandedDetail;
    private String connectionDetail = "--";
    private String positioningDetail = "--";
    private String powerDetail = "--";

    private final Runnable videoHealthCheck = new Runnable() {
        @Override public void run() {
            renderPrimaryFreshness();
            renderFpvFreshness();
            refreshControllerConnection();
            mainHandler.postDelayed(this, 500L);
        }
    };

    public OperateMvpView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        // MainActivity keeps prior views underneath during its push animation. Consume blank
        // touches here so they cannot activate a demo that is visually behind Operate.
        setClickable(true);
        LayoutInflater.from(context).inflate(R.layout.view_operate_mvp, this, true);

        operatorMenu = findViewById(R.id.operate_mvp_menu_panel);
        sourcePanel = findViewById(R.id.operate_mvp_source_panel);
        gimbalPanel = findViewById(R.id.operate_mvp_gimbal_panel);
        detailPanel = findViewById(R.id.operate_mvp_detail_panel);
        menuButton = findViewById(R.id.operate_mvp_menu_button);
        primarySource = findViewById(R.id.operate_mvp_primary_source);
        primaryStreamStatus = findViewById(R.id.operate_mvp_primary_stream_status);
        primaryFrameAge = findViewById(R.id.operate_mvp_primary_frame_age);
        fpvSource = findViewById(R.id.operate_mvp_fpv_source);
        fpvStreamStatus = findViewById(R.id.operate_mvp_fpv_stream_status);
        fpvFrameAge = findViewById(R.id.operate_mvp_fpv_frame_age);
        cendenceStatus = findViewById(R.id.operate_mvp_cendence_status);
        aircraftStatus = findViewById(R.id.operate_mvp_aircraft_status);
        linkStatus = findViewById(R.id.operate_mvp_link_status);
        rtkStatus = findViewById(R.id.operate_mvp_rtk_status);
        gpsStatus = findViewById(R.id.operate_mvp_gps_status);
        rcBatteryStatus = findViewById(R.id.operate_mvp_rc_battery_status);
        batteryOneStatus = findViewById(R.id.operate_mvp_battery_one_status);
        batteryTwoStatus = findViewById(R.id.operate_mvp_battery_two_status);
        detailTitle = findViewById(R.id.operate_mvp_detail_title);
        detailBody = findViewById(R.id.operate_mvp_detail_body);
        detailChecklist = findViewById(R.id.operate_mvp_detail_checklist);
        connectionAttention = findViewById(R.id.operate_mvp_connection_attention);
        positioningAttention = findViewById(R.id.operate_mvp_positioning_attention);
        powerAttention = findViewById(R.id.operate_mvp_power_attention);
        noSignalTitle = findViewById(R.id.operate_mvp_no_signal_title);
        noSignalDetail = findViewById(R.id.operate_mvp_no_signal_detail);
        latitude = findViewById(R.id.operate_mvp_latitude);
        longitude = findViewById(R.id.operate_mvp_longitude);
        altitude = findViewById(R.id.operate_mvp_altitude);
        heading = findViewById(R.id.operate_mvp_heading);
        velocity = findViewById(R.id.operate_mvp_velocity);
        gimbalSummary = findViewById(R.id.operate_mvp_gimbal_summary);
        gimbalPitch = findViewById(R.id.operate_mvp_gimbal_pitch);
        gimbalRoll = findViewById(R.id.operate_mvp_gimbal_roll);
        gimbalYaw = findViewById(R.id.operate_mvp_gimbal_yaw);
        videoSurface = findViewById(R.id.operate_mvp_video_surface);
        videoContent = findViewById(R.id.operate_mvp_video_content);
        pageOverlay = findViewById(R.id.operate_mvp_page_overlay);
        pageOverlay.setClickable(true);
        videoSurface.setZOrderMediaOverlay(false);
        fpvVideoSurface = new SurfaceView(context);
        // SurfaceView layers do not follow normal FrameLayout child order. Keep the primary
        // decoder below the floating monitor and let normal FPV warning views stay above it.
        fpvVideoSurface.setZOrderMediaOverlay(true);
        FrameLayout fpvContent = new FrameLayout(context);
        fpvContent.setBackgroundColor(0xFF000000);
        fpvContent.addView(fpvVideoSurface, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        fpvWarning = new TextView(context);
        fpvWarning.setGravity(Gravity.CENTER);
        fpvWarning.setTextSize(12f);
        fpvWarning.setVisibility(GONE);
        fpvContent.addView(fpvWarning, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        FloatingFpvView fpvOverlay = new FloatingFpvView(context);
        fpvOverlay.setContentView(fpvContent);
        FrameLayout.LayoutParams fpvParams = new FrameLayout.LayoutParams(dp(300), dp(180),
                Gravity.RIGHT | Gravity.BOTTOM);
        videoContent.addView(fpvOverlay, fpvParams);
        fpvOverlay.bringToFront();
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
                codecManager = new DJICodecManager(getContext(), holder, getWidth(), getHeight(),
                        UsbAccessoryService.VideoStreamSource.Camera);
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) { }
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (codecManager != null) {
                    codecManager.cleanSurface();
                    codecManager.destroyCodec();
                    codecManager = null;
                }
            }
        });
        fpvVideoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
                fpvCodecManager = new DJICodecManager(getContext(), holder, fpvVideoSurface.getWidth(),
                        fpvVideoSurface.getHeight(), UsbAccessoryService.VideoStreamSource.Fpv);
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) { }
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (fpvCodecManager != null) {
                    fpvCodecManager.cleanSurface();
                    fpvCodecManager.destroyCodec();
                    fpvCodecManager = null;
                }
            }
        });

        menuButton.setOnClickListener(view -> toggleExclusive(operatorMenu));
        findViewById(R.id.operate_mvp_operate_label).setOnClickListener(view -> toggleExclusive(operatorMenu));
        findViewById(R.id.operate_mvp_sources_button).setOnClickListener(view -> toggleExclusive(sourcePanel));
        findViewById(R.id.operate_mvp_connection_detail_button).setOnClickListener(
                view -> toggleDetail(DetailGroup.CONNECTION));
        findViewById(R.id.operate_mvp_positioning_detail_button).setOnClickListener(
                view -> toggleDetail(DetailGroup.POSITIONING));
        findViewById(R.id.operate_mvp_power_detail_button).setOnClickListener(
                view -> toggleDetail(DetailGroup.POWER));
        findViewById(R.id.operate_mvp_readiness_connection).setOnClickListener(
                view -> showDetail(DetailGroup.CONNECTION));
        findViewById(R.id.operate_mvp_readiness_navigation).setOnClickListener(
                view -> showDetail(DetailGroup.POSITIONING));
        findViewById(R.id.operate_mvp_readiness_power).setOnClickListener(
                view -> showDetail(DetailGroup.POWER));
        findViewById(R.id.operate_mvp_readiness_safety).setOnClickListener(
                view -> showDetail(DetailGroup.SAFETY));
        findViewById(R.id.operate_mvp_gimbal_cell).setOnClickListener(view -> toggle(gimbalPanel));
        findViewById(R.id.operate_mvp_menu_operate).setOnClickListener(
                view -> selectDestination(Destination.OPERATE));
        findViewById(R.id.operate_mvp_menu_mission).setOnClickListener(
                view -> selectDestination(Destination.MISSION));
        findViewById(R.id.operate_mvp_menu_transport).setOnClickListener(
                view -> selectDestination(Destination.TRANSPORT_CONFIGURATION));
        findViewById(R.id.operate_mvp_menu_dji_connection).setOnClickListener(
                view -> selectDestination(Destination.DJI_CONNECTION));
        findViewById(R.id.operate_mvp_menu_settings).setOnClickListener(
                view -> selectDestination(Destination.SETTINGS));
        findViewById(R.id.operate_mvp_menu_demos).setOnClickListener(
                view -> selectDestination(Destination.DEMOS));
        findViewById(R.id.operate_mvp_sources_left_fpv).setOnClickListener(
                view -> assignM210Sources(PhysicalSource.LEFT_CAM, PhysicalSource.FPV_CAM));
        findViewById(R.id.operate_mvp_sources_right_fpv).setOnClickListener(
                view -> assignM210Sources(PhysicalSource.RIGHT_CAM, PhysicalSource.FPV_CAM));
        findViewById(R.id.operate_mvp_sources_left_right).setOnClickListener(
                view -> assignM210Sources(PhysicalSource.LEFT_CAM, PhysicalSource.RIGHT_CAM));
        View primaryOptions = findViewById(R.id.operate_mvp_primary_options);
        findViewById(R.id.operate_mvp_primary_selector).setOnClickListener(view -> toggle(primaryOptions));
        setMenuListener(this::handleDefaultDestination);
    }

    @Override public int getDescription() { return R.string.operate_menu_operate; }

    @NonNull @Override public String getHint() { return getClass().getSimpleName() + ".java"; }

    /** Returns the surface on which the primary DJI camera feed is decoded. */
    @NonNull
    public SurfaceView getVideoSurface() {
        return videoSurface;
    }

    public void setMenuListener(MenuListener listener) {
        menuListener = listener;
    }

    /** Updates connection chrome. Expected to be called on the Android main thread. */
    public void setConnectionState(@NonNull String connection, @NonNull String rtk,
            @NonNull String signal, @NonNull String battery) {
        cendenceStatus.setText(connection);
        rtkStatus.setText(rtk);
        linkStatus.setText(signal);
        batteryOneStatus.setText(battery);
    }

    /** Updates the passive bottom telemetry strip. */
    public void setTelemetry(@NonNull String lat, @NonNull String lon, @NonNull String alt,
            @NonNull String hdg, @NonNull String vel) {
        latitude.setText(lat);
        longitude.setText(lon);
        altitude.setText(alt);
        heading.setText(hdg);
        velocity.setText(vel);
    }

    /** Updates the gimbal summary and the expandable readout. */
    public void setGimbalAngles(@NonNull String pitch, @NonNull String roll, @NonNull String yaw) {
        gimbalSummary.setText("PITCH  " + pitch);
        gimbalPitch.setText("PITCH   " + pitch);
        gimbalRoll.setText("ROLL     " + roll);
        gimbalYaw.setText("YAW      " + yaw);
    }

    /** Updates the selected camera/source labels. */
    public void setSelectedSource(@NonNull String source) {
        primarySource.setText(source);
    }

    /** Shows a non-flight page without removing the live status chrome from this operator shell. */
    public void showPage(@NonNull View page) {
        pageOverlay.removeAllViews();
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        pageOverlay.addView(page, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pageOverlay.setVisibility(VISIBLE);
        videoContent.setVisibility(INVISIBLE);
    }

    /** Returns the centre panel to the primary/FPV video layout. */
    public void showOperatePage() {
        pageOverlay.removeAllViews();
        pageOverlay.setVisibility(GONE);
        videoContent.setVisibility(VISIBLE);
    }

    /** Lets the host keep Android Back inside the persistent operator shell. */
    public boolean handleBackPressed() {
        if (pageOverlay.getVisibility() != VISIBLE) return false;
        showOperatePage();
        return true;
    }

    /** Shows or hides the no-signal overlay without changing the underlying decoder surface. */
    public void setVideoSignal(boolean available, @NonNull String detail) {
        noSignalTitle.setVisibility(available ? GONE : VISIBLE);
        noSignalDetail.setVisibility(available ? GONE : VISIBLE);
        noSignalDetail.setText(detail);
    }

    private void selectDestination(Destination destination) {
        operatorMenu.setVisibility(GONE);
        if (destination == Destination.OPERATE) {
            showOperatePage();
            return;
        }
        if (menuListener != null) {
            menuListener.onDestinationSelected(destination);
        }
    }

    private void handleDefaultDestination(@NonNull Destination destination) {
        if (destination == Destination.MISSION) {
            showPage(new MissionMvpView(getContext()));
        } else if (destination == Destination.TRANSPORT_CONFIGURATION) {
            showPage(new TransportView(getContext()));
        } else if (destination == Destination.DJI_CONNECTION && getContext() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getContext();
            activity.onBackPressed();
            activity.onBackPressed();
        } else if (destination == Destination.SETTINGS) {
            TextView settings = new TextView(getContext());
            settings.setText("SETTINGS\n\nThis MVP keeps connection and video status in the header. Additional settings are not configured yet.");
            settings.setTextColor(0xFF162A36);
            settings.setTextSize(18f);
            settings.setPadding(32, 32, 32, 32);
            settings.setBackgroundColor(0xFFE8F3FA);
            showPage(settings);
        } else if (destination == Destination.DEMOS && getContext() instanceof MainActivity) {
            ((MainActivity) getContext()).showSampleDemos();
        }
    }

    private static void toggle(View panel) {
        panel.setVisibility(panel.getVisibility() == VISIBLE ? GONE : VISIBLE);
    }

    private void toggleDetail(@NonNull DetailGroup group) {
        if (detailPanel.getVisibility() == VISIBLE && expandedDetail == group) {
            detailPanel.setVisibility(GONE);
            expandedDetail = null;
            return;
        }
        showDetail(group);
    }

    private void showDetail(@NonNull DetailGroup group) {
        expandedDetail = group;
        renderExpandedDetail();
        positionDetailPanel(group);
        operatorMenu.setVisibility(GONE);
        sourcePanel.setVisibility(GONE);
        detailPanel.setVisibility(VISIBLE);
    }

    /** Anchors the compact detail sheet under the chevron that opened it. */
    private void positionDetailPanel(@NonNull DetailGroup group) {
        int id = group == DetailGroup.CONNECTION ? R.id.operate_mvp_connection_detail_button
                : group == DetailGroup.POSITIONING ? R.id.operate_mvp_positioning_detail_button
                : R.id.operate_mvp_power_detail_button;
        View anchor = findViewById(id);
        detailPanel.post(() -> {
            android.widget.FrameLayout.LayoutParams params =
                    (android.widget.FrameLayout.LayoutParams) detailPanel.getLayoutParams();
            int width = detailPanel.getWidth() > 0 ? detailPanel.getWidth() : dp(360);
            int maxLeft = Math.max(dp(11), getWidth() - width - dp(11));
            params.leftMargin = Math.max(dp(11), Math.min(maxLeft, anchor.getRight() - width));
            params.topMargin = dp(55);
            detailPanel.setLayoutParams(params);
        });
    }

    private void toggleExclusive(View panel) {
        boolean opening = panel.getVisibility() != VISIBLE;
        operatorMenu.setVisibility(GONE);
        sourcePanel.setVisibility(GONE);
        detailPanel.setVisibility(GONE);
        if (opening) panel.setVisibility(VISIBLE);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (operatorMenu.getVisibility() == VISIBLE && !isTouchInside(event, operatorMenu)
                    && !isTouchInside(event, menuButton)
                    && !isTouchInside(event, findViewById(R.id.operate_mvp_operate_label))) {
                operatorMenu.setVisibility(GONE);
            }
            if (sourcePanel.getVisibility() == VISIBLE && !isTouchInside(event, sourcePanel)
                    && !isTouchInside(event, findViewById(R.id.operate_mvp_sources_button))) {
                sourcePanel.setVisibility(GONE);
            }
            if (detailPanel.getVisibility() == VISIBLE && !isTouchInside(event, detailPanel)) {
                detailPanel.setVisibility(GONE);
                expandedDetail = null;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isTouchInside(MotionEvent event, View view) {
        if (view == null || view.getVisibility() != VISIBLE) return false;
        Rect bounds = new Rect();
        view.getGlobalVisibleRect(bounds);
        return bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus().post(new MainActivity.RequestStartFullScreenEvent());
        viewAttached = true;
        resetStatusSnapshot();
        lastVideoFrameElapsedMs = -1L;
        lastFpvFrameElapsedMs = -1L;
        setVideoSignal(false, "WAITING FOR CAMERA FEED");
        try {
            TransportSessionManager.getInstance().reconcile(getContext(),
                    DJISampleApplication.getAircraftInstance());
        } catch (Exception ignored) {
            // TransportView shows the actionable endpoint/error detail. Operate must stay usable.
        }
        FlightReadinessStore readinessStore = FlightReadinessStore.getInstance();
        readinessStore.start();
        readinessSubscription = readinessStore.addListener(decision -> mainHandler.post(() -> {
            if (viewAttached) renderExpandedDetail();
        }));
        attachVideo();
        attachPassiveTelemetry();
        mainHandler.post(videoHealthCheck);
    }

    @Override protected void onDetachedFromWindow() {
        viewAttached = false;
        detachVideo();
        detachPassiveTelemetry();
        if (readinessSubscription != null) readinessSubscription.close();
        readinessSubscription = null;
        mainHandler.removeCallbacks(videoHealthCheck);
        DJISampleApplication.getEventBus().post(new MainActivity.RequestEndFullScreenEvent());
        super.onDetachedFromWindow();
    }

    private void attachVideo() {
        if (!viewAttached) return;
        Aircraft aircraft = DJISampleApplication.getAircraftInstance();
        if (aircraft == null || !aircraft.isConnected()) {
            setVideoSignal(false, "WAITING FOR DJI VIDEO FEED");
            mainHandler.postDelayed(this::attachVideo, 1000L);
            return;
        }
        try {
            TransportSessionManager.getInstance().reconcile(getContext(), aircraft);
        } catch (Exception ignored) {
            // Endpoint/status view exposes configuration failures; video must remain usable.
        }
        attachHub();
    }

    private void detachVideo() {
        detachHub();
    }

    private void attachPassiveTelemetry() {
        Aircraft aircraft = DJISampleApplication.getAircraftInstance();
        if (aircraft == null || !aircraft.isConnected()) {
            renderConnectionState();
            renderStatusOnMain();
            return;
        }
        attachHub();
        renderConnectionState();
        renderStatusOnMain();
    }

    private void detachPassiveTelemetry() {
        detachHub();
    }

    private void attachHub() {
        DjiDataHub hub = DjiDataHub.getInstance();
        if (hubSubscription != null) return;
        hubSubscription = hub.addListener(new DjiDataHub.Listener() {
            @Override public void onVideo(@NonNull DjiDataHub.Feed feed, byte[] buffer, int size,
                    @NonNull String source) {
                if (feed == DjiDataHub.Feed.PRIMARY) {
                    DJICodecManager decoder = codecManager;
                    if (decoder != null) decoder.sendDataToDecoder(buffer, size,
                            UsbAccessoryService.VideoStreamSource.Camera.getIndex());
                    lastVideoFrameElapsedMs = SystemClock.elapsedRealtime();
                    postPrimarySignal();
                } else {
                    DJICodecManager decoder = fpvCodecManager;
                    if (decoder != null) decoder.sendDataToDecoder(buffer, size,
                            UsbAccessoryService.VideoStreamSource.Fpv.getIndex());
                    lastFpvFrameElapsedMs = SystemClock.elapsedRealtime();
                }
            }
            @Override public void onPhysicalSource(@NonNull DjiDataHub.Feed feed,
                    @NonNull PhysicalSource source) {
                mainHandler.post(() -> {
                    if (!viewAttached) return;
                    if (feed == DjiDataHub.Feed.PRIMARY) primarySource.setText(String.valueOf(source));
                    else fpvSource.setText(String.valueOf(source));
                });
            }
            @Override public void onFeedAvailability(@NonNull DjiDataHub.Feed feed,
                    boolean available) {
                if (feed == DjiDataHub.Feed.SECONDARY) {
                    secondaryFeedAvailable = available;
                    if (!available) lastFpvFrameElapsedMs = -1L;
                }
            }
            @Override public void onStatusSnapshot(@NonNull DjiDataHub.StatusSnapshot snapshot) {
                pendingStatusSnapshot = snapshot;
                scheduleTelemetryRender();
            }
        });
        setSelectedSource(hub.getSource(DjiDataHub.Feed.PRIMARY));
        fpvSource.setText(hub.getSource(DjiDataHub.Feed.SECONDARY));
        secondaryFeedAvailable = hub.isAvailable(DjiDataHub.Feed.SECONDARY);
    }

    private void detachHub() {
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
        primarySignalPostPending.set(false);
        telemetryRenderPosted.set(false);
        secondaryFeedAvailable = false;
        resetStatusSnapshot();
    }

    /** A reopened Operate surface waits for fresh hub events; it never presents an old snapshot. */
    private void resetStatusSnapshot() {
        pendingFlightState = null;
        pendingRtkState = null;
        pendingGimbalState = null;
        pendingStatusSnapshot = null;
        pendingRemoteControllerBattery = null;
        pendingOcuSyncQuality = -1;
        ocuSyncSupported = false;
        lastFlightStateElapsedMs = -1L;
        lastRtkStateElapsedMs = -1L;
        lastGimbalStateElapsedMs = -1L;
        lastRemoteControllerBatteryElapsedMs = -1L;
        lastOcuSyncElapsedMs = -1L;
        synchronized (pendingBatteryStates) {
            pendingBatteryStates.clear();
            batteryReceivedAtMs.clear();
        }
    }

    private void postPrimarySignal() {
        if (!primarySignalPostPending.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            primarySignalPostPending.set(false);
            if (!viewAttached) return;
            setVideoSignal(true, "");
        });
    }

    /** Coalesces high-rate DJI telemetry onto the main thread without retaining video buffers. */
    private void scheduleTelemetryRender() {
        if (!telemetryRenderPosted.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            telemetryRenderPosted.set(false);
            if (!viewAttached) return;
            applyStatusSnapshot(pendingStatusSnapshot);
            FlightControllerState flightState = pendingFlightState;
            RTKState rtkState = pendingRtkState;
            GimbalState gimbalState = pendingGimbalState;
            if (flightState != null) renderFlightStateOnMain(flightState);
            if (rtkState != null) rtkStatus.setText(String.format(Locale.US, "RTK %s %d",
                    String.valueOf(rtkState.getPositioningSolution()), rtkState.getSatelliteCount()));
            if (gimbalState != null && gimbalState.getAttitudeInDegrees() != null) setGimbalAngles(
                    String.format(Locale.US, "%.1f°", gimbalState.getAttitudeInDegrees().getPitch()),
                    String.format(Locale.US, "%.1f°", gimbalState.getAttitudeInDegrees().getRoll()),
                    String.format(Locale.US, "%.1f°", gimbalState.getAttitudeInDegrees().getYaw()));
            renderStatusOnMain();
        });
    }

    /** Copies the hub's immutable sample only on the UI thread, keeping one coherent render input. */
    private void applyStatusSnapshot(DjiDataHub.StatusSnapshot snapshot) {
        if (snapshot == null) return;
        pendingFlightState = snapshot.flightState;
        pendingRtkState = snapshot.rtkState;
        pendingRemoteControllerBattery = snapshot.remoteControllerBattery;
        pendingOcuSyncQuality = snapshot.ocuSyncDownlinkQuality;
        ocuSyncSupported = snapshot.ocuSyncAvailable;
        lastFlightStateElapsedMs = snapshot.flightStateElapsedMs;
        lastRtkStateElapsedMs = snapshot.rtkStateElapsedMs;
        lastGimbalStateElapsedMs = snapshot.gimbalStateElapsedMs;
        lastRemoteControllerBatteryElapsedMs = snapshot.remoteControllerBatteryElapsedMs;
        lastOcuSyncElapsedMs = snapshot.ocuSyncElapsedMs;
        synchronized (pendingBatteryStates) {
            pendingBatteryStates.clear();
            pendingBatteryStates.putAll(snapshot.aircraftBatteryStates);
            batteryReceivedAtMs.clear();
            batteryReceivedAtMs.putAll(snapshot.aircraftBatteryElapsedMs);
        }
        if (displayedGimbalIndex < 0 && !snapshot.gimbalStates.isEmpty()) {
            displayedGimbalIndex = snapshot.gimbalStates.keySet().iterator().next();
        }
        pendingGimbalState = snapshot.gimbalStates.get(displayedGimbalIndex);
    }

    private void renderFlightStateOnMain(FlightControllerState state) {
            LocationCoordinate3D location = state.getAircraftLocation();
            if (location == null) return;
            double latitudeValue = location.getLatitude();
            double longitudeValue = location.getLongitude();
            double velocityValue = Math.sqrt(state.getVelocityX() * state.getVelocityX()
                    + state.getVelocityY() * state.getVelocityY() + state.getVelocityZ() * state.getVelocityZ());
            setTelemetry(String.format(Locale.US, "%.6f", latitudeValue),
                    String.format(Locale.US, "%.6f", longitudeValue),
                    String.format(Locale.US, "%.1f m", location.getAltitude()),
                    String.format(Locale.US, "%d°", state.getAircraftHeadDirection()),
                    String.format(Locale.US, "%.1f m/s", velocityValue));
            gpsStatus.setText(String.format(Locale.US, "GPS %s %d",
                    String.valueOf(state.getGPSSignalLevel()), state.getSatelliteCount()));
    }

    private void renderConnectionState() {
        Aircraft aircraft = DJISampleApplication.getAircraftInstance();
        boolean aircraftConnected = aircraft != null && aircraft.isConnected();
        RemoteController controller = aircraft == null ? null : aircraft.getRemoteController();
        boolean controllerConnected = controller != null && controller.isConnected();
        controllerConnection = controllerConnected ? "CENDENCE OK" : "CENDENCE --";
        cendenceStatus.setText(controllerConnection);
        aircraftStatus.setText(aircraftConnected ? "AIRCRAFT OK" : "AIRCRAFT --");
        applySeverity(cendenceStatus, controllerConnected ? OperateStatusModel.Severity.HEALTHY
                : OperateStatusModel.Severity.UNAVAILABLE);
        applySeverity(aircraftStatus, aircraftConnected ? OperateStatusModel.Severity.HEALTHY
                : OperateStatusModel.Severity.UNAVAILABLE);
    }

    private void renderStatusOnMain() {
        if (!viewAttached) return;
        long now = SystemClock.elapsedRealtime();
        FlightControllerState flightState = pendingFlightState;
        RTKState rtkState = pendingRtkState;
        dji.common.remotecontroller.BatteryState rcBattery = pendingRemoteControllerBattery;
        renderConnectionState();

        OperateStatusModel.Severity linkSeverity = OperateStatusModel.freshness(
                lastOcuSyncElapsedMs, now, ocuSyncSupported);
        if (linkSeverity == OperateStatusModel.Severity.HEALTHY && pendingOcuSyncQuality >= 0) {
            linkStatus.setText(String.format(Locale.US, "LINK %d", pendingOcuSyncQuality));
        } else {
            linkStatus.setText(ocuSyncSupported ? "LINK WAIT" : "LINK N/A");
        }
        applySeverity(linkStatus, linkSeverity);

        OperateStatusModel.Severity rtkSeverity = OperateStatusModel.freshness(
                lastRtkStateElapsedMs, now, rtkState != null);
        if (rtkState != null && rtkSeverity == OperateStatusModel.Severity.HEALTHY) {
            rtkStatus.setText(String.format(Locale.US, "RTK %s %d",
                    String.valueOf(rtkState.getPositioningSolution()), rtkState.getSatelliteCount()));
        } else if (rtkState == null) {
            rtkStatus.setText("RTK --");
        }
        applySeverity(rtkStatus, rtkSeverity);

        OperateStatusModel.Severity gpsSeverity = OperateStatusModel.freshness(
                lastFlightStateElapsedMs, now, flightState != null);
        if (flightState != null && gpsSeverity == OperateStatusModel.Severity.HEALTHY) {
            gpsStatus.setText(String.format(Locale.US, "GPS %s %d",
                    String.valueOf(flightState.getGPSSignalLevel()), flightState.getSatelliteCount()));
        } else if (flightState == null) {
            gpsStatus.setText("GPS --");
        }
        applySeverity(gpsStatus, gpsSeverity);

        OperateStatusModel.Severity gimbalSeverity = OperateStatusModel.freshness(
                lastGimbalStateElapsedMs, now, pendingGimbalState != null);

        int rcPercent = rcBattery == null ? -1 : rcBattery.getRemainingChargeInPercent();
        OperateStatusModel.Severity rcSeverity = OperateStatusModel.battery(rcPercent,
                lastRemoteControllerBatteryElapsedMs, now, rcBattery != null);
        rcBatteryStatus.setText(rcPercent < 0 ? "RC --" : String.format(Locale.US, "RC %d", rcPercent));
        applySeverity(rcBatteryStatus, rcSeverity);

        BatteryState batteryOne = batteryAt(0);
        BatteryState batteryTwo = batteryAt(1);
        renderAircraftBattery(batteryOneStatus, "B1", 0, batteryOne, now);
        renderAircraftBattery(batteryTwoStatus, "B2", 1, batteryTwo, now);

        boolean aircraftConnected = "AIRCRAFT OK".equals(aircraftStatus.getText().toString());
        boolean connectionAttentionNeeded = aircraftConnected
                && (!"CENDENCE OK".equals(controllerConnection)
                || linkSeverity != OperateStatusModel.Severity.HEALTHY);
        boolean positioningAttentionNeeded = aircraftConnected
                && (rtkSeverity != OperateStatusModel.Severity.HEALTHY
                || gpsSeverity != OperateStatusModel.Severity.HEALTHY
                || flightState == null || !flightState.isHomeLocationSet());
        boolean powerAttentionNeeded = aircraftConnected
                && (OperateStatusModel.requiresAttention(rcSeverity)
                || hasBatteryAttention(0, batteryOne, now) || hasBatteryAttention(1, batteryTwo, now));
        setAttention(connectionAttention, connectionAttentionNeeded, false);
        setAttention(positioningAttention, positioningAttentionNeeded, false);
        setAttention(powerAttention, powerAttentionNeeded,
                rcSeverity == OperateStatusModel.Severity.CRITICAL
                        || batterySeverity(0, batteryOne, now) == OperateStatusModel.Severity.CRITICAL
                        || batterySeverity(1, batteryTwo, now) == OperateStatusModel.Severity.CRITICAL);
        connectionDetail = String.format(Locale.US,
                "Cendence remote controller\n%s\n\nAircraft\n%s\n\nDJI OcuSync link\n%s",
                cendenceStatus.getText(), aircraftStatus.getText(), linkStatus.getText());
        positioningDetail = String.format(Locale.US,
                "RTK positioning\n%s\n\nGPS positioning\n%s\n\nHome point\n%s",
                rtkStatus.getText(), gpsStatus.getText(), flightState != null && flightState.isHomeLocationSet()
                        ? "SET" : "NOT SET OR NOT RECEIVED");
        powerDetail = String.format(Locale.US,
                "Cendence battery\n%s\n%s\n\nDrone battery 1 (DJI pack 0)\n%s\n\nDrone battery 2 (DJI pack 1)\n%s",
                rcBatteryStatus.getText(), rcBattery == null ? "NO LIVE SAMPLE" : String.format(Locale.US,
                        "%d mAh · %s", rcBattery.getRemainingChargeInmAh(),
                        rcBattery.isCharging() ? "CHARGING" : "NOT CHARGING"),
                batteryDetail(batteryOne, 0, now), batteryDetail(batteryTwo, 1, now));
        renderExpandedDetail();
    }

    private BatteryState batteryAt(int index) {
        synchronized (pendingBatteryStates) { return pendingBatteryStates.get(index); }
    }

    private void renderAircraftBattery(TextView view, String label, int index, BatteryState state, long now) {
        int percent = state == null ? -1 : state.getChargeRemainingInPercent();
        view.setText(percent < 0 ? label + " --" : String.format(Locale.US, "%s %d", label, percent));
        applySeverity(view, OperateStatusModel.battery(percent, batteryReceivedAt(index), now, state != null));
    }

    private boolean hasBatteryAttention(int index, BatteryState state, long now) {
        return OperateStatusModel.requiresAttention(batterySeverity(index, state, now));
    }

    @NonNull
    private OperateStatusModel.Severity batterySeverity(int index, BatteryState state, long now) {
        return OperateStatusModel.battery(state == null ? -1 : state.getChargeRemainingInPercent(),
                batteryReceivedAt(index), now, state != null);
    }

    private long batteryReceivedAt(int index) {
        synchronized (pendingBatteryStates) {
            Long receivedAt = batteryReceivedAtMs.get(index);
            return receivedAt == null ? -1L : receivedAt;
        }
    }

    @NonNull
    private String batteryDetail(BatteryState state, int index, long now) {
        if (state == null) return "NO LIVE SAMPLE";
        return String.format(Locale.US, "%s\n%d%% · %d mV · %.1f°C\nCELL %s · %s · %s",
                index == 0 ? "DRONE BATTERY 1 (DJI PACK 0)" : "DRONE BATTERY 2 (DJI PACK 1)",
                state.getChargeRemainingInPercent(), state.getVoltage(), state.getTemperature(),
                String.valueOf(state.getCellVoltageLevel()), String.valueOf(state.getConnectionState()),
                batterySeverity(index, state, now).name());
    }

    private void setAttention(TextView marker, boolean attention, boolean critical) {
        marker.setVisibility(attention ? VISIBLE : GONE);
        marker.setTextColor(critical ? 0xFFFF3145 : 0xFFE29A22);
        marker.setContentDescription(attention ? (critical ? "critical status needs review"
                : "status needs review") : "");
    }

    private void renderExpandedDetail() {
        if (expandedDetail == null) return;
        FlightReadinessModel.Decision decision = FlightReadinessStore.getInstance().currentDecision();
        FlightReadinessModel.Group group = expandedDetail == DetailGroup.CONNECTION
                ? FlightReadinessModel.Group.AIRCRAFT_LINK
                : expandedDetail == DetailGroup.POSITIONING ? FlightReadinessModel.Group.NAVIGATION_HOME
                : expandedDetail == DetailGroup.POWER ? FlightReadinessModel.Group.POWER
                : FlightReadinessModel.Group.SAFETY_CONFIGURATION;
        String title = group == FlightReadinessModel.Group.AIRCRAFT_LINK ? "AIRCRAFT & LINK"
                : group == FlightReadinessModel.Group.NAVIGATION_HOME ? "NAVIGATION & HOME"
                : group == FlightReadinessModel.Group.POWER ? "POWER"
                : "SAFETY REVIEW";
        StringBuilder body = new StringBuilder("STATUS — ").append(decision.getState().name())
                .append('\n').append(decision.getExplanation());
        for (FlightReadinessModel.Row row : decision.getRows()) {
            if (row.getGroup() != group) continue;
            body.append("\n\n").append(row.getLabel()).append("\n")
                    .append(row.getValue()).append(" · ").append(row.getState().name());
            if (row.getReason() != null) body.append("\n").append(row.getReason().getMessage());
        }
        if (group == FlightReadinessModel.Group.SAFETY_CONFIGURATION) {
            body.append("\n\n").append(FlightReadinessStore.getInstance()
                    .getSafetyConfiguration().getSummary());
        }
        detailTitle.setText(title);
        detailBody.setText(body.toString());
        renderChecklist(group);
    }

    private void renderChecklist(@NonNull FlightReadinessModel.Group group) {
        detailChecklist.removeAllViews();
        if (group != FlightReadinessModel.Group.SAFETY_CONFIGURATION) return;
        FlightReadinessStore store = FlightReadinessStore.getInstance();
        java.util.Set<FlightReadinessSession.ChecklistItem> acknowledged =
                store.getSession().getAcknowledgedItems();
        for (FlightReadinessSession.ChecklistItem item : FlightReadinessSession.ChecklistItem.values()) {
            Button button = new Button(getContext());
            boolean checked = acknowledged.contains(item);
            button.setText((checked ? "✓  " : "□  ") + item.getLabel());
            button.setTextSize(11f);
            button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            button.setTextColor(checked ? 0xFF36B95C : 0xFF162A36);
            button.setBackgroundResource(R.drawable.operate_mvp_button);
            button.setOnClickListener(view -> store.acknowledge(item));
            detailChecklist.addView(button, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(38)));
        }
    }

    private static void applySeverity(TextView view, OperateStatusModel.Severity severity) {
        int color = severity == OperateStatusModel.Severity.HEALTHY ? 0xFF36B95C
                : severity == OperateStatusModel.Severity.WARNING ? 0xFFE29A22
                : severity == OperateStatusModel.Severity.CRITICAL ? 0xFFFF3145 : 0xFF667B87;
        view.setTextColor(color);
        for (Drawable drawable : view.getCompoundDrawables()) {
            if (drawable != null) drawable.mutate().setTint(color);
        }
        view.setContentDescription(view.getText() + ", " + severity.name().toLowerCase(Locale.US));
    }

    private void renderPrimaryFreshness() {
        if (!viewAttached) return;
        if (lastVideoFrameElapsedMs < 0L) {
            primaryStreamStatus.setText("NO SIGNAL");
            primaryStreamStatus.setTextColor(0xFFFF3145);
            primaryFrameAge.setText("NO FRAME RECEIVED");
            return;
        }
        long ageMs = SystemClock.elapsedRealtime() - lastVideoFrameElapsedMs;
        primaryFrameAge.setText(String.format(Locale.US, "FRAME AGE %.1f s", ageMs / 1000.0));
        if (ageMs < 2_000L) {
            primaryStreamStatus.setText("LIVE");
            primaryStreamStatus.setTextColor(0xFF36B95C);
            setVideoSignal(true, "");
        } else if (ageMs < 5_000L) {
            primaryStreamStatus.setText("STALE");
            primaryStreamStatus.setTextColor(0xFFE29A22);
            noSignalTitle.setText("PRIMARY STALE");
            noSignalTitle.setTextColor(0xFFE29A22);
            noSignalDetail.setTextColor(0xFFE29A22);
            setVideoSignal(false, primaryFrameAge.getText().toString());
        } else {
            primaryStreamStatus.setText("NO SIGNAL");
            primaryStreamStatus.setTextColor(0xFFFF3145);
            noSignalTitle.setText("NO SIGNAL");
            noSignalTitle.setTextColor(0xFFFF3145);
            noSignalDetail.setTextColor(0xFFFF3145);
            setVideoSignal(false, primaryFrameAge.getText().toString());
        }
    }

    private void refreshControllerConnection() {
        renderStatusOnMain();
    }

    private void renderFpvFreshness() {
        if (!viewAttached) return;
        if (!secondaryFeedAvailable) {
            fpvStreamStatus.setText("UNAVAILABLE");
            fpvStreamStatus.setTextColor(0xFF667B87);
            fpvFrameAge.setText("SECONDARY FEED NOT EXPOSED");
            showFpvWarning("NO SIGNAL\nWAITING FOR CAMERA FEED", 0xFFFF3145);
            return;
        }
        if (lastFpvFrameElapsedMs < 0L) {
            fpvStreamStatus.setText("NO SIGNAL");
            fpvStreamStatus.setTextColor(0xFFFF3145);
            fpvFrameAge.setText("NO FRAME RECEIVED");
            showFpvWarning("NO SIGNAL\nWAITING FOR CAMERA FEED", 0xFFFF3145);
            return;
        }
        long ageMs = SystemClock.elapsedRealtime() - lastFpvFrameElapsedMs;
        fpvFrameAge.setText(String.format(Locale.US, "FRAME AGE %.1f s", ageMs / 1000.0));
        if (ageMs < 2_000L) {
            fpvStreamStatus.setText("LIVE");
            fpvStreamStatus.setTextColor(0xFF36B95C);
            fpvWarning.setVisibility(GONE);
        } else if (ageMs < 5_000L) {
            fpvStreamStatus.setText("STALE");
            fpvStreamStatus.setTextColor(0xFFE29A22);
            showFpvWarning("STALE\n" + fpvFrameAge.getText(), 0xFFE29A22);
        } else {
            fpvStreamStatus.setText("NO SIGNAL");
            fpvStreamStatus.setTextColor(0xFFFF3145);
            showFpvWarning("NO SIGNAL\n" + fpvFrameAge.getText(), 0xFFFF3145);
        }
    }

    private void showFpvWarning(String message, int color) {
        fpvWarning.setText(message);
        fpvWarning.setTextColor(color);
        fpvWarning.setVisibility(VISIBLE);
    }

    private void assignM210Sources(PhysicalSource primary, PhysicalSource secondary) {
        AirLink airLink = DJISDKManager.getInstance().getProduct() == null ? null
                : DJISDKManager.getInstance().getProduct().getAirLink();
        if (airLink == null || !airLink.isOcuSyncLinkSupported()) {
            primaryStreamStatus.setText("SOURCE ROUTING UNAVAILABLE");
            return;
        }
        AirLinkKey key = AirLinkKey.createOcuSyncLinkKey(AirLinkKey.ASSIGN_SOURCE_TO_PRIMARY_CHANNEL);
        KeyManager.getInstance().performAction(key, new ActionCallback() {
            @Override public void onSuccess() { sourcePanel.setVisibility(GONE); }
            @Override public void onFailure(@NonNull dji.common.error.DJIError error) {
                primaryStreamStatus.setText("SOURCE CHANGE FAILED");
            }
        }, primary, secondary);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
