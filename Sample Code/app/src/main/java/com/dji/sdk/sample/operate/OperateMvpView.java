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
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.controller.MainActivity;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.djihub.DjiHomeState;
import com.dji.sdk.sample.transport.ui.TransportView;
import com.dji.sdk.sample.transport.TransportSessionManager;
import com.dji.sdk.sample.transport.TransportForegroundService;
import com.dji.sdk.sample.transport.TransportRuntime;
import com.dji.sdk.sample.transport.TransportEgressMetrics;
import com.dji.sdk.sample.recording.LocalPrimaryRecordingManager;
import com.dji.sdk.sample.recording.RecordingFolderPreferences;
import com.dji.sdk.sample.recording.RecordingState;
import com.dji.sdk.sample.operate.fpv.FloatingFpvView;
import com.dji.sdk.sample.missionplanner.MissionDefaults;
import com.dji.sdk.sample.missionplanner.MissionDefaultsPreferences;
import com.dji.sdk.sample.missionplanner.MissionFinishAction;
import com.dji.sdk.sample.missionplanner.MissionHeadingMode;
import com.dji.sdk.sample.rtk.NetworkRtkController;
import com.dji.sdk.sample.rtk.NetworkRtkProfile;
import com.dji.sdk.sample.rtk.NetworkRtkProfileStore;
import com.dji.sdk.sample.rtk.NetworkRtkUiState;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.flightcontroller.FlightControllerState;
import dji.common.error.DJIError;
import dji.common.airlink.PhysicalSource;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.flightcontroller.RTKState;
import dji.common.battery.BatteryState;
import dji.common.gimbal.GimbalState;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.products.Aircraft;
import dji.sdk.camera.Camera;
import dji.common.camera.ResolutionAndFrameRate;
import dji.common.util.CommonCallbacks;
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
    public enum Destination { OPERATE, MISSION, TRANSPORT_CONFIGURATION, DJI_CONNECTION, RTK, SETTINGS, DEMOS }

    /** Host callback for handling an operator menu selection. */
    public interface MenuListener {
        void onDestinationSelected(@NonNull Destination destination);
    }

    private MenuListener menuListener;
    private final LinearLayout operatorMenu;
    private final LinearLayout sourcePanel;
    private final LinearLayout recordingPanel;
    private final LinearLayout gimbalPanel;
    private final ScrollView detailPanel;
    private final View menuButton;
    private final TextView shellTitle;
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
    private final TextView landingAdvisory;
    private final TextView detailTitle;
    private final TextView detailBody;
    private final LinearLayout detailChecklist;
    /**
     * Checklist rows must stay attached while live DJI state refreshes. Recreating them on every
     * refresh can replace a row between ACTION_DOWN and ACTION_UP, which prevents its click from
     * ever completing on a busy connected aircraft.
     */
    private final Map<FlightReadinessSession.ChecklistItem, Button> safetyChecklistButtons =
            new LinkedHashMap<>();
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
    private final TextView recordingState;
    private final TextView recordingDetail;
    private final TextView recordingElapsed;
    private final Button recordingAction;
    private final SurfaceView videoSurface;
    private final SurfaceView fpvVideoSurface;
    private final TextView fpvWarning;
    private final FrameLayout videoContent;
    private final FrameLayout pageOverlay;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DJICodecManager codecManager;
    private DJICodecManager fpvCodecManager;
    private CallbackMulticaster.Subscription hubSubscription;
    private DjiDataHub.PrimaryVideoSubscription primaryVideoSubscription;
    private CallbackMulticaster.Subscription readinessSubscription;
    private CallbackMulticaster.Subscription recordingSubscription;
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
    private final OperateStatusDetailState detailState = new OperateStatusDetailState();
    private String powerDetail = "--";
    private final LowBatteryAdvisoryPreferences lowBatteryPreferences;
    private final MissionDefaultsPreferences missionDefaultsPreferences;
    private final PrimaryVideoProfilePreferences primaryVideoProfilePreferences;
    private final NetworkRtkProfileStore networkRtkProfileStore;
    private final NetworkRtkController networkRtkController = new NetworkRtkController();
    private final OperateShellState shellState = new OperateShellState();
    private LowBatteryAdvisory.Severity lastLandingAdvisorySeverity;

    private final Runnable videoHealthCheck = new Runnable() {
        @Override public void run() {
            renderPrimaryFreshness();
            renderFpvFreshness();
            refreshControllerConnection();
            renderRecordingSnapshot(LocalPrimaryRecordingManager.getInstance().snapshot(getContext()));
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
        lowBatteryPreferences = new LowBatteryAdvisoryPreferences(context);
        missionDefaultsPreferences = new MissionDefaultsPreferences(context);
        primaryVideoProfilePreferences = new PrimaryVideoProfilePreferences(context);
        networkRtkProfileStore = new NetworkRtkProfileStore(context);

        operatorMenu = findViewById(R.id.operate_mvp_menu_panel);
        sourcePanel = findViewById(R.id.operate_mvp_source_panel);
        recordingPanel = findViewById(R.id.operate_mvp_recording_panel);
        gimbalPanel = findViewById(R.id.operate_mvp_gimbal_panel);
        detailPanel = findViewById(R.id.operate_mvp_detail_panel);
        menuButton = findViewById(R.id.operate_mvp_menu_button);
        shellTitle = findViewById(R.id.operate_mvp_operate_label);
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
        landingAdvisory = findViewById(R.id.operate_mvp_landing_advisory);
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
        recordingState = findViewById(R.id.operate_mvp_recording_state);
        recordingDetail = findViewById(R.id.operate_mvp_recording_detail);
        recordingElapsed = findViewById(R.id.operate_mvp_recording_elapsed);
        recordingAction = findViewById(R.id.operate_mvp_recording_action);
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
        shellTitle.setOnClickListener(view -> toggleExclusive(operatorMenu));
        findViewById(R.id.operate_mvp_sources_button).setOnClickListener(view -> toggleExclusive(sourcePanel));
        findViewById(R.id.operate_mvp_recording_button).setOnClickListener(view -> toggleExclusive(recordingPanel));
        recordingAction.setOnClickListener(view -> toggleRecording());
        findViewById(R.id.operate_mvp_connection_detail_button).setOnClickListener(
                view -> toggleDetail(DetailGroup.CONNECTION));
        findViewById(R.id.operate_mvp_positioning_detail_button).setOnClickListener(
                view -> toggleDetail(DetailGroup.POSITIONING));
        findViewById(R.id.operate_mvp_power_detail_button).setOnClickListener(
                view -> toggleDetail(DetailGroup.POWER));
        findViewById(R.id.operate_mvp_readiness_connection).setOnClickListener(
                view -> showDetail(DetailGroup.CONNECTION, false));
        findViewById(R.id.operate_mvp_readiness_navigation).setOnClickListener(
                view -> showDetail(DetailGroup.POSITIONING, false));
        findViewById(R.id.operate_mvp_readiness_power).setOnClickListener(
                view -> showDetail(DetailGroup.POWER, false));
        findViewById(R.id.operate_mvp_readiness_safety).setOnClickListener(
                view -> showDetail(DetailGroup.SAFETY, false));
        findViewById(R.id.operate_mvp_gimbal_cell).setOnClickListener(view -> toggle(gimbalPanel));
        findViewById(R.id.operate_mvp_menu_operate).setOnClickListener(
                view -> selectDestination(Destination.OPERATE));
        findViewById(R.id.operate_mvp_menu_mission).setOnClickListener(
                view -> selectDestination(Destination.MISSION));
        findViewById(R.id.operate_mvp_menu_transport).setOnClickListener(
                view -> selectDestination(Destination.TRANSPORT_CONFIGURATION));
        findViewById(R.id.operate_mvp_menu_dji_connection).setOnClickListener(
                view -> selectDestination(Destination.DJI_CONNECTION));
        findViewById(R.id.operate_mvp_menu_rtk).setOnClickListener(
                view -> selectDestination(Destination.RTK));
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
        discardPageOverlay();
        pageOverlay.removeAllViews();
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        pageOverlay.addView(page, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pageOverlay.setVisibility(VISIBLE);
        videoContent.setVisibility(INVISIBLE);
    }

    /** Returns the centre panel to the primary/FPV video layout. */
    public void showOperatePage() {
        discardPageOverlay();
        pageOverlay.removeAllViews();
        pageOverlay.setVisibility(GONE);
        videoContent.setVisibility(VISIBLE);
        shellState.showOperate();
        renderShellTitle();
    }

    private void discardPageOverlay() {
        for (int index = 0; index < pageOverlay.getChildCount(); index++) {
            View child = pageOverlay.getChildAt(index);
            if (child instanceof MissionMvpView) ((MissionMvpView) child).discardPage();
        }
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
            shellState.show(OperateShellState.Page.MISSION);
            renderShellTitle();
            showPage(new MissionMvpView(getContext()));
        } else if (destination == Destination.TRANSPORT_CONFIGURATION) {
            shellState.show(OperateShellState.Page.TRANSPORT);
            renderShellTitle();
            showPage(new TransportView(getContext()));
        } else if (destination == Destination.DJI_CONNECTION && getContext() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getContext();
            activity.onBackPressed();
            activity.onBackPressed();
        } else if (destination == Destination.RTK) {
            shellState.show(OperateShellState.Page.RTK);
            renderShellTitle();
            showPage(createNetworkRtkPage());
        } else if (destination == Destination.SETTINGS) {
            shellState.show(OperateShellState.Page.SETTINGS);
            renderShellTitle();
            showPage(createSettingsPage());
        } else if (destination == Destination.DEMOS && getContext() instanceof MainActivity) {
            ((MainActivity) getContext()).showSampleDemos();
        }
    }

    private void renderShellTitle() {
        shellTitle.setText(shellState.getTitle());
    }

    /** Custom NTRIP configuration and live DJI state. It owns no DJI callback. */
    private View createNetworkRtkPage() {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(22), dp(26), dp(22)); content.setBackgroundColor(0xFFE8F3FA);
        TextView title = rtkText("CUSTOM NETWORK RTK", 20f, 0xFF162A36); content.addView(title);
        TextView hint = rtkText("NTRIP profile is stored only on this tablet. Connect and disconnect are explicit actions.", 12f, 0xFF667B87);
        hint.setPadding(0, dp(4), 0, dp(12)); content.addView(hint);
        NetworkRtkProfile saved = networkRtkProfileStore.load();
        EditText host = rtkInput("NTRIP Host", saved == null ? "" : saved.host, false);
        EditText port = rtkInput("Port", String.valueOf(saved == null ? 2101 : saved.port), true);
        EditText user = rtkInput("User", saved == null ? "" : saved.user, false);
        EditText password = rtkInput("Password", saved == null ? "" : saved.password, false);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText mount = rtkInput("Mountpoint", saved == null ? "" : saved.mountpoint, false);
        content.addView(host); content.addView(port); content.addView(user); content.addView(password); content.addView(mount);
        TextView action = rtkText("NOT CONNECTED", 12f, 0xFF667B87); action.setPadding(0, dp(6), 0, dp(4)); content.addView(action);
        LinearLayout actions = new LinearLayout(getContext()); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(getContext()); save.setText("SAVE CONFIGURATION");
        Button connect = new Button(getContext()); connect.setText("CONNECT RTK");
        Button disconnect = new Button(getContext()); disconnect.setText("DISCONNECT");
        actions.addView(save); actions.addView(connect); actions.addView(disconnect); content.addView(actions);
        TextView statusTitle = rtkText("LIVE STATUS", 14f, 0xFF162A36); statusTitle.setPadding(0, dp(24), 0, dp(6)); content.addView(statusTitle);
        TextView live = rtkText("WAITING FOR DJI RTK SAMPLE", 13f, 0xFF162A36); content.addView(live);
        TextView table = rtkText("", 12f, 0xFF425866); table.setPadding(0, dp(8), 0, dp(24)); content.addView(table);
        View.OnClickListener saveProfile = view -> {
            NetworkRtkProfile profile = readNetworkRtkProfile(host, port, user, password, mount, action);
            if (profile != null && networkRtkProfileStore.save(profile)) action.setText("Saved locally. No DJI connection was started.");
            else if (profile != null) action.setText("Could not encrypt and save this profile.");
        };
        save.setOnClickListener(saveProfile);
        connect.setOnClickListener(view -> {
            NetworkRtkProfile profile = readNetworkRtkProfile(host, port, user, password, mount, action);
            if (profile == null) return;
            action.setText("CONNECTING — waiting for DJI result...");
            networkRtkController.connect(profile, (accepted, detail) -> mainHandler.post(() -> {
                if (isActiveNetworkRtkPage(scroll)) action.setText(
                        (accepted ? "CONNECT REQUEST ACCEPTED: " : "CONNECT FAILED: ") + detail);
            }));
        });
        disconnect.setOnClickListener(view -> {
            action.setText("DISCONNECTING — waiting for DJI result...");
            networkRtkController.disconnect((accepted, detail) -> mainHandler.post(() -> {
                if (isActiveNetworkRtkPage(scroll)) action.setText(
                        (accepted ? "DISCONNECT REQUEST ACCEPTED: " : "DISCONNECT FAILED: ") + detail);
            }));
        });
        Runnable refresh = new Runnable() { @Override public void run() {
            if (!isActiveNetworkRtkPage(scroll)) return;
            DjiDataHub.StatusSnapshot snapshot = pendingStatusSnapshot;
            if (snapshot != null) renderNetworkRtkStatus(live, table, snapshot);
            mainHandler.postDelayed(this, 500L);
        }};
        mainHandler.post(refresh);
        scroll.addView(content); return scroll;
    }

    private boolean isActiveNetworkRtkPage(View page) {
        return pageOverlay.getVisibility() == VISIBLE && page.getParent() == pageOverlay;
    }

    private EditText rtkInput(String hint, String value, boolean number) {
        EditText input = new EditText(getContext()); input.setSingleLine(true); input.setHint(hint); input.setText(value);
        if (number) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(14f); return input;
    }

    private TextView rtkText(String value, float size, int color) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }

    private NetworkRtkProfile readNetworkRtkProfile(EditText host, EditText port, EditText user,
            EditText password, EditText mountpoint, TextView result) {
        try {
            NetworkRtkProfile profile = new NetworkRtkProfile(host.getText().toString(),
                    Integer.parseInt(port.getText().toString().trim()), user.getText().toString(),
                    password.getText().toString(), mountpoint.getText().toString());
            if (!profile.isValid()) { result.setText(profile.validationError()); return null; }
            return profile;
        } catch (NumberFormatException error) { result.setText("Port must be between 1 and 65535."); return null; }
    }

    private void renderNetworkRtkStatus(TextView live, TextView table, DjiDataHub.StatusSnapshot snapshot) {
        RTKState rtk = snapshot.rtkState;
        long now = SystemClock.elapsedRealtime();
        boolean rtkFresh = OperateStatusModel.isFresh(snapshot.rtkStateElapsedMs, now);
        String channel = OperateStatusModel.isFresh(snapshot.rtkNetworkServiceStateElapsedMs, now)
                ? NetworkRtkUiState.channel(snapshot.rtkNetworkServiceState) : "WAITING / STALE";
        String source = OperateStatusModel.isFresh(snapshot.rtkReferenceStationSourceElapsedMs, now)
                ? NetworkRtkUiState.source(snapshot.rtkReferenceStationSource) : "WAITING / STALE";
        live.setText("CHANNEL " + channel + "\nSOURCE " + source
                + "\nPOSITIONING " + (rtkFresh ? NetworkRtkUiState.solution(rtk) + " · "
                + NetworkRtkUiState.inUse(rtk) : "WAITING / STALE"));
        if (!rtkFresh || rtk == null) { table.setText("Coordinates and satellites: --\nNo fresh DJI RTK sample."); return; }
        dji.common.model.LocationCoordinate2D mobile = rtk.getMobileStationLocation();
        dji.common.model.LocationCoordinate2D base = rtk.getBaseStationLocation();
        table.setText("                         AIRCRAFT / MOBILE          REFERENCE / BASE"
                + "\nLatitude     " + rtkCoordinate(mobile, true) + "                 " + rtkCoordinate(base, true)
                + "\nLongitude  " + rtkCoordinate(mobile, false) + "                 " + rtkCoordinate(base, false)
                + String.format(Locale.US, "\nAltitude      %.2f m                         %.2f m", rtk.getMobileStationAltitude(), rtk.getBaseStationAltitude())
                + "\n\nGPS             " + rtkCount(rtk.getMobileStationReceiver1GPSInfo()) + "                              " + rtkCount(rtk.getBaseStationReceiverGPSInfo())
                + "\nBeiDou       " + rtkCount(rtk.getMobileStationReceiver1BeiDouInfo()) + "                              " + rtkCount(rtk.getBaseStationReceiverBeiDouInfo())
                + "\nGLONASS   " + rtkCount(rtk.getMobileStationReceiver1GLONASSInfo()) + "                              " + rtkCount(rtk.getBaseStationReceiverGLONASSInfo())
                + "\nGalileo       " + rtkCount(rtk.getMobileStationReceiver1GalileoInfo()) + "                              " + rtkCount(rtk.getBaseStationReceiverGalileoInfo()));
    }

    private static String rtkCoordinate(dji.common.model.LocationCoordinate2D coordinate, boolean latitude) {
        if (coordinate == null || !coordinate.isValid()) return "--";
        return String.format(Locale.US, "%.7f", latitude ? coordinate.getLatitude() : coordinate.getLongitude());
    }

    private static String rtkCount(dji.common.flightcontroller.ReceiverInfo info) {
        return info == null || !info.isConstellationSupported() ? "--" : String.valueOf(info.getSatelliteCount());
    }

    private View createSettingsPage() {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(22), dp(26), dp(22));
        content.setBackgroundColor(0xFFE8F3FA);
        TextView title = new TextView(getContext());
        title.setText("SETTINGS"); title.setTextSize(20f); title.setTextColor(0xFF162A36);
        content.addView(title);
        TextView label = new TextView(getContext());
        label.setText("LANDING ADVISORY THRESHOLD (%)"); label.setTextSize(12f); label.setTextColor(0xFF667B87);
        label.setPadding(0, dp(22), 0, dp(4)); content.addView(label);
        EditText threshold = new EditText(getContext());
        threshold.setSingleLine(true); threshold.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        threshold.setText(String.valueOf(lowBatteryPreferences.getThresholdPercent()));
        content.addView(threshold, new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView helper = new TextView(getContext());
        helper.setText("Valid range: 15–100%. Default: 30%.\nApp advisory only — DJI battery protection and flight behavior are unchanged.");
        helper.setTextSize(12f); helper.setTextColor(0xFF667B87); helper.setPadding(0, dp(8), 0, dp(14));
        content.addView(helper);
        Button save = new Button(getContext()); save.setText("SAVE LOCAL SETTING");
        save.setOnClickListener(view -> {
            try {
                int value = Integer.parseInt(threshold.getText().toString().trim());
                if (!lowBatteryPreferences.saveThresholdPercent(value)) {
                    helper.setText("Enter a whole number from 15 to 100. The saved setting was not changed.");
                    return;
                }
                helper.setText("Saved locally. App advisory only — DJI battery protection and flight behavior are unchanged.");
                renderStatusOnMain();
            } catch (NumberFormatException error) {
                helper.setText("Enter a whole number from 15 to 100. The saved setting was not changed.");
            }
        });
        content.addView(save, new LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView recordingTitle = new TextView(getContext());
        recordingTitle.setText("LOCAL RECORDING"); recordingTitle.setTextSize(14f); recordingTitle.setTextColor(0xFF162A36);
        recordingTitle.setPadding(0, dp(28), 0, dp(4)); content.addView(recordingTitle);
        TextView recordingFolder = new TextView(getContext()); recordingFolder.setTextSize(12f); recordingFolder.setTextColor(0xFF667B87);
        recordingFolder.setText("PRIMARY MP4 + telemetry sidecar: " + new RecordingFolderPreferences(getContext()).displayName());
        content.addView(recordingFolder);
        Button chooseRecordingFolder = new Button(getContext()); chooseRecordingFolder.setText("CHOOSE RECORDING FOLDER");
        chooseRecordingFolder.setOnClickListener(view -> {
            if (getContext() instanceof MainActivity) ((MainActivity) getContext()).selectRecordingFolder();
        });
        content.addView(chooseRecordingFolder, new LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView missionTitle = new TextView(getContext());
        missionTitle.setText("NEW MISSION DEFAULTS"); missionTitle.setTextSize(14f); missionTitle.setTextColor(0xFF162A36);
        missionTitle.setPadding(0, dp(28), 0, dp(4)); content.addView(missionTitle);
        TextView missionHint = new TextView(getContext());
        missionHint.setText("Applied only when you select NEW DRAFT. Existing missions are unchanged.");
        missionHint.setTextSize(12f); missionHint.setTextColor(0xFF667B87); content.addView(missionHint);
        MissionDefaults defaults = missionDefaultsPreferences.load();
        EditText defaultAltitude = new EditText(getContext()); defaultAltitude.setSingleLine(true);
        defaultAltitude.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        defaultAltitude.setHint("Altitude (m)"); defaultAltitude.setText(String.format(Locale.US, "%.1f", defaults.getAltitudeMeters()));
        content.addView(defaultAltitude, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText defaultSpeed = new EditText(getContext()); defaultSpeed.setSingleLine(true);
        defaultSpeed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        defaultSpeed.setHint("Speed (m/s)"); defaultSpeed.setText(String.format(Locale.US, "%.1f", defaults.getSpeedMetersPerSecond()));
        content.addView(defaultSpeed, new LinearLayout.LayoutParams(dp(180), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView headingLabel = new TextView(getContext()); headingLabel.setText("HEADING");
        headingLabel.setTextSize(12f); headingLabel.setTextColor(0xFF667B87); content.addView(headingLabel);
        Spinner defaultHeading = new Spinner(getContext());
        defaultHeading.setAdapter(new android.widget.ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"AUTO", "INITIAL DIRECTION", "REMOTE CONTROLLER"}));
        defaultHeading.setSelection(defaults.getHeadingMode().ordinal()); content.addView(defaultHeading);
        TextView finishLabel = new TextView(getContext()); finishLabel.setText("FINISH ACTION");
        finishLabel.setTextSize(12f); finishLabel.setTextColor(0xFF667B87); content.addView(finishLabel);
        Spinner defaultFinish = new Spinner(getContext());
        defaultFinish.setAdapter(new android.widget.ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Hover at final waypoint", "Return and land at DJI Home", "Land at final waypoint"}));
        defaultFinish.setSelection(defaults.getFinishAction().ordinal()); content.addView(defaultFinish);
        Button saveMissionDefaults = new Button(getContext()); saveMissionDefaults.setText("SAVE NEW MISSION DEFAULTS");
        saveMissionDefaults.setOnClickListener(view -> {
            try {
                MissionDefaults next = new MissionDefaults(Float.parseFloat(defaultAltitude.getText().toString().trim()),
                        Float.parseFloat(defaultSpeed.getText().toString().trim()),
                        MissionHeadingMode.values()[defaultHeading.getSelectedItemPosition()],
                        MissionFinishAction.values()[defaultFinish.getSelectedItemPosition()]);
                if (!missionDefaultsPreferences.save(next)) throw new IllegalStateException();
                missionHint.setText("Saved locally. Applied only when you select NEW DRAFT. Existing missions are unchanged.");
            } catch (RuntimeException error) {
                missionHint.setText("Enter altitude of 0 m or more and speed from 0.1 to 15 m/s. Existing defaults were unchanged.");
            }
        });
        content.addView(saveMissionDefaults, new LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView cameraTitle = new TextView(getContext());
        cameraTitle.setText("PAYLOAD RECORDING PROFILE"); cameraTitle.setTextSize(14f); cameraTitle.setTextColor(0xFF162A36);
        cameraTitle.setPadding(0, dp(28), 0, dp(4)); content.addView(cameraTitle);
        TextView cameraTarget = settingsDetailText(); content.addView(cameraTarget);
        TextView cameraCurrent = settingsDetailText(); content.addView(cameraCurrent);
        TextView cameraSaved = settingsDetailText(); content.addView(cameraSaved);
        TextView resolutionLabel = settingsFieldLabel("RESOLUTION"); content.addView(resolutionLabel);
        Spinner resolutionSelector = new Spinner(getContext()); resolutionSelector.setEnabled(false); content.addView(resolutionSelector);
        TextView frameRateLabel = settingsFieldLabel("FRAME RATE"); content.addView(frameRateLabel);
        Spinner frameRateSelector = new Spinner(getContext()); frameRateSelector.setEnabled(false); content.addView(frameRateSelector);
        TextView cameraSelection = settingsDetailText(); content.addView(cameraSelection);
        LinearLayout videoActions = new LinearLayout(getContext()); videoActions.setOrientation(LinearLayout.HORIZONTAL);
        Button saveVideoProfile = new Button(getContext()); saveVideoProfile.setText("SAVE PREFERENCE"); saveVideoProfile.setEnabled(false);
        videoActions.addView(saveVideoProfile, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button applyVideoProfile = new Button(getContext()); applyVideoProfile.setText("APPLY TO PAYLOAD"); applyVideoProfile.setEnabled(false);
        videoActions.addView(applyVideoProfile, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(videoActions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        configureVideoProfileControls(cameraTarget, cameraCurrent, cameraSaved, cameraSelection,
                resolutionSelector, frameRateSelector, saveVideoProfile, applyVideoProfile);
        TextView liveTitle = new TextView(getContext());
        liveTitle.setText("PRIMARY LIVE FEED"); liveTitle.setTextSize(14f); liveTitle.setTextColor(0xFF162A36);
        liveTitle.setPadding(0, dp(24), 0, dp(4)); content.addView(liveTitle);
        TextView liveFeed = settingsDetailText(); content.addView(liveFeed);
        Runnable refreshLiveFeed = new Runnable() {
            @Override public void run() {
                renderPrimaryLiveFeed(liveFeed);
                liveFeed.postDelayed(this, 1_000L);
            }
        };
        liveFeed.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { refreshLiveFeed.run(); }
            @Override public void onViewDetachedFromWindow(View view) { liveFeed.removeCallbacks(refreshLiveFeed); }
        });
        renderPrimaryLiveFeed(liveFeed);
        scroll.addView(content); return scroll;
    }

    private TextView settingsDetailText() {
        TextView value = new TextView(getContext());
        value.setTextSize(12f); value.setTextColor(0xFF667B87);
        value.setPadding(0, dp(2), 0, dp(2));
        return value;
    }

    private TextView settingsFieldLabel(String value) {
        TextView label = new TextView(getContext());
        label.setText(value); label.setTextSize(12f); label.setTextColor(0xFF667B87);
        label.setPadding(0, dp(12), 0, 0);
        return label;
    }

    private void configureVideoProfileControls(TextView targetStatus, TextView currentStatus, TextView savedStatus,
                                               TextView selectionStatus, Spinner resolutionSelector,
                                               Spinner frameRateSelector, Button save, Button apply) {
        PrimaryVideoProfile saved = primaryVideoProfilePreferences.load();
        savedStatus.setText("SAVED PREFERENCE — " + PrimaryVideoProfileLabels.profile(saved));
        String route = DjiDataHub.getInstance().getSource(DjiDataHub.Feed.PRIMARY);
        targetStatus.setText("RECORDING TARGET — " + PrimaryCameraBinding.label() + " · CAMERA "
                + PrimaryCameraBinding.componentIndex() + "\nPRIMARY ROUTE — " + route);
        Aircraft aircraft = DJISampleApplication.getAircraftInstance();
        if (aircraft == null || !aircraft.isConnected()) {
            currentStatus.setText("DJI CURRENT — UNAVAILABLE (connect the left Zenmuse payload)");
            selectionStatus.setText("SELECTED — UNAVAILABLE until supported payload profiles are available.");
            return;
        }
        Camera camera = aircraft.getCameraWithComponentIndex(PrimaryCameraBinding.componentIndex());
        if (camera == null) {
            currentStatus.setText("DJI CURRENT — UNAVAILABLE (" + PrimaryCameraBinding.label() + " not connected)");
            selectionStatus.setText("SELECTED — UNAVAILABLE until the configured Primary payload is available.");
            return;
        }
        String displayName = camera.getDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) {
            targetStatus.setText("RECORDING TARGET — " + PrimaryCameraBinding.label() + " · CAMERA "
                    + PrimaryCameraBinding.componentIndex() + " · " + displayName
                    + "\nPRIMARY ROUTE — " + route);
        }
        if (camera.getCapabilities() == null || camera.getCapabilities().videoResolutionAndFrameRateRange() == null) {
            currentStatus.setText("DJI CURRENT — UNAVAILABLE (camera capabilities not ready)"); return;
        }
        ResolutionAndFrameRate[] profiles = camera.getCapabilities().videoResolutionAndFrameRateRange();
        if (profiles.length == 0) { currentStatus.setText("DJI CURRENT — UNAVAILABLE (no supported profiles)"); return; }
        PrimaryVideoProfileSelection choices = primaryVideoProfileSelection(profiles);
        List<String> resolutionIds = choices.resolutions();
        if (resolutionIds.isEmpty()) { currentStatus.setText("DJI CURRENT — UNAVAILABLE (no valid supported profiles)"); return; }
        String[] resolutionLabels = new String[resolutionIds.size()];
        for (int index = 0; index < resolutionIds.size(); index++) resolutionLabels[index] = choices.labelForResolution(resolutionIds.get(index));
        resolutionSelector.setAdapter(new android.widget.ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, resolutionLabels));
        final boolean[] binding = {true};
        final boolean[] userSelected = {false};
        final boolean[] requirePayloadSelection = {saved != null && !PrimaryCameraBinding.matches(saved)};
        final PrimaryVideoProfile[] selected = {!requirePayloadSelection[0] && saved != null && choices.resolve(saved) != null
                ? saved : choices.frameRatesFor(resolutionIds.get(0)).get(0).profile};
        final Runnable updateSelectionText = () -> {
            PrimaryVideoProfileSelection.Choice choice = choices.resolve(selected[0]);
            selectionStatus.setText(choice == null ? "SELECTED — UNAVAILABLE"
                    : "SELECTED — " + choice.resolutionLabel + " · " + choice.frameRateLabel
                    + "\nPress APPLY TO PAYLOAD to change the camera.");
        };
        final Runnable populateFrameRates = () -> {
            String resolutionId = resolutionIds.get(resolutionSelector.getSelectedItemPosition());
            List<PrimaryVideoProfileSelection.Choice> frameRates = choices.frameRatesFor(resolutionId);
            String[] labels = new String[frameRates.size()];
            int chosenIndex = 0;
            for (int index = 0; index < frameRates.size(); index++) {
                labels[index] = frameRates.get(index).frameRateLabel;
                if (frameRates.get(index).profile.sameSelection(selected[0])) chosenIndex = index;
            }
            binding[0] = true;
            frameRateSelector.setAdapter(new android.widget.ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, labels));
            frameRateSelector.setSelection(chosenIndex);
            selected[0] = frameRates.get(chosenIndex).profile;
            binding[0] = false;
            updateSelectionText.run();
        };
        int initialResolution = resolutionIds.indexOf(selected[0].getResolutionId());
        resolutionSelector.setSelection(initialResolution < 0 ? 0 : initialResolution);
        populateFrameRates.run();
        resolutionSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding[0]) return;
                userSelected[0] = true; requirePayloadSelection[0] = false; apply.setEnabled(true);
                populateFrameRates.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        frameRateSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding[0]) return;
                String resolutionId = resolutionIds.get(resolutionSelector.getSelectedItemPosition());
                List<PrimaryVideoProfileSelection.Choice> rates = choices.frameRatesFor(resolutionId);
                if (position >= 0 && position < rates.size()) {
                    userSelected[0] = true; requirePayloadSelection[0] = false; apply.setEnabled(true);
                    selected[0] = rates.get(position).profile; updateSelectionText.run();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        resolutionSelector.setEnabled(true); frameRateSelector.setEnabled(true); save.setEnabled(true);
        apply.setEnabled(!requirePayloadSelection[0]);
        PrimaryVideoProfileSelection.Choice savedChoice = choices.resolve(saved);
        savedStatus.setText(saved == null ? "SAVED PREFERENCE — NONE"
                : (!PrimaryCameraBinding.matches(saved) ? "SAVED PREFERENCE — select again for " + PrimaryCameraBinding.label()
                : (savedChoice == null ? "SAVED PREFERENCE — unavailable for this payload"
                : "SAVED PREFERENCE — " + savedChoice.resolutionLabel + " · " + savedChoice.frameRateLabel)));
        currentStatus.setText("DJI CURRENT — READING " + PrimaryCameraBinding.label() + "…");
        camera.getVideoResolutionAndFrameRate(new CommonCallbacks.CompletionCallbackWith<ResolutionAndFrameRate>() {
            @Override public void onSuccess(ResolutionAndFrameRate current) { post(() -> {
                PrimaryVideoProfile reported = profileOf(current);
                PrimaryVideoProfileSelection.Choice currentChoice = choices.resolve(reported);
                currentStatus.setText(currentChoice == null ? "DJI CURRENT — " + current
                        : "DJI CURRENT — " + currentChoice.resolutionLabel + " · " + currentChoice.frameRateLabel);
                if (!userSelected[0] && saved == null && currentChoice != null) {
                    binding[0] = true;
                    selected[0] = reported;
                    resolutionSelector.setSelection(resolutionIds.indexOf(reported.getResolutionId()));
                    binding[0] = false;
                    populateFrameRates.run();
                }
            }); }
            @Override public void onFailure(DJIError error) { post(() -> currentStatus.setText("DJI CURRENT — UNAVAILABLE: " + error.getDescription())); }
        });
        save.setOnClickListener(view -> {
            PrimaryVideoProfile scopedSelection = selected[0].forTarget(PrimaryCameraBinding.targetId());
            if (primaryVideoProfilePreferences.save(scopedSelection)) {
                PrimaryVideoProfileSelection.Choice choice = choices.resolve(selected[0]);
                savedStatus.setText("SAVED PREFERENCE — " + choice.resolutionLabel + " · " + choice.frameRateLabel);
            } else savedStatus.setText("SAVED PREFERENCE — FAILED; previous preference was kept.");
        });
        apply.setOnClickListener(view -> {
            ResolutionAndFrameRate requested = djiProfileFor(profiles, selected[0]);
            if (requested == null) {
                currentStatus.setText("DJI CURRENT — cannot apply an unsupported profile.");
                return;
            }
            apply.setEnabled(false); currentStatus.setText("DJI CURRENT — APPLYING…");
            camera.setVideoResolutionAndFrameRate(requested, error -> post(() -> {
                apply.setEnabled(true);
                if (error != null) { currentStatus.setText("DJI CURRENT — APPLY FAILED: " + error.getDescription()); return; }
                camera.getVideoResolutionAndFrameRate(new CommonCallbacks.CompletionCallbackWith<ResolutionAndFrameRate>() {
                    @Override public void onSuccess(ResolutionAndFrameRate current) { post(() -> {
                        PrimaryVideoProfileSelection.Choice reported = choices.resolve(profileOf(current));
                        currentStatus.setText(requested.equals(current)
                                ? "DJI CURRENT — " + (reported == null ? current : reported.resolutionLabel + " · " + reported.frameRateLabel) + " (APPLIED)"
                                : "DJI CURRENT — NOT VERIFIED; DJI REPORTS " + current);
                        if (requested.equals(current)) selectionStatus.setText("SELECTED — "
                                + (reported == null ? PrimaryVideoProfileLabels.profile(profileOf(current))
                                : reported.resolutionLabel + " · " + reported.frameRateLabel)
                                + "\nApplied and verified by the payload.");
                    }); }
                    @Override public void onFailure(DJIError readError) { post(() -> currentStatus.setText("DJI CURRENT — NOT VERIFIED: " + readError.getDescription())); }
                });
            }));
        });
    }

    private static PrimaryVideoProfileSelection primaryVideoProfileSelection(ResolutionAndFrameRate[] profiles) {
        List<PrimaryVideoProfileSelection.Choice> choices = new ArrayList<>();
        for (ResolutionAndFrameRate profile : profiles) {
            if (profile == null || profile.getResolution() == null || profile.getFrameRate() == null) continue;
            choices.add(new PrimaryVideoProfileSelection.Choice(profileOf(profile), resolutionDisplay(profile), frameRateDisplay(profile)));
        }
        return new PrimaryVideoProfileSelection(choices);
    }

    private static PrimaryVideoProfile profileOf(ResolutionAndFrameRate profile) {
        return new PrimaryVideoProfile(profile.getResolution().name(), profile.getFrameRate().name());
    }

    private static ResolutionAndFrameRate djiProfileFor(ResolutionAndFrameRate[] candidates, PrimaryVideoProfile profile) {
        if (profile == null) return null;
        for (ResolutionAndFrameRate candidate : candidates) if (candidate != null && profile.sameSelection(profileOf(candidate))) return candidate;
        return null;
    }

    /** Renders observed transport/decoder facts only; it never reads a camera profile as a fallback. */
    private void renderPrimaryLiveFeed(@NonNull TextView target) {
        DJICodecManager decoder = codecManager;
        Integer width = decoder == null ? null : decoder.getVideoWidth();
        Integer height = decoder == null ? null : decoder.getVideoHeight();
        String dimensions = width != null && height != null && width > 0 && height > 0
                ? width + " × " + height : "UNOBSERVED";
        TransportRuntime runtime = TransportSessionManager.getInstance().getRuntime();
        if (runtime == null) {
            target.setText("DECODED DIMENSIONS — " + dimensions
                    + "\nOBSERVED FPS — UNOBSERVED (transport inactive)"
                    + "\nPRIMARY RTP EGRESS — UNOBSERVED (transport inactive)");
            return;
        }
        com.dji.sdk.sample.transport.video.PrimaryLiveFeedMetrics.Snapshot cadence =
                runtime.getPrimaryLiveFeedSnapshot();
        TransportEgressMetrics.Snapshot egress = runtime.getEgressSnapshot();
        String fps = cadence.observed ? String.format(Locale.US, "%.2f fps observed", cadence.framesPerSecond)
                : "UNOBSERVED";
        String bitrate = egress.primaryRtpRate.available
                ? String.format(Locale.US, "%.2f Mb/s", egress.primaryRtpRate.bitsPerSecond / 1_000_000.0d)
                : "UNOBSERVED";
        target.setText("DECODED DIMENSIONS — " + dimensions + "\nOBSERVED FPS — " + fps
                + "\nPRIMARY RTP EGRESS — " + bitrate + " · Android UDP socket accepted");
    }

    private static String resolutionDisplay(ResolutionAndFrameRate profile) {
        return PrimaryVideoProfileLabels.resolution(profile.getResolution().name());
    }

    private static String frameRateDisplay(ResolutionAndFrameRate profile) {
        return PrimaryVideoProfileLabels.frameRate(profile.getFrameRate().name());
    }

    private static void toggle(View panel) {
        panel.setVisibility(panel.getVisibility() == VISIBLE ? GONE : VISIBLE);
    }

    private void toggleDetail(@NonNull DetailGroup group) {
        if (detailPanel.getVisibility() == VISIBLE && expandedDetail == group) {
            hideDetailPanel();
            return;
        }
        showDetail(group, true);
    }

    /**
     * Shows a status group. Only a top-rail chevron moves the sheet; tabs inside the sheet
     * preserve its current location so Safety cannot accidentally fall back to Power's anchor.
     */
    private void showDetail(@NonNull DetailGroup group, boolean anchorToTopRail) {
        if (anchorToTopRail) detailState.openFromTopRail(detailStateGroup(group));
        else detailState.selectTab(detailStateGroup(group));
        expandedDetail = group;
        renderExpandedDetail();
        if (anchorToTopRail) positionDetailPanel(group);
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
        recordingPanel.setVisibility(GONE);
        hideDetailPanel();
        if (opening) panel.setVisibility(VISIBLE);
    }

    private void toggleRecording() {
        LocalPrimaryRecordingManager manager = LocalPrimaryRecordingManager.getInstance();
        LocalPrimaryRecordingManager.Snapshot snapshot = manager.snapshot(getContext());
        if (snapshot.state == RecordingState.ARMING || snapshot.state == RecordingState.RECORDING) {
            manager.stop();
            return;
        }
        if (manager.start(getContext())) TransportForegroundService.startForRecording(getContext());
    }

    private void renderRecordingSnapshot(@NonNull LocalPrimaryRecordingManager.Snapshot snapshot) {
        if (!isAttachedToWindow()) return;
        String stateText = snapshot.state == RecordingState.ARMING ? "ARMING RECORDING" : snapshot.state.name();
        recordingState.setText(stateText);
        recordingDetail.setText(snapshot.reason + " · " + snapshot.destination);
        if (snapshot.state == RecordingState.RECORDING) {
            long totalSeconds = snapshot.elapsedMillis() / 1000L;
            recordingElapsed.setText(String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L));
            recordingAction.setText("STOP");
        } else {
            recordingElapsed.setText("");
            recordingAction.setText(snapshot.state == RecordingState.ARMING ? "STOP" : "RECORD");
        }
        Button header = findViewById(R.id.operate_mvp_recording_button);
        header.setText(snapshot.state == RecordingState.RECORDING ? "REC  ▾" : "RECORD  ▾");
    }

    private void hideDetailPanel() {
        detailPanel.setVisibility(GONE);
        expandedDetail = null;
        detailState.close();
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
            if (recordingPanel.getVisibility() == VISIBLE && !isTouchInside(event, recordingPanel)
                    && !isTouchInside(event, findViewById(R.id.operate_mvp_recording_button))) {
                recordingPanel.setVisibility(GONE);
            }
            // Status panels contain operator controls (notably the Safety acknowledgements).
            // Do not auto-close on an ambiguous touch: landscape/SurfaceView coordinate changes
            // could otherwise hide the panel before its row receives the click. The same top-rail
            // chevron that opened it remains the explicit close control.
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
        recordingSubscription = LocalPrimaryRecordingManager.getInstance().addListener(
                snapshot -> mainHandler.post(() -> renderRecordingSnapshot(snapshot)));
        renderRecordingSnapshot(LocalPrimaryRecordingManager.getInstance().snapshot(getContext()));
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
        if (recordingSubscription != null) recordingSubscription.close();
        recordingSubscription = null;
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
                if (feed == DjiDataHub.Feed.SECONDARY) {
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
        primaryVideoSubscription = hub.addPrimaryVideoListener(new DjiDataHub.PrimaryVideoListener() {
            @Override public void onVideo(byte[] buffer, int size, @NonNull String source) {
                DJICodecManager decoder = codecManager;
                if (decoder != null) decoder.sendDataToDecoder(buffer, size,
                        UsbAccessoryService.VideoStreamSource.Camera.getIndex());
                lastVideoFrameElapsedMs = SystemClock.elapsedRealtime();
                postPrimarySignal();
            }

            @Override public void onDiscontinuity() {
                DJICodecManager decoder = codecManager;
                if (decoder != null) decoder.resetKeyFrame();
            }
        });
        setSelectedSource(hub.getSource(DjiDataHub.Feed.PRIMARY));
        fpvSource.setText(hub.getSource(DjiDataHub.Feed.SECONDARY));
        secondaryFeedAvailable = hub.isAvailable(DjiDataHub.Feed.SECONDARY);
    }

    private void detachHub() {
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
        if (primaryVideoSubscription != null) primaryVideoSubscription.close();
        primaryVideoSubscription = null;
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
        LowBatteryAdvisory.Snapshot advisory = LowBatteryAdvisory.evaluate(
                batteryOne == null ? -1 : batteryOne.getChargeRemainingInPercent(),
                batterySeverity(0, batteryOne, now) == OperateStatusModel.Severity.HEALTHY,
                batteryTwo == null ? -1 : batteryTwo.getChargeRemainingInPercent(),
                batterySeverity(1, batteryTwo, now) == OperateStatusModel.Severity.HEALTHY,
                lowBatteryPreferences.getThresholdPercent());
        renderLandingAdvisory(advisory);

        boolean aircraftConnected = "AIRCRAFT OK".equals(aircraftStatus.getText().toString());
        boolean connectionAttentionNeeded = aircraftConnected
                && (!"CENDENCE OK".equals(controllerConnection)
                || linkSeverity != OperateStatusModel.Severity.HEALTHY);
        DjiHomeState djiHome = pendingStatusSnapshot == null ? null : pendingStatusSnapshot.djiHome;
        OperateStatusModel.Severity homeSeverity = djiHome == null
                ? OperateStatusModel.Severity.UNAVAILABLE
                : djiHome.getAvailability() == DjiHomeState.Availability.WAITING_FOR_SAMPLE
                ? OperateStatusModel.Severity.UNAVAILABLE
                : OperateStatusModel.freshness(djiHome.getObservedElapsedMs(), now,
                        djiHome.isAvailable());
        boolean positioningAttentionNeeded = aircraftConnected
                && (rtkSeverity != OperateStatusModel.Severity.HEALTHY
                || gpsSeverity != OperateStatusModel.Severity.HEALTHY
                || homeSeverity != OperateStatusModel.Severity.HEALTHY);
        boolean powerAttentionNeeded = aircraftConnected
                && (OperateStatusModel.requiresAttention(rcSeverity)
                || hasBatteryAttention(0, batteryOne, now) || hasBatteryAttention(1, batteryTwo, now));
        setAttention(connectionAttention, connectionAttentionNeeded, false);
        setAttention(positioningAttention, positioningAttentionNeeded, false);
        setAttention(powerAttention, powerAttentionNeeded,
                rcSeverity == OperateStatusModel.Severity.CRITICAL
                        || batterySeverity(0, batteryOne, now) == OperateStatusModel.Severity.CRITICAL
                        || batterySeverity(1, batteryTwo, now) == OperateStatusModel.Severity.CRITICAL);
        powerDetail = String.format(Locale.US,
                "Landing advisory (app only)\n%s\nThreshold %d%% · DJI protection unchanged\n\nCendence battery\n%s\n%s\n\nDrone battery 1 (DJI pack 0)\n%s\n\nDrone battery 2 (DJI pack 1)\n%s",
                advisory.render(), lowBatteryPreferences.getThresholdPercent(),
                rcBatteryStatus.getText(), rcBattery == null ? "NO LIVE SAMPLE" : String.format(Locale.US,
                        "%d mAh · %s", rcBattery.getRemainingChargeInmAh(),
                        rcBattery.isCharging() ? "CHARGING" : "NOT CHARGING"),
                batteryDetail(batteryOne, 0, now), batteryDetail(batteryTwo, 1, now));
        renderExpandedDetail();
    }

    private void renderLandingAdvisory(@NonNull LowBatteryAdvisory.Snapshot advisory) {
        LowBatteryAdvisory.Severity severity = advisory.getSeverity();
        landingAdvisory.setVisibility(severity == LowBatteryAdvisory.Severity.CLEAR ? GONE : VISIBLE);
        landingAdvisory.setText(severity == LowBatteryAdvisory.Severity.LAND_NOW ? "LAND NOW\nB"
                + (advisory.getLimitingBatteryIndex() + 1) + " " + advisory.getLimitingPercent() + "%"
                : severity == LowBatteryAdvisory.Severity.LAND_SOON ? "LAND SOON\nB"
                + (advisory.getLimitingBatteryIndex() + 1) + " " + advisory.getLimitingPercent() + "%"
                : "BATTERY\nUNAVAILABLE");
        landingAdvisory.setTextColor(severity == LowBatteryAdvisory.Severity.LAND_NOW ? 0xFFFF3145
                : severity == LowBatteryAdvisory.Severity.LAND_SOON ? 0xFFE29A22 : 0xFF667B87);
        if (severity != lastLandingAdvisorySeverity) {
            SessionTimelineStore.android("BATTERY_LANDING_ADVISORY", advisory.render());
            lastLandingAdvisorySeverity = severity;
        }
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
        String summary = group == FlightReadinessModel.Group.SAFETY_CONFIGURATION
                ? safetySummary(decision)
                : groupSummary(group, decision);
        StringBuilder body = new StringBuilder(summary);
        for (FlightReadinessModel.Row row : decision.getRows()) {
            if (row.getGroup() != group) continue;
            body.append("\n\n").append(row.getLabel()).append("\n")
                    .append(row.getValue()).append(" · ").append(row.getState().name());
            if (row.getReason() != null) body.append("\n").append(row.getReason().getMessage());
        }
        if (group == FlightReadinessModel.Group.AIRCRAFT_LINK) {
            body.append("\n\nOcuSync downlink\n").append(ocuSyncDownlinkDetail());
        }
        if (group == FlightReadinessModel.Group.NAVIGATION_HOME) {
            appendNavigationEvidence(body);
        }
        if (group == FlightReadinessModel.Group.POWER) {
            body.append("\n\n").append(powerDetail);
        }
        if (group == FlightReadinessModel.Group.SAFETY_CONFIGURATION) {
            FlightControllerState flight = pendingFlightState;
            body.append("\n\nMotors\n").append(flight == null ? "WAITING FOR FLIGHT STATE"
                    : flight.areMotorsOn() ? "ON" : "OFF");
            body.append("\n\nAircraft\n").append(flight == null ? "WAITING FOR FLIGHT STATE"
                    : flight.isFlying() ? "FLYING" : "ON GROUND");
            if (flight != null) {
                body.append("\n\nFlight mode\n").append(String.valueOf(flight.getFlightMode()));
                body.append("\n\nReturn to home\n").append(flight.isGoingHome()
                        ? "ACTIVE" : "NOT ACTIVE");
                body.append("\n\nLanding confirmation\n").append(flight.isLandingConfirmationNeeded()
                        ? "REQUIRED — REVIEW LANDING AREA" : "NOT REQUIRED");
            }
            body.append("\n\n").append(FlightReadinessStore.getInstance()
                    .getSafetyConfiguration().getSummary());
        }
        detailTitle.setText(title);
        detailBody.setText(body.toString());
        renderDetailTabSelection();
        renderChecklist(group);
    }

    @NonNull private String ocuSyncDownlinkDetail() {
        OperateStatusModel.Severity freshness = OperateStatusModel.freshness(
                lastOcuSyncElapsedMs, SystemClock.elapsedRealtime(), ocuSyncSupported);
        if (!ocuSyncSupported) return "UNAVAILABLE";
        if (pendingOcuSyncQuality < 0) return "WAITING FOR QUALITY SAMPLE";
        if (freshness == OperateStatusModel.Severity.STALE) return "STALE";
        if (freshness != OperateStatusModel.Severity.HEALTHY) return "UNAVAILABLE";
        return String.format(Locale.US, "%d%%", pendingOcuSyncQuality);
    }

    /** Adds current positioning facts only while the hub-backed flight sample remains fresh. */
    private void appendNavigationEvidence(@NonNull StringBuilder body) {
        FlightControllerState flight = pendingFlightState;
        OperateStatusModel.Severity freshness = OperateStatusModel.freshness(
                lastFlightStateElapsedMs, SystemClock.elapsedRealtime(), flight != null);
        if (freshness != OperateStatusModel.Severity.HEALTHY || flight == null) {
            body.append("\n\nCurrent position\n").append(freshness == OperateStatusModel.Severity.STALE
                    ? "STALE" : "WAITING FOR FLIGHT STATE");
            body.append("\n\nHeading\n").append(freshness == OperateStatusModel.Severity.STALE
                    ? "STALE" : "WAITING FOR FLIGHT STATE");
            body.append("\n\nFlight mode\n").append(freshness == OperateStatusModel.Severity.STALE
                    ? "STALE" : "WAITING FOR FLIGHT STATE");
            return;
        }
        LocationCoordinate3D location = flight.getAircraftLocation();
        body.append("\n\nCurrent position\n");
        if (location == null || !finite(location.getLatitude()) || !finite(location.getLongitude())
                || !finite(location.getAltitude())) {
            body.append("UNAVAILABLE");
        } else {
            body.append(String.format(Locale.US, "%.6f, %.6f · %.1f m", location.getLatitude(),
                    location.getLongitude(), location.getAltitude()));
        }
        body.append("\n\nHeading\n").append(String.format(Locale.US, "%d°",
                flight.getAircraftHeadDirection()));
        body.append("\n\nFlight mode\n").append(String.valueOf(flight.getFlightMode()));
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** Makes the selected detail group visible without introducing a fourth top-rail chevron. */
    private void renderDetailTabSelection() {
        styleDetailTab(R.id.operate_mvp_readiness_connection, DetailGroup.CONNECTION);
        styleDetailTab(R.id.operate_mvp_readiness_navigation, DetailGroup.POSITIONING);
        styleDetailTab(R.id.operate_mvp_readiness_power, DetailGroup.POWER);
        styleDetailTab(R.id.operate_mvp_readiness_safety, DetailGroup.SAFETY);
    }

    private void styleDetailTab(int id, @NonNull DetailGroup group) {
        Button button = findViewById(id);
        boolean selected = group == expandedDetail;
        button.setTextColor(selected ? 0xFF36B95C : 0xFF162A36);
        button.setAlpha(selected ? 1f : 0.68f);
    }

    @NonNull private static OperateStatusDetailState.Group detailStateGroup(@NonNull DetailGroup group) {
        if (group == DetailGroup.CONNECTION) return OperateStatusDetailState.Group.LINK;
        if (group == DetailGroup.POSITIONING) return OperateStatusDetailState.Group.NAV;
        if (group == DetailGroup.POWER) return OperateStatusDetailState.Group.POWER;
        return OperateStatusDetailState.Group.SAFETY;
    }

    @NonNull private static String groupSummary(@NonNull FlightReadinessModel.Group group,
            @NonNull FlightReadinessModel.Decision decision) {
        for (FlightReadinessModel.Row row : decision.getRows()) {
            if (row.getGroup() == group && row.getState() != FlightReadinessModel.RowState.HEALTHY) {
                return row.getLabel() + " — " + row.getValue();
            }
        }
        return "LIVE";
    }

    @NonNull private static String safetySummary(@NonNull FlightReadinessModel.Decision decision) {
        if (decision.getState() == FlightReadinessModel.State.MISSION_READY) {
            return "APP CHECKS READY FOR MANUAL TAKEOFF\nUse Cendence/DJI warnings and physical inspection as final authority.";
        }
        if (decision.getState() == FlightReadinessModel.State.REVIEW_REQUIRED) return "REVIEW REQUIRED";
        if (decision.getState() == FlightReadinessModel.State.BLOCKED) return "NOT READY — " + decision.getExplanation();
        return "ATTENTION — " + decision.getExplanation();
    }

    private void renderChecklist(@NonNull FlightReadinessModel.Group group) {
        if (group != FlightReadinessModel.Group.SAFETY_CONFIGURATION) {
            detailChecklist.setVisibility(GONE);
            return;
        }
        detailChecklist.setVisibility(VISIBLE);
        FlightReadinessStore store = FlightReadinessStore.getInstance();
        java.util.Set<FlightReadinessSession.ChecklistItem> acknowledged =
                store.getSession().getAcknowledgedItems();
        for (FlightReadinessSession.ChecklistItem item : FlightReadinessSession.ChecklistItem.values()) {
            Button button = safetyChecklistButtons.get(item);
            if (button == null) {
                button = new Button(getContext());
                button.setTextSize(12f);
                button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                button.setAllCaps(false);
                button.setPadding(dp(14), 0, dp(14), 0);
                button.setOnClickListener(view -> {
                    store.toggleAcknowledgement(item);
                    // Keep this same row alive: live status updates may arrive during the tap.
                    renderChecklist(FlightReadinessModel.Group.SAFETY_CONFIGURATION);
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, dp(52));
                params.bottomMargin = dp(6);
                detailChecklist.addView(button, params);
                safetyChecklistButtons.put(item, button);
            }
            boolean checked = acknowledged.contains(item);
            button.setText((checked ? "✓  CONFIRMED — " : "○  ") + item.getLabel());
            button.setTextColor(checked ? 0xFF187A38 : 0xFF162A36);
            button.setBackgroundResource(checked ? R.drawable.operate_mvp_checklist_confirmed
                    : R.drawable.operate_mvp_button);
            button.setContentDescription((checked ? "Confirmed. Tap to undo: " : "Tap to confirm: ")
                    + item.getLabel());
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
