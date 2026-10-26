package com.dji.sdk.sample.operate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.djihub.DjiHomeState;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.internal.controller.MainActivity;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.missionplanner.MissionFolderPreferences;
import com.squareup.otto.Subscribe;
import com.dji.sdk.sample.missionplanner.MissionDraft;
import com.dji.sdk.sample.missionplanner.MissionFinishAction;
import com.dji.sdk.sample.missionplanner.MissionHeadingMode;
import com.dji.sdk.sample.missionplanner.MissionDefaultsPreferences;
import com.dji.sdk.sample.missionplanner.MissionDraftLibrary;
import com.dji.sdk.sample.missionplanner.MissionDraftValidator;
import com.dji.sdk.sample.missionplanner.MissionRouteEditor;
import com.dji.sdk.sample.missionplanner.MissionMapInteractionState;
import com.dji.sdk.sample.missionplanner.MissionPreflight;
import com.dji.sdk.sample.missionplanner.MissionSyncState;
import com.dji.sdk.sample.missionplanner.MissionSessionEvidence;
import com.dji.sdk.sample.missionplanner.RouteStatistics;
import com.dji.sdk.sample.missionplanner.BoundaryEvaluator;
import com.dji.sdk.sample.missionplanner.PlanningBoundaryProfile;
import com.dji.sdk.sample.missionplanner.PlanningBoundaryProfileLibrary;
import com.dji.sdk.sample.missionplanner.PlanningBoundary;
import com.dji.sdk.sample.missionplanner.MissionWaypoint;
import com.dji.sdk.sample.missionplanner.WaypointMissionFactory;
import com.dji.sdk.sample.missionplanner.map.MapLibreMissionMapView;

import java.util.Locale;

import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.model.LocationCoordinate2D;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionExecutionEvent;
import dji.common.mission.waypoint.WaypointMissionState;
import dji.common.util.CommonCallbacks;
import dji.sdk.mission.MissionControl;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;

/** Explicit local mission draft controls. Upload and flight commands require separate taps. */
public final class MissionMvpView extends LinearLayout implements PresentableView {
    private final EditText altitude;
    private final EditText speed;
    private final EditText name;
    private final Spinner finish;
    private final Spinner headingMode;
    private final TextView route;
    private final TextView sync;
    private final TextView stats;
    private final TextView boundaryWarning;
    private final TextView home;
    private final TextView status;
    private final Button addWaypoints;
    private final Button addDjiHome;
    private final WaypointMissionOperator operator;
    private final MissionDraftLibrary library;
    private final MissionDefaultsPreferences defaults;
    private final MissionFolderPreferences missionFolder;
    private final PlanningBoundaryProfileLibrary boundaryProfiles;
    private final MissionRouteEditor routeEditor;
    private final MapLibreMissionMapView map;
    private WaypointMission loadedMission;
    private MissionDraft loadedDraft;
    private final MissionSessionEvidence missionEvidence = new MissionSessionEvidence();
    private boolean draftDirty;
    private boolean bindingDraft;
    private int lastFinishSelection;
    private int lastHeadingSelection;
    private PlanningBoundaryProfile activeBoundaryProfile;
    private PlanningBoundaryProfile boundaryCaptureProfile;
    private final java.util.List<PlanningBoundary.Point> boundaryCapturePoints = new java.util.ArrayList<>();
    private final MissionMapInteractionState mapInteraction = new MissionMapInteractionState();
    private int selectedWaypoint = -1;
    private CallbackMulticaster.Subscription hubSubscription;
    private CallbackMulticaster.Subscription readinessSubscription;
    private boolean rthActive;
    private boolean flightStateAvailable;
    private boolean homeAvailable;
    private boolean missionListenerAttached;
    private Double latestAircraftLatitude;
    private Double latestAircraftLongitude;
    private long latestAircraftLocationElapsedMs = -1L;
    private final WaypointMissionOperatorListener missionListener = new WaypointMissionOperatorListener() {
        @Override public void onDownloadUpdate(@NonNull dji.common.mission.waypoint.WaypointMissionDownloadEvent event) {
            post(() -> {
                SessionTimelineStore.dji("MISSION_DOWNLOAD", event.getError() == null ? "Download update" : event.getError().getDescription());
                if (event.getError() != null) {
                    status.setText("Download failed: " + event.getError().getDescription());
                } else if (event.getProgress() != null) {
                    status.setText(event.getProgress().isSummaryDownloaded
                            ? "Downloading mission waypoint " + (event.getProgress().downloadedWaypointIndex + 1)
                            + " of " + event.getProgress().totalWaypointCount + "."
                            : "Downloading mission summary.");
                }
                renderMissionSync();
            });
        }

        @Override public void onUploadUpdate(@NonNull dji.common.mission.waypoint.WaypointMissionUploadEvent event) {
            post(() -> {
                SessionTimelineStore.dji("MISSION_UPLOAD", "Upload update");
                if (event.getProgress() != null) {
                    status.setText("Uploading waypoint " + (event.getProgress().uploadedWaypointIndex + 1) + ".");
                }
            });
        }

        @Override public void onExecutionUpdate(@NonNull WaypointMissionExecutionEvent event) {
            post(() -> {
                String state = event.getCurrentState() == null ? "Unknown" : event.getCurrentState().getName();
                SessionTimelineStore.dji("MISSION_EXECUTION", state);
                status.setText(event.getProgress() == null ? "Mission — " + state
                        : "Mission — " + state + ", waypoint "
                        + (event.getProgress().targetWaypointIndex + 1) + ".");
                renderMissionSync();
            });
        }

        @Override public void onExecutionStart() { post(() -> {
            SessionTimelineStore.dji("MISSION_START_RESULT", "Mission execution started");
            status.setText("Mission execution started.");
        }); }

        @Override public void onExecutionFinish(dji.common.error.DJIError error) {
            post(() -> {
                SessionTimelineStore.dji("MISSION_FINISH", error == null ? "Mission finished" : error.getDescription());
                status.setText(error == null ? "Mission finished." : "Mission ended: " + error.getDescription());
            });
        }
    };

    public MissionMvpView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_mission_mvp, this, true);
        altitude = findViewById(R.id.mission_mvp_altitude);
        speed = findViewById(R.id.mission_mvp_speed);
        name = findViewById(R.id.mission_mvp_name);
        finish = findViewById(R.id.mission_mvp_finish);
        headingMode = findViewById(R.id.mission_mvp_heading);
        route = findViewById(R.id.mission_mvp_route);
        sync = findViewById(R.id.mission_mvp_sync);
        stats = findViewById(R.id.mission_mvp_stats);
        boundaryWarning = findViewById(R.id.mission_mvp_boundary_warning);
        home = findViewById(R.id.mission_mvp_home);
        status = findViewById(R.id.mission_mvp_status);
        addWaypoints = findViewById(R.id.mission_mvp_add);
        addDjiHome = findViewById(R.id.mission_mvp_add_dji_home);
        library = new MissionDraftLibrary(context);
        defaults = new MissionDefaultsPreferences(context);
        missionFolder = new MissionFolderPreferences(context);
        boundaryProfiles = new PlanningBoundaryProfileLibrary(context);
        routeEditor = new MissionRouteEditor(loadInitialDraft());
        name.setText(routeEditor.getDraft().getName());
        altitude.setText(String.format(Locale.US, "%.1f", routeEditor.getDraft().getDefaultAltitudeMeters()));
        speed.setText(String.format(Locale.US, "%.1f", routeEditor.getDraft().getBaseSpeedMetersPerSecond()));
        ArrayAdapter<String> finishAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Hover at final waypoint", "Return and land at DJI Home", "Land at final waypoint"});
        finish.setAdapter(finishAdapter);
        finish.setSelection(routeEditor.getDraft().getFinishAction().ordinal());
        ArrayAdapter<String> headingAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item, new String[]{
                "AUTO", "INITIAL DIRECTION", "REMOTE CONTROLLER"});
        headingMode.setAdapter(headingAdapter);
        headingMode.setSelection(routeEditor.getDraft().getHeadingMode().ordinal());
        lastFinishSelection = finish.getSelectedItemPosition();
        lastHeadingSelection = headingMode.getSelectedItemPosition();
        attachDraftEditListeners();
        map = new MapLibreMissionMapView(context);
        ((FrameLayout) findViewById(R.id.mission_mvp_map)).addView(map);
        map.setListener(new MapLibreMissionMapView.Listener() {
            @Override public void onMapTap(double latitude, double longitude) {
                if (mapInteraction.getMode() == MissionMapInteractionState.Mode.CAPTURE_BOUNDARY_POINTS) {
                    captureBoundaryPoint(latitude, longitude);
                    return;
                }
                if (mapInteraction.getMode() == MissionMapInteractionState.Mode.SELECT_DJI_HOME) {
                    mapInteraction.clear();
                    addDjiHome.setText("ADD DJI HOME");
                    confirmSetDjiHome(latitude, longitude, "map point");
                    return;
                }
                if (mapInteraction.getMode() != MissionMapInteractionState.Mode.ADD_WAYPOINTS) {
                    status.setText("Select ADD WAYPOINTS before tapping the map."); return;
                }
                routeEditor.append(new MissionWaypoint(latitude, longitude, null));
                selectedWaypoint = routeEditor.getDraft().getWaypoints().size() - 1;
                routeChanged(String.format(Locale.US, "Waypoint %d added at %.6f, %.6f.",
                        selectedWaypoint + 1, latitude, longitude));
            }
            @Override public void onWaypointTap(int index, float x, float y) {
                selectedWaypoint = index;
                showWaypointMenu(index, x, y);
            }
        });
        operator = MissionControl.getInstance().getWaypointMissionOperator();
        findViewById(R.id.mission_mvp_new).setOnClickListener(view -> newDraft());
        addWaypoints.setOnClickListener(view -> {
            boolean active = mapInteraction.toggleAddWaypoints();
            addWaypoints.setText(active ? "STOP ADDING" : "ADD WAYPOINTS");
            if (active) addDjiHome.setText("ADD DJI HOME");
            status.setText(active ? "Tap the map to append waypoints. Press STOP ADDING when finished."
                    : "Waypoint entry stopped.");
        });
        addDjiHome.setOnClickListener(view -> {
            if (mapInteraction.isBoundaryCapture()) clearBoundaryCapture();
            boolean active = mapInteraction.toggleSelectDjiHome();
            addDjiHome.setText(active ? "CANCEL HOME POINT" : "ADD DJI HOME");
            if (active) addWaypoints.setText("ADD WAYPOINTS");
            status.setText(active ? "Tap the map to choose a DJI Home Point. It will not add a waypoint."
                    : "DJI Home Point selection stopped.");
        });
        ((Button) findViewById(R.id.mission_mvp_center)).setOnClickListener(
                view -> centerOnCurrentLocation());
        findViewById(R.id.mission_mvp_library).setOnClickListener(
                view -> openMissionLibrary());
        findViewById(R.id.mission_mvp_library_path).setOnClickListener(view -> selectMissionFolder());
        ((Button) findViewById(R.id.mission_mvp_delete)).setOnClickListener(view -> deleteSelected());
        ((Button) findViewById(R.id.mission_mvp_save)).setOnClickListener(view -> saveDraft());
        ((Button) findViewById(R.id.mission_mvp_load)).setOnClickListener(view -> load());
        ((Button) findViewById(R.id.mission_mvp_upload)).setOnClickListener(view -> upload());
        ((Button) findViewById(R.id.mission_mvp_boundaries)).setOnClickListener(view -> openBoundaryProfiles());
        findViewById(R.id.mission_mvp_boundary_undo).setOnClickListener(view -> undoBoundaryCapturePoint());
        findViewById(R.id.mission_mvp_boundary_cancel).setOnClickListener(view -> clearBoundaryCapture());
        ((Button) findViewById(R.id.mission_mvp_timeline)).setOnClickListener(view -> openSessionLog());
        ((Button) findViewById(R.id.mission_mvp_start)).setOnClickListener(view -> start());
        ((Button) findViewById(R.id.mission_mvp_pause)).setOnClickListener(view -> { SessionTimelineStore.operator("MISSION_PAUSE", "Pause pressed"); operator.pauseMission(result("Pause")); });
        ((Button) findViewById(R.id.mission_mvp_cancel)).setOnClickListener(view -> { SessionTimelineStore.operator("MISSION_CANCEL", "Cancel pressed"); operator.stopMission(result("Cancel")); });
        refreshRoute();
        renderMissionFolder();
        attachHub();
    }

    private void selectMissionFolder() {
        if (getContext() instanceof MainActivity) ((MainActivity) getContext()).selectMissionFolder();
        else status.setText("Mission folder selection is unavailable from this screen.");
    }

    private void renderMissionFolder() {
        TextView path = findViewById(R.id.mission_mvp_library_path);
        path.setText(missionFolder.displayName());
    }

    private void load() {
        SessionTimelineStore.operator("MISSION_LOAD", "Load pressed");
        try {
            MissionDraft draft = currentDraft();
            String preflightError = firstPreflightError(draft);
            if (preflightError != null) { status.setText("Preflight: " + preflightError); return; }
            int count = draft.getWaypoints().size();
            WaypointMission candidate = WaypointMissionFactory.create(draft);
            DJIError error = operator.loadMission(candidate);
            SessionTimelineStore.android("MISSION_LOAD_RESULT", error == null ? "Mission loaded locally" : error.getDescription());
            status.setText(error == null ? String.format(Locale.US, "Loaded %d waypoints. Upload is now available.", count)
                    : "Load failed: " + error.getDescription());
            loadedMission = error == null ? candidate : null;
            loadedDraft = error == null ? draft : null;
            if (error == null) missionEvidence.recordLoaded(currentAircraftSessionGeneration());
            else missionEvidence.clear();
            draftDirty = error != null;
            refreshRoute();
        } catch (IllegalArgumentException error) {
            loadedMission = null;
            loadedDraft = null;
            missionEvidence.clear();
            status.setText("Invalid mission: " + error.getMessage());
        }
    }

    private void upload() {
        SessionTimelineStore.operator("MISSION_UPLOAD", "Upload pressed");
        MissionDraft draft = requireCurrentLoadedDraft();
        if (draft == null) return;
        String preflightError = firstPreflightError(draft);
        if (preflightError != null) { status.setText("Preflight: " + preflightError); return; }
        final long requestGeneration = currentAircraftSessionGeneration();
        operator.uploadMission(error -> post(() -> {
            if (error == null && missionEvidence.recordUploaded(requestGeneration)) {
                SessionTimelineStore.dji("MISSION_UPLOAD_RESULT", "Upload completed");
                status.setText("Upload successful. Start is now available.");
            } else if (error == null) {
                clearLoadedMissionEvidence();
                SessionTimelineStore.android("MISSION_UPLOAD_RESULT", "Upload result ignored after aircraft session change");
                status.setText("Aircraft session changed. Local draft retained; LOAD and UPLOAD again.");
            } else {
                SessionTimelineStore.dji("MISSION_UPLOAD_RESULT", error.getDescription());
                status.setText("Upload failed: " + error.getDescription());
            }
            renderMissionSync();
        }));
    }


    private void start() {
        SessionTimelineStore.operator("MISSION_START", "Start pressed");
        if (rthActive) { status.setText("RTH is active; Start is unavailable."); return; }
        MissionDraft draft = requireCurrentLoadedDraft();
        if (draft == null || !missionEvidence.isUploadedFor(currentAircraftSessionGeneration())) {
            status.setText("Load and upload a mission first.");
            return;
        }
        String preflightError = firstPreflightError(draft);
        if (preflightError != null) { status.setText("Preflight: " + preflightError); return; }
        FlightReadinessModel.Decision readiness = FlightReadinessStore.getInstance().currentDecision();
        String readinessError = firstStartReadinessError(draft, readiness);
        if (readinessError != null) {
            status.setText("Start blocked: " + readinessError + " Open Operate status for details.");
            return;
        }
        final long requestGeneration = currentAircraftSessionGeneration();
        operator.startMission(error -> post(() -> {
            if (error != null) {
                SessionTimelineStore.dji("MISSION_START_RESULT", error.getDescription());
                status.setText("Start failed: " + error.getDescription()); return;
            }
            if (requestGeneration != currentAircraftSessionGeneration()) {
                SessionTimelineStore.android("MISSION_START_RESULT", "Start result returned after aircraft session change");
                status.setText("Start result returned after aircraft session changed; check aircraft state.");
                renderMissionSync();
                return;
            }
            SessionTimelineStore.dji("MISSION_START_RESULT", "Start request accepted");
            status.setText("Mission started. Returning to Operate.");
            if (getContext() instanceof Activity) ((Activity) getContext()).onBackPressed();
        }));
    }

    private CommonCallbacks.CompletionCallback result(final String action) {
        return error -> post(() -> {
            SessionTimelineStore.dji("MISSION_" + action.toUpperCase(Locale.US) + "_RESULT",
                    error == null ? action + " successful" : error.getDescription());
            status.setText(error == null ? action + " successful." : action + " failed: " + error.getDescription());
        });
    }

    private MissionDraft loadInitialDraft() {
        java.util.List<MissionDraft> saved = library.loadAll();
        return saved.isEmpty() ? MissionDraft.empty() : saved.get(saved.size() - 1);
    }

    private MissionDraft currentDraft() {
        return new MissionDraft(name.getText().toString(), parse(altitude, "altitude"),
                parse(speed, "speed"), selectedFinishAction(), selectedHeadingMode(),
                routeEditor.getDraft().getWaypoints(), routeEditor.getDraft().getPlanningReferenceLatitude(),
                routeEditor.getDraft().getPlanningReferenceLongitude());
    }

    private MissionHeadingMode selectedHeadingMode() {
        return MissionHeadingMode.values()[Math.max(0, Math.min(headingMode.getSelectedItemPosition(),
                MissionHeadingMode.values().length - 1))];
    }

    private void newDraft() {
        if (draftDirty) {
            new AlertDialog.Builder(getContext()).setTitle("Discard local changes?")
                    .setMessage("Create a new empty draft using Settings defaults?")
                    .setNegativeButton("CANCEL", null).setPositiveButton("NEW DRAFT", (dialog, ignored) -> replaceWithNewDraft())
                    .show();
            return;
        }
        replaceWithNewDraft();
    }

    private void replaceWithNewDraft() {
        bindVisibleDraft(defaults.load().newDraft());
        status.setText("New draft created from Settings defaults.");
    }

    private void attachDraftEditListeners() {
        TextWatcher formTextWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence source, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence source, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) { markDraftChanged(); }
        };
        name.addTextChangedListener(formTextWatcher);
        altitude.addTextChangedListener(formTextWatcher);
        speed.addTextChangedListener(formTextWatcher);
        finish.setOnItemSelectedListener(selectionListener(true));
        headingMode.setOnItemSelectedListener(selectionListener(false));
    }

    private AdapterView.OnItemSelectedListener selectionListener(final boolean isFinish) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int previous = isFinish ? lastFinishSelection : lastHeadingSelection;
                if (isFinish) lastFinishSelection = position;
                else lastHeadingSelection = position;
                if (position != previous) markDraftChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }

    private void bindVisibleDraft(MissionDraft draft) {
        bindingDraft = true;
        try {
            routeEditor.replace(draft);
            name.setText(draft.getName());
            altitude.setText(String.format(Locale.US, "%.1f", draft.getDefaultAltitudeMeters()));
            speed.setText(String.format(Locale.US, "%.1f", draft.getBaseSpeedMetersPerSecond()));
            finish.setSelection(draft.getFinishAction().ordinal());
            headingMode.setSelection(draft.getHeadingMode().ordinal());
            lastFinishSelection = finish.getSelectedItemPosition();
            lastHeadingSelection = headingMode.getSelectedItemPosition();
            selectedWaypoint = -1;
            draftDirty = false;
            clearLoadedMissionEvidence();
        } finally {
            bindingDraft = false;
        }
        refreshRoute();
    }

    private MissionFinishAction selectedFinishAction() {
        switch (finish.getSelectedItemPosition()) {
            case 1: return MissionFinishAction.RETURN_AND_LAND_DJI_HOME;
            case 2: return MissionFinishAction.LAND_AT_FINAL;
            default: return MissionFinishAction.HOVER_AT_FINAL;
        }
    }

    private float parse(EditText field, String label) {
        try { return Float.parseFloat(field.getText().toString().trim()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("invalid " + label); }
    }

    private void saveDraft() {
        try {
            MissionDraft draft = currentDraft();
            routeEditor.replace(draft);
            boolean saved = library.save(draft);
            draftDirty = !saved;
            refreshRoute();
            status.setText(saved ? "Draft saved locally." : "Draft is invalid and was not saved.");
        } catch (IllegalArgumentException exception) { status.setText("Invalid draft: " + exception.getMessage()); }
    }

    private void deleteSelected() {
        if (!routeEditor.delete(selectedWaypoint)) { status.setText("Select a waypoint to delete."); return; }
        selectedWaypoint = -1;
        routeChanged("Waypoint deleted.");
    }

    private void showWaypointMenu(int index, float x, float y) {
        if (index < 0 || index >= routeEditor.getDraft().getWaypoints().size()) return;
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(6), dp(8), dp(6));
        content.setBackgroundResource(R.drawable.operate_mvp_panel);
        PopupWindow popup = new PopupWindow(content, dp(166),
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        Button inspect = waypointMenuButton("INSPECT / EDIT");
        Button setHome = waypointMenuButton("SET DJI HOME");
        Button delete = waypointMenuButton("DELETE");
        content.addView(inspect);
        content.addView(setHome);
        content.addView(delete);
        inspect.setOnClickListener(view -> {
            popup.dismiss();
            inspectWaypoint(index);
        });
        setHome.setOnClickListener(view -> {
            popup.dismiss();
            confirmSetDjiHome(index);
        });
        delete.setOnClickListener(view -> {
            popup.dismiss();
            selectedWaypoint = index;
            deleteSelected();
        });
        content.measure(View.MeasureSpec.makeMeasureSpec(dp(166), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] mapPosition = new int[2];
        map.getLocationOnScreen(mapPosition);
        View root = getRootView();
        int desiredX = mapPosition[0] + Math.round(x) + dp(12);
        int desiredY = mapPosition[1] + Math.round(y) - content.getMeasuredHeight() / 2;
        int popupX = Math.max(dp(6), Math.min(desiredX, root.getWidth() - dp(172)));
        int popupY = Math.max(dp(6), Math.min(desiredY, root.getHeight() - content.getMeasuredHeight() - dp(6)));
        popup.showAtLocation(map, Gravity.NO_GRAVITY, popupX, popupY);
    }

    private Button waypointMenuButton(String label) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(10);
        button.setTextColor(Color.rgb(22, 42, 54));
        button.setBackgroundResource(R.drawable.operate_mvp_button);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        return button;
    }

    private void inspectWaypoint(int index) {
        if (index < 0 || index >= routeEditor.getDraft().getWaypoints().size()) return;
        selectedWaypoint = index;
        MissionDraft draft = routeEditor.getDraft();
        MissionWaypoint waypoint = draft.getWaypoints().get(index);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView order = inspectorLabel(String.format(Locale.US, "WAYPOINT %d OF %d", index + 1,
                draft.getWaypoints().size()));
        content.addView(order);
        content.addView(inspectorLabel("LATITUDE (WGS84)"));
        EditText latitude = inspectorField(String.format(Locale.US, "%.6f", waypoint.getLatitude()),
                android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        content.addView(latitude);
        content.addView(inspectorLabel("LONGITUDE (WGS84)"));
        EditText longitude = inspectorField(String.format(Locale.US, "%.6f", waypoint.getLongitude()),
                android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        content.addView(longitude);
        String override = waypoint.getAltitudeOverrideMeters() == null ? ""
                : String.format(Locale.US, "%.1f", waypoint.getAltitudeOverrideMeters());
        content.addView(inspectorLabel("ALTITUDE OVERRIDE (m) — BLANK = DEFAULT "
                + String.format(Locale.US, "%.1f", draft.getDefaultAltitudeMeters())));
        EditText altitudeOverride = inspectorField(override,
                android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        content.addView(altitudeOverride);
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button insertAfter = inspectorAction("INSERT");
        Button moveEarlier = inspectorAction("EARLIER");
        Button moveLater = inspectorAction("LATER");
        Button delete = inspectorAction("DELETE");
        actions.addView(insertAfter);
        actions.addView(moveEarlier);
        actions.addView(moveLater);
        actions.addView(delete);
        content.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(content)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE WAYPOINT", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                MissionWaypoint updated;
                try {
                    updated = waypointFromFields(latitude, longitude, altitudeOverride);
                } catch (IllegalArgumentException error) {
                    status.setText("Waypoint unchanged: " + error.getMessage());
                    return;
                }
                MissionDraft candidate = draft.withWaypoints(replaceWaypoint(draft, index, updated));
                if (!MissionDraftValidator.validate(candidate).isEmpty()) {
                    status.setText("Waypoint unchanged: " + MissionDraftValidator.validate(candidate).get(0));
                    return;
                }
                routeEditor.replace(candidate);
                selectedWaypoint = index;
                routeChanged("Waypoint " + (index + 1) + " updated. Load it again before upload or start.");
                dialog.dismiss();
            });
            insertAfter.setOnClickListener(view -> {
                routeEditor.insertAfter(index, new MissionWaypoint(waypoint.getLatitude(), waypoint.getLongitude(),
                        waypoint.getAltitudeOverrideMeters()));
                selectedWaypoint = index + 1;
                routeChanged("Waypoint inserted after " + (index + 1) + ". Load it again before upload or start.");
                dialog.dismiss();
                inspectWaypoint(selectedWaypoint);
            });
            moveEarlier.setOnClickListener(view -> {
                if (!routeEditor.moveEarlier(index)) {
                    status.setText("Waypoint " + (index + 1) + " is already first.");
                    return;
                }
                selectedWaypoint = index - 1;
                routeChanged("Waypoint moved earlier. Load it again before upload or start.");
                dialog.dismiss();
                inspectWaypoint(selectedWaypoint);
            });
            moveLater.setOnClickListener(view -> {
                if (!routeEditor.moveLater(index)) {
                    status.setText("Waypoint " + (index + 1) + " is already last.");
                    return;
                }
                selectedWaypoint = index + 1;
                routeChanged("Waypoint moved later. Load it again before upload or start.");
                dialog.dismiss();
                inspectWaypoint(selectedWaypoint);
            });
            delete.setOnClickListener(view -> {
                routeEditor.delete(index);
                selectedWaypoint = -1;
                routeChanged("Waypoint deleted. Load it again before upload or start.");
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private TextView inspectorLabel(String text) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextColor(Color.rgb(22, 42, 54));
        label.setTextSize(12);
        label.setPadding(0, 0, 0, dp(6));
        return label;
    }

    private EditText inspectorField(String value, int inputType) {
        EditText field = new EditText(getContext());
        field.setText(value);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setInputType(inputType);
        return field;
    }

    private Button inspectorAction(String label) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(9);
        button.setTextColor(Color.rgb(22, 42, 54));
        button.setBackgroundResource(R.drawable.operate_mvp_button);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(34), 1f));
        return button;
    }

    private MissionWaypoint waypointFromFields(EditText latitude, EditText longitude, EditText altitudeOverride) {
        try {
            double lat = Double.parseDouble(latitude.getText().toString().trim());
            double lon = Double.parseDouble(longitude.getText().toString().trim());
            String altitudeText = altitudeOverride.getText().toString().trim();
            Float override = altitudeText.isEmpty() ? null : Float.parseFloat(altitudeText);
            if (!LocationCoordinate2D.isValid(lat, lon)) throw new IllegalArgumentException("coordinates are invalid");
            if (override != null && (!Float.isFinite(override) || override < 0f)) {
                throw new IllegalArgumentException("altitude override must be zero or greater");
            }
            return new MissionWaypoint(lat, lon, override);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("coordinates and altitude must be numbers");
        }
    }

    private java.util.List<MissionWaypoint> replaceWaypoint(MissionDraft draft, int index,
            MissionWaypoint updated) {
        java.util.List<MissionWaypoint> waypoints = new java.util.ArrayList<>(draft.getWaypoints());
        waypoints.set(index, updated);
        return waypoints;
    }

    private void routeChanged(String message) {
        markDraftChanged();
        status.setText(message);
    }

    private void markDraftChanged() {
        if (bindingDraft) return;
        if (!draftDirty) clearLoadedMissionEvidence();
        draftDirty = true;
        refreshRoute();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void confirmSetDjiHome(int index) {
        MissionWaypoint waypoint = routeEditor.getDraft().getWaypoints().get(index);
        confirmSetDjiHome(waypoint.getLatitude(), waypoint.getLongitude(), "waypoint " + (index + 1));
    }

    private void confirmSetDjiHome(double latitude, double longitude, String source) {
        String coordinate = String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
        map.setPendingDjiHome(latitude, longitude);
        AlertDialog homeDialog = new AlertDialog.Builder(getContext())
                .setTitle("Set DJI Home Point?")
                .setMessage(capitalize(source) + "\n" + coordinate
                        + "\n\nThis changes the aircraft's real Return-to-Home location immediately."
                        + " It does not save, load, or upload this mission.")
                .setNegativeButton("CANCEL", (ignoredDialog, ignored) -> map.setPendingDjiHome(null, null))
                .setPositiveButton("SET DJI HOME", (ignoredDialog, ignored) -> setDjiHome(latitude, longitude, source))
                .create();
        homeDialog.setOnCancelListener(ignored -> map.setPendingDjiHome(null, null));
        homeDialog.show();
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? "Map point"
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void setDjiHome(double latitude, double longitude, String source) {
        SessionTimelineStore.operator("DJI_HOME_SET", "Set DJI Home requested for " + source);
        final long requestGeneration = currentAircraftSessionGeneration();
        boolean sent = DjiDataHub.getInstance().setDjiHomeLocation(
                new LocationCoordinate2D(latitude, longitude), error ->
                        post(() -> {
                            if (requestGeneration != currentAircraftSessionGeneration()) return;
                            if (error != null) {
                                SessionTimelineStore.dji("DJI_HOME_SET_RESULT", error.getDescription());
                                map.setPendingDjiHome(null, null);
                                status.setText("Set DJI Home failed: " + error.getDescription());
                                return;
                            }
                            SessionTimelineStore.dji("DJI_HOME_SET_RESULT", "DJI Home confirmed for " + source);
                            map.setPendingDjiHome(null, null);
                            // DJI accepted the explicit command. Reflect that confirmed global Home
                            // immediately instead of waiting for the next recurring state callback.
                            renderConfirmedDjiHome(latitude, longitude);
                            DjiDataHub.getInstance().requestDjiHomeReadback();
                            status.setText("DJI Home set to " + source + ".");
                        }));
        if (!sent) {
            SessionTimelineStore.android("DJI_HOME_SET_RESULT", "Flight controller or coordinates unavailable");
            map.setPendingDjiHome(null, null);
            status.setText("DJI Home is unavailable: flight controller or coordinates not ready.");
        }
    }

    private void openMissionLibrary() {
        java.util.List<MissionDraft> drafts = library.loadAll();
        if (drafts.isEmpty()) {
            status.setText("No saved missions. Name a route and use SAVE DRAFT.");
            return;
        }
        String[] labels = new String[drafts.size()];
        for (int index = 0; index < drafts.size(); index++) {
            MissionDraft draft = drafts.get(index);
            labels[index] = String.format(Locale.US, "%s  ·  %d waypoints", draft.getName(),
                    draft.getWaypoints().size());
        }
        new AlertDialog.Builder(getContext())
                .setTitle("MISSIONS")
                .setItems(labels, (dialog, index) -> openSavedDraft(drafts.get(index)))
                .show();
    }

    private void openSavedDraft(MissionDraft draft) {
        bindVisibleDraft(draft);
        status.setText("Opened " + draft.getName() + ". Edit, then LOAD when ready.");
    }

    private void centerOnCurrentLocation() {
        Location tabletLocation = latestTabletLocation();
        if (tabletLocation != null) {
            map.centerOn(tabletLocation.getLatitude(), tabletLocation.getLongitude(), 18.5d);
            status.setText("Centered on tablet location.");
        } else if (latestAircraftLatitude != null && latestAircraftLongitude != null
                && OperateStatusModel.isFresh(latestAircraftLocationElapsedMs,
                SystemClock.elapsedRealtime())) {
            map.centerOn(latestAircraftLatitude, latestAircraftLongitude, 18.5d);
            status.setText("Tablet location unavailable; centered on aircraft.");
        } else {
            status.setText("No current tablet or aircraft location is available.");
        }
    }

    private Location latestTabletLocation() {
        if (ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        Location newest = null;
        long nowMs = SystemClock.elapsedRealtime();
        for (String provider : manager.getProviders(true)) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                long receivedAtMs = candidate == null ? -1L : candidate.getElapsedRealtimeNanos() / 1_000_000L;
                if (candidate != null && OperateStatusModel.isFresh(receivedAtMs, nowMs)
                        && (newest == null || candidate.getElapsedRealtimeNanos()
                        > newest.getElapsedRealtimeNanos())) {
                    newest = candidate;
                }
            } catch (SecurityException ignored) { }
        }
        return newest;
    }

    private void refreshRoute() {
        MissionDraft draft = routeEditor.getDraft();
        route.setText(String.format(Locale.US, "ROUTE — %d WAYPOINTS%s", draft.getWaypoints().size(),
                draftDirty ? " · LOCAL CHANGES" : ""));
        map.render(draft);
        renderMissionSync();
        renderRouteStats();
        renderBoundaryWarnings();
    }

    private void openBoundaryProfiles() {
        java.util.List<PlanningBoundaryProfile> profiles = boundaryProfiles.loadAll();
        String[] names = new String[profiles.size() + 1]; names[0] = "NEW PROFILE";
        for (int index = 0; index < profiles.size(); index++) names[index + 1] = profiles.get(index).getName();
        new AlertDialog.Builder(getContext()).setTitle("LOCAL BOUNDARIES")
                .setItems(names, (dialog, index) -> {
                    if (index == 0) createBoundaryProfile();
                    else openBoundaryProfileEditor(profiles.get(index - 1));
                }).show();
    }

    private void createBoundaryProfile() {
        EditText profileName = inspectorField("Local profile", android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(getContext()).setTitle("NEW LOCAL PROFILE").setView(profileName)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONTINUE", (dialog, ignored) -> {
                    PlanningBoundaryProfile profile = new PlanningBoundaryProfile(profileName.getText().toString(),
                            java.util.Collections.<PlanningBoundary>emptyList());
                    if (findBoundaryProfile(profile.getName()) != null) {
                        status.setText("Profile already exists. Select it to add or edit a boundary.");
                        return;
                    }
                    beginBoundaryCapture(profile);
                }).show();
    }

    private void openBoundaryProfileEditor(PlanningBoundaryProfile profile) {
        selectBoundaryProfile(profile);
        String[] entries = new String[profile.getBoundaries().size() + 1];
        entries[0] = "+ NEW BOUNDARY";
        for (int index = 0; index < profile.getBoundaries().size(); index++) {
            PlanningBoundary boundary = profile.getBoundaries().get(index);
            entries[index + 1] = String.format(Locale.US, "%d. %s · %s %s", index + 1, boundary.getName(),
                    boundary.getKind().name(), boundary.getShape().name());
        }
        new AlertDialog.Builder(getContext()).setTitle(profile.getName() + " — LOCAL ONLY")
                .setItems(entries, (dialog, index) -> {
                    if (index == 0) beginBoundaryCapture(profile);
                    else editBoundary(profile, index - 1, profile.getBoundaries().get(index - 1));
                }).show();
    }

    private void beginBoundaryCapture(PlanningBoundaryProfile profile) {
        boundaryCaptureProfile = profile;
        boundaryCapturePoints.clear();
        mapInteraction.beginBoundaryCapture();
        map.setBoundaryCapturePoints(boundaryCapturePoints);
        findViewById(R.id.mission_mvp_boundary_capture_actions).setVisibility(VISIBLE);
        status.setText("Tap four map positions for local boundary " + profile.getName() + ".");
    }

    private void captureBoundaryPoint(double latitude, double longitude) {
        if (boundaryCaptureProfile == null || boundaryCapturePoints.size() >= 4) return;
        boundaryCapturePoints.add(new PlanningBoundary.Point(latitude, longitude));
        map.setBoundaryCapturePoints(boundaryCapturePoints);
        if (boundaryCapturePoints.size() < 4) {
            status.setText("Boundary point " + boundaryCapturePoints.size() + " of 4 added.");
            return;
        }
        chooseCapturedBoundaryType();
    }

    private void chooseCapturedBoundaryType() {
        String[] types = {"EXCLUSION POLYGON", "INCLUSION POLYGON", "EXCLUSION CIRCLE", "INCLUSION CIRCLE"};
        new AlertDialog.Builder(getContext()).setTitle("BUILD LOCAL BOUNDARY")
                .setMessage("Four points captured. Circle encloses all four points. Not DJI enforced.")
                .setNegativeButton("CANCEL", (dialog, ignored) -> clearBoundaryCapture())
                .setItems(types, (dialog, index) -> saveCapturedBoundary(types[index])).show();
    }

    private void saveCapturedBoundary(String type) {
        if (boundaryCaptureProfile == null || boundaryCapturePoints.size() != 4) return;
        try {
            PlanningBoundary.Kind kind = type.startsWith("EXCLUSION")
                    ? PlanningBoundary.Kind.EXCLUSION : PlanningBoundary.Kind.INCLUSION;
            PlanningBoundary boundary;
            if (type.endsWith("POLYGON")) {
                boundary = PlanningBoundary.polygon("Boundary " + (boundaryCaptureProfile.getBoundaries().size() + 1), kind, boundaryCapturePoints);
            } else {
                double lat = 0d, lon = 0d;
                for (PlanningBoundary.Point point : boundaryCapturePoints) { lat += point.getLatitude(); lon += point.getLongitude(); }
                lat /= boundaryCapturePoints.size(); lon /= boundaryCapturePoints.size();
                double radius = 1d;
                for (PlanningBoundary.Point point : boundaryCapturePoints) radius = Math.max(radius, distanceMeters(lat, lon, point.getLatitude(), point.getLongitude()));
                boundary = PlanningBoundary.circle("Boundary " + (boundaryCaptureProfile.getBoundaries().size() + 1), kind, lat, lon, radius);
            }
            PlanningBoundaryProfile saved = boundaryCaptureProfile.withBoundaryAppended(boundary);
            if (!boundaryProfiles.save(saved)) throw new IllegalStateException("could not save profile");
            selectBoundaryProfile(saved); status.setText("Local boundary saved. It is not DJI enforced.");
        } catch (RuntimeException error) { status.setText("Boundary not saved: " + error.getMessage()); }
        finally { clearBoundaryCapture(); }
    }

    private void clearBoundaryCapture() {
        mapInteraction.cancelBoundaryCapture(); boundaryCaptureProfile = null; boundaryCapturePoints.clear(); map.setBoundaryCapturePoints(null);
        findViewById(R.id.mission_mvp_boundary_capture_actions).setVisibility(GONE);
    }

    private void undoBoundaryCapturePoint() {
        if (!mapInteraction.isBoundaryCapture() || boundaryCapturePoints.isEmpty()) {
            status.setText("No boundary point to undo.");
            return;
        }
        boundaryCapturePoints.remove(boundaryCapturePoints.size() - 1);
        map.setBoundaryCapturePoints(boundaryCapturePoints);
        status.setText("Boundary point removed. " + boundaryCapturePoints.size() + " of 4 captured.");
    }

    private static double distanceMeters(double aLat, double aLon, double bLat, double bLon) {
        double lat = Math.toRadians(bLat - aLat), lon = Math.toRadians(bLon - aLon);
        double h = Math.sin(lat / 2d) * Math.sin(lat / 2d) + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(lon / 2d) * Math.sin(lon / 2d);
        return 12742000d * Math.atan2(Math.sqrt(h), Math.sqrt(1d - h));
    }

    private void chooseBoundaryType(PlanningBoundaryProfile profile) {
        String[] types = {"EXCLUSION CIRCLE", "INCLUSION CIRCLE", "EXCLUSION POLYGON", "INCLUSION POLYGON"};
        new AlertDialog.Builder(getContext()).setTitle("NEW LOCAL BOUNDARY")
                .setItems(types, (dialog, index) -> editBoundary(profile, -1, types[index], null)).show();
    }

    private void editBoundary(PlanningBoundaryProfile profile, int index, PlanningBoundary boundary) {
        String type = boundary.getKind().name() + " " + boundary.getShape().name();
        editBoundary(profile, index, type, boundary);
    }

    private void editBoundary(PlanningBoundaryProfile profile, int index, String type, PlanningBoundary existing) {
        LinearLayout panel = new LinearLayout(getContext()); panel.setOrientation(VERTICAL); panel.setPadding(dp(18), dp(6), dp(18), 0);
        boolean circle = type.endsWith("CIRCLE");
        EditText boundaryName = inspectorField(existing == null ? type : existing.getName(),
                android.text.InputType.TYPE_CLASS_TEXT);
        EditText geometry = inspectorField(existing == null ? (circle ? "lat,lon,radius_m" : "lat,lon; lat,lon; lat,lon")
                : boundaryGeometry(existing), android.text.InputType.TYPE_CLASS_TEXT);
        panel.addView(inspectorLabel("PROFILE — " + profile.getName()));
        panel.addView(inspectorLabel("BOUNDARY NAME")); panel.addView(boundaryName);
        panel.addView(inspectorLabel(circle ? "CENTER / RADIUS" : "POLYGON VERTICES")); panel.addView(geometry);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext()).setTitle(existing == null ? type : "EDIT " + type)
                .setView(panel).setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE LOCAL", null);
        if (existing != null) builder.setNeutralButton("DELETE", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (saveBoundary(profile, index, type, boundaryName.getText().toString(),
                        geometry.getText().toString())) dialog.dismiss();
            });
            if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                dialog.dismiss();
                confirmDeleteBoundary(profile, index);
            });
        });
        dialog.show();
    }

    private String boundaryGeometry(PlanningBoundary boundary) {
        if (boundary.getShape() == PlanningBoundary.Shape.CIRCLE) {
            PlanningBoundary.Point point = boundary.getPoints().get(0);
            return String.format(Locale.US, "%.6f,%.6f,%.1f", point.getLatitude(), point.getLongitude(), boundary.getRadiusMeters());
        }
        StringBuilder result = new StringBuilder();
        for (PlanningBoundary.Point point : boundary.getPoints()) {
            if (result.length() > 0) result.append("; ");
            result.append(String.format(Locale.US, "%.6f,%.6f", point.getLatitude(), point.getLongitude()));
        }
        return result.toString();
    }

    private boolean saveBoundary(PlanningBoundaryProfile profile, int index, String type, String name,
            String source) {
        try {
            String boundaryName = name == null ? "" : name.trim();
            if (boundaryName.isEmpty()) throw new IllegalArgumentException("boundary name is required");
            boolean exclusion = type.startsWith("EXCLUSION"); PlanningBoundary.Kind kind = exclusion ? PlanningBoundary.Kind.EXCLUSION : PlanningBoundary.Kind.INCLUSION;
            PlanningBoundary boundary;
            if (type.endsWith("CIRCLE")) { String[] parts = source.split(","); if (parts.length != 3) throw new IllegalArgumentException("use lat,lon,radius_m");
                boundary = PlanningBoundary.circle(boundaryName, kind, Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()));
            } else { java.util.List<PlanningBoundary.Point> points = new java.util.ArrayList<>(); for (String pair : source.split(";")) { String[] parts = pair.split(","); if (parts.length != 2) throw new IllegalArgumentException("use lat,lon; lat,lon; lat,lon"); points.add(new PlanningBoundary.Point(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()))); }
                boundary = PlanningBoundary.polygon(boundaryName, kind, points); }
            PlanningBoundaryProfile updated = index < 0 ? profile.withBoundaryAppended(boundary)
                    : profile.withBoundaryReplaced(index, boundary);
            if (!boundaryProfiles.save(updated)) throw new IllegalArgumentException("could not save profile");
            selectBoundaryProfile(updated); status.setText("Local profile saved. It is not DJI enforced.");
            return true;
        } catch (RuntimeException error) {
            status.setText("Boundary not saved: " + error.getMessage());
            return false;
        }
    }

    private void confirmDeleteBoundary(PlanningBoundaryProfile profile, int index) {
        if (index < 0 || index >= profile.getBoundaries().size()) return;
        PlanningBoundary boundary = profile.getBoundaries().get(index);
        new AlertDialog.Builder(getContext()).setTitle("Delete local boundary?")
                .setMessage(boundary.getName() + " will be removed from " + profile.getName()
                        + ". This does not change DJI aircraft restrictions.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DELETE", (dialog, ignored) -> {
                    PlanningBoundaryProfile updated = profile.withBoundaryDeleted(index);
                    if (!boundaryProfiles.save(updated)) {
                        status.setText("Boundary was not deleted locally.");
                        return;
                    }
                    selectBoundaryProfile(updated);
                    status.setText("Local boundary deleted. It was not DJI enforced.");
                }).show();
    }

    private void selectBoundaryProfile(PlanningBoundaryProfile profile) {
        activeBoundaryProfile = profile;
        map.setPlanningBoundaries(profile.getBoundaries());
        renderBoundaryWarnings();
    }

    private PlanningBoundaryProfile findBoundaryProfile(String name) {
        String normalized = new PlanningBoundaryProfile(name, java.util.Collections.<PlanningBoundary>emptyList()).getName();
        if (activeBoundaryProfile != null && normalized.equals(activeBoundaryProfile.getName())) return activeBoundaryProfile;
        for (PlanningBoundaryProfile profile : boundaryProfiles.loadAll()) {
            if (normalized.equals(profile.getName())) return profile;
        }
        return null;
    }

    private void renderBoundaryWarnings() {
        if (boundaryWarning == null) return;
        if (activeBoundaryProfile == null) { boundaryWarning.setText("LOCAL PLANNING WARNING — NOT DJI ENFORCED"); return; }
        BoundaryEvaluator.Result evaluation = BoundaryEvaluator.evaluate(routeEditor.getDraft(), activeBoundaryProfile.getBoundaries());
        int count = evaluation.getWarningCount();
        boundaryWarning.setText(activeBoundaryProfile.getName() + " — " + count
                + " ROUTE WARNING" + (count == 1 ? "" : "S")
                + (count == 0 ? "" : ": " + joinBoundaryNames(evaluation.getWarningBoundaryNames()))
                + " · NOT DJI ENFORCED");
    }

    private String joinBoundaryNames(java.util.List<String> names) {
        StringBuilder result = new StringBuilder();
        for (String boundaryName : names) {
            if (result.length() > 0) result.append(", ");
            result.append(boundaryName);
        }
        return result.toString();
    }

    private void openSessionLog() {
        String text = sessionLogText();
        TextView content = new TextView(getContext()); content.setText(text); content.setTextSize(11);
        content.setPadding(dp(18), dp(10), dp(18), dp(10));
        ScrollView scroll = new ScrollView(getContext()); scroll.addView(content);
        new AlertDialog.Builder(getContext()).setTitle("SESSION LOG").setView(scroll)
                .setNegativeButton("CLOSE", null).setPositiveButton("EXPORT", (dialog, ignored) -> exportSessionLog(text)).show();
    }

    private String sessionLogText() {
        StringBuilder result = new StringBuilder();
        for (SessionTimeline.Event event : SessionTimelineStore.timeline().snapshot()) {
            result.append(event.getElapsedRealtimeMs()).append(" ms · ").append(event.getTimeSource())
                    .append(" · ").append(event.getType()).append(" · ").append(event.getDetail()).append('\n');
        }
        if (result.length() == 0) result.append("No events in this session.");
        long dropped = SessionTimelineStore.timeline().getDroppedCount();
        if (dropped > 0) result.append("Dropped oldest events: ").append(dropped).append('\n');
        return result.toString();
    }

    private void exportSessionLog(String text) {
        Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT, text);
        getContext().startActivity(Intent.createChooser(share, "Export session log"));
        SessionTimelineStore.operator("SESSION_EXPORT", "Shared local text timeline");
    }

    private void renderMissionSync() {
        if (sync == null) return;
        WaypointMissionState currentState = operator == null ? null : operator.getCurrentState();
        String stateName = currentState == null ? "" : currentState.getName();
        long generation = currentAircraftSessionGeneration();
        sync.setText(MissionSyncState.resolve(missionEvidence.isLoadedFor(generation),
                missionEvidence.isUploadedFor(generation),
                draftDirty, stateName).render());
    }

    private void renderRouteStats() {
        if (stats == null) return;
        try {
            RouteStatistics.Snapshot estimate = RouteStatistics.calculate(currentDraft());
            if (!estimate.isAvailable()) {
                stats.setText("ROUTE ESTIMATE — ADD TWO VALID WAYPOINTS\n"
                        + "TERRAIN UNKNOWN · BATTERY/RESERVE NOT ESTIMATED");
                return;
            }
            stats.setText(String.format(Locale.US,
                    "ESTIMATE — %.0f m · %d WP · ALT %.0f–%.0f m · %s\n"
                            + "CLIMB %.0f m · DESCENT %.0f m · TERRAIN UNKNOWN · BATTERY/RESERVE NOT ESTIMATED",
                    estimate.getDistanceMeters(), estimate.getWaypointCount(),
                    estimate.getMinimumAltitudeMeters(), estimate.getMaximumAltitudeMeters(),
                    formatDuration(estimate.getEstimatedDurationSeconds()), estimate.getClimbMeters(),
                    estimate.getDescentMeters()));
        } catch (IllegalArgumentException ignored) {
            stats.setText("ROUTE ESTIMATE — INVALID ALTITUDE OR SPEED\n"
                    + "TERRAIN UNKNOWN · BATTERY/RESERVE NOT ESTIMATED");
        }
    }

    private String formatDuration(double seconds) {
        int totalSeconds = Math.max(0, (int) Math.round(seconds));
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private MissionDraft requireCurrentLoadedDraft() {
        if (invalidateMissionEvidenceForAircraftSession(true)
                || loadedMission == null || loadedDraft == null
                || !missionEvidence.isLoadedFor(currentAircraftSessionGeneration())) {
            status.setText("Load a valid mission first.");
            return null;
        }
        final MissionDraft draft;
        try {
            draft = currentDraft();
        } catch (IllegalArgumentException error) {
            clearLoadedMissionEvidence();
            status.setText("Mission changed and is invalid: " + error.getMessage());
            return null;
        }
        if (!loadedDraft.equals(draft)) {
            clearLoadedMissionEvidence();
            status.setText("Mission changed. Load it again before upload or start.");
            return null;
        }
        return draft;
    }

    private void attachHub() {
        if (hubSubscription != null) return;
        FlightReadinessStore store = FlightReadinessStore.getInstance();
        store.start();
        readinessSubscription = store.addListener(decision -> post(() -> {
            if (invalidateMissionEvidenceForAircraftSession(true)) {
                status.setText("Aircraft connection changed. Local draft retained; LOAD and UPLOAD again.");
            }
            renderMissionSync();
        }));
        hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
            @Override public void onStatusSnapshot(@NonNull DjiDataHub.StatusSnapshot snapshot) {
                post(() -> renderStatusSnapshot(snapshot));
            }
        });
    }

    private long currentAircraftSessionGeneration() {
        return FlightReadinessStore.getInstance().getAircraftSessionGeneration();
    }

    private boolean invalidateMissionEvidenceForAircraftSession(boolean recordTimeline) {
        if (!missionEvidence.invalidateFor(currentAircraftSessionGeneration())) return false;
        loadedMission = null;
        loadedDraft = null;
        if (recordTimeline) {
            SessionTimelineStore.android("MISSION_SESSION_INVALIDATED",
                    "Aircraft connection changed; prior Load/Upload evidence cleared");
        }
        return true;
    }

    private void clearLoadedMissionEvidence() {
        loadedMission = null;
        loadedDraft = null;
        missionEvidence.clear();
    }

    private void renderStatusSnapshot(@NonNull DjiDataHub.StatusSnapshot snapshot) {
        FlightControllerState state = snapshot.flightState;
        flightStateAvailable = state != null;
        rthActive = state != null && state.isGoingHome();
        DjiHomeState djiHome = snapshot.djiHome;
        // DJI Home is a connected-session configuration, not a streaming sample. The hub keeps a
        // successful command/readback until disconnect, so it must not be downgraded merely
        // because no newer flight-state callback arrived within the UI freshness interval.
        homeAvailable = djiHome != null && djiHome.isAvailable();
        if (rthActive) {
            home.setText("RTH — " + String.valueOf(state.getGoHomeExecutionState()));
        } else if (djiHome == null || djiHome.getAvailability() == DjiHomeState.Availability.WAITING_FOR_SAMPLE) {
            home.setText("CHECKING DJI HOME");
        } else {
            home.setText(homeAvailable ? "HOME READY" : "HOME UNAVAILABLE");
        }
        map.setDjiHome(homeAvailable ? djiHome.getLatitude() : null,
                homeAvailable ? djiHome.getLongitude() : null);
        if (state == null) {
            latestAircraftLatitude = null;
            latestAircraftLongitude = null;
            latestAircraftLocationElapsedMs = -1L;
            map.setAircraft(null, null);
            return;
        }
        LocationCoordinate3D aircraftLocation = state.getAircraftLocation();
        boolean aircraftValid = aircraftLocation != null && LocationCoordinate2D.isValid(
                aircraftLocation.getLatitude(), aircraftLocation.getLongitude());
        latestAircraftLocationElapsedMs = snapshot.flightStateElapsedMs;
        aircraftValid = aircraftValid && OperateStatusModel.isFresh(latestAircraftLocationElapsedMs,
                SystemClock.elapsedRealtime());
        latestAircraftLatitude = aircraftValid ? aircraftLocation.getLatitude() : null;
        latestAircraftLongitude = aircraftValid ? aircraftLocation.getLongitude() : null;
        map.setAircraft(latestAircraftLatitude, latestAircraftLongitude);
    }

    private void renderConfirmedDjiHome(double latitude, double longitude) {
        homeAvailable = true;
        if (!rthActive) home.setText("HOME READY");
        map.setDjiHome(latitude, longitude);
    }

    private String firstPreflightError(MissionDraft draft) {
        java.util.List<String> errors = MissionPreflight.validate(draft, flightStateAvailable,
                homeAvailable, rthActive);
        return errors.isEmpty() ? null : errors.get(0);
    }

    private String firstStartReadinessError(MissionDraft draft,
            FlightReadinessModel.Decision readiness) {
        java.util.List<String> errors = MissionPreflight.validateStart(draft, flightStateAvailable,
                homeAvailable, rthActive, readiness);
        return errors.isEmpty() ? null : errors.get(0);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus().register(this);
        FlightReadinessStore.getInstance().start();
        attachHub();
        DjiDataHub.getInstance().requestStatusSnapshot();
        DjiDataHub.getInstance().requestDjiHomeReadback();
        renderMissionFolder();
        if (!missionListenerAttached) {
            operator.addListener(missionListener);
            missionListenerAttached = true;
        }
        if (invalidateMissionEvidenceForAircraftSession(true)) {
            status.setText("Aircraft connection changed. Local draft retained; LOAD and UPLOAD again.");
        }
        renderMissionSync();
    }

    @Override protected void onDetachedFromWindow() {
        DJISampleApplication.getEventBus().unregister(this);
        mapInteraction.clear();
        boundaryCaptureProfile = null; boundaryCapturePoints.clear();
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
        if (readinessSubscription != null) readinessSubscription.close();
        readinessSubscription = null;
        if (missionListenerAttached) operator.removeListener(missionListener);
        missionListenerAttached = false;
        super.onDetachedFromWindow();
    }

    public void discardPage() {
        map.destroyMap();
    }

    @Subscribe public void onMissionFolderSelected(MainActivity.MissionFolderSelectedEvent event) {
        post(() -> {
            renderMissionFolder();
            status.setText("Mission folder selected. Saved missions stay local until you explicitly load and upload.");
        });
    }

    @Override public int getDescription() { return R.string.operate_menu_mission; }
    @NonNull @Override public String getHint() { return getClass().getSimpleName() + ".java"; }
}
