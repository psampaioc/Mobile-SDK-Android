package com.dji.sdk.sample.operate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
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
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.djihub.CallbackMulticaster;
import com.dji.sdk.sample.djihub.DjiDataHub;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.missionplanner.MissionDraft;
import com.dji.sdk.sample.missionplanner.MissionFinishAction;
import com.dji.sdk.sample.missionplanner.MissionDraftLibrary;
import com.dji.sdk.sample.missionplanner.MissionDraftValidator;
import com.dji.sdk.sample.missionplanner.MissionRouteEditor;
import com.dji.sdk.sample.missionplanner.MissionPreflight;
import com.dji.sdk.sample.missionplanner.MissionSyncState;
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
    private final TextView route;
    private final TextView sync;
    private final TextView stats;
    private final TextView boundaryWarning;
    private final TextView home;
    private final TextView status;
    private final WaypointMissionOperator operator;
    private final MissionDraftLibrary library;
    private final PlanningBoundaryProfileLibrary boundaryProfiles;
    private final MissionRouteEditor routeEditor;
    private final MapLibreMissionMapView map;
    private WaypointMission loadedMission;
    private MissionDraft loadedDraft;
    private boolean uploaded;
    private boolean draftDirty;
    private PlanningBoundaryProfile activeBoundaryProfile;
    private boolean addMode;
    private int selectedWaypoint = -1;
    private CallbackMulticaster.Subscription hubSubscription;
    private boolean rthActive;
    private boolean flightStateAvailable;
    private boolean homeAvailable;
    private boolean missionListenerAttached;
    private Double latestAircraftLatitude;
    private Double latestAircraftLongitude;
    /** Last home coordinate positively acknowledged by FlightController.setHomeLocation. */
    private final ConfirmedDjiHomeState confirmedDjiHome = new ConfirmedDjiHomeState();
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

        @Override public void onExecutionStart() { post(() -> status.setText("Mission execution started.")); }

        @Override public void onExecutionFinish(dji.common.error.DJIError error) {
            post(() -> status.setText(error == null ? "Mission finished." : "Mission ended: " + error.getDescription()));
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
        route = findViewById(R.id.mission_mvp_route);
        sync = findViewById(R.id.mission_mvp_sync);
        stats = findViewById(R.id.mission_mvp_stats);
        boundaryWarning = findViewById(R.id.mission_mvp_boundary_warning);
        home = findViewById(R.id.mission_mvp_home);
        status = findViewById(R.id.mission_mvp_status);
        library = new MissionDraftLibrary(context);
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
        altitude.setOnFocusChangeListener((view, hasFocus) -> { if (!hasFocus) refreshRoute(); });
        speed.setOnFocusChangeListener((view, hasFocus) -> { if (!hasFocus) refreshRoute(); });
        map = new MapLibreMissionMapView(context);
        ((FrameLayout) findViewById(R.id.mission_mvp_map)).addView(map);
        map.setListener(new MapLibreMissionMapView.Listener() {
            @Override public void onMapTap(double latitude, double longitude) {
                if (!addMode) { status.setText("Select ADD WAYPOINT before tapping the map."); return; }
                routeEditor.append(new MissionWaypoint(latitude, longitude, null));
                selectedWaypoint = routeEditor.getDraft().getWaypoints().size() - 1;
                addMode = false;
                routeChanged(String.format(Locale.US, "Waypoint %d added at %.6f, %.6f.",
                        selectedWaypoint + 1, latitude, longitude));
            }
            @Override public void onWaypointTap(int index, float x, float y) {
                selectedWaypoint = index;
                showWaypointMenu(index, x, y);
            }
        });
        operator = MissionControl.getInstance().getWaypointMissionOperator();
        ((Button) findViewById(R.id.mission_mvp_add)).setOnClickListener(view -> {
            addMode = true;
            status.setText("Tap the map to append a waypoint.");
        });
        ((Button) findViewById(R.id.mission_mvp_center)).setOnClickListener(
                view -> centerOnCurrentLocation());
        findViewById(R.id.mission_mvp_library).setOnClickListener(
                view -> openMissionLibrary());
        ((Button) findViewById(R.id.mission_mvp_delete)).setOnClickListener(view -> deleteSelected());
        ((Button) findViewById(R.id.mission_mvp_save)).setOnClickListener(view -> saveDraft());
        ((Button) findViewById(R.id.mission_mvp_load)).setOnClickListener(view -> load());
        ((Button) findViewById(R.id.mission_mvp_upload)).setOnClickListener(view -> upload());
        ((Button) findViewById(R.id.mission_mvp_download)).setOnClickListener(view -> download());
        ((Button) findViewById(R.id.mission_mvp_boundaries)).setOnClickListener(view -> openBoundaryProfiles());
        ((Button) findViewById(R.id.mission_mvp_start)).setOnClickListener(view -> start());
        ((Button) findViewById(R.id.mission_mvp_pause)).setOnClickListener(view -> { SessionTimelineStore.operator("MISSION_PAUSE", "Pause pressed"); operator.pauseMission(result("Pause")); });
        ((Button) findViewById(R.id.mission_mvp_cancel)).setOnClickListener(view -> { SessionTimelineStore.operator("MISSION_CANCEL", "Cancel pressed"); operator.stopMission(result("Cancel")); });
        refreshRoute();
        attachHub();
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
            status.setText(error == null ? String.format(Locale.US, "Loaded %d waypoints. Upload is now available.", count)
                    : "Load failed: " + error.getDescription());
            uploaded = false;
            loadedMission = error == null ? candidate : null;
            loadedDraft = error == null ? draft : null;
            draftDirty = error != null;
            refreshRoute();
        } catch (IllegalArgumentException error) {
            loadedMission = null;
            loadedDraft = null;
            uploaded = false;
            status.setText("Invalid mission: " + error.getMessage());
        }
    }

    private void upload() {
        SessionTimelineStore.operator("MISSION_UPLOAD", "Upload pressed");
        MissionDraft draft = requireCurrentLoadedDraft();
        if (draft == null) return;
        String preflightError = firstPreflightError(draft);
        if (preflightError != null) { status.setText("Preflight: " + preflightError); return; }
        operator.uploadMission(error -> {
            uploaded = error == null;
            status.setText(error == null ? "Upload successful. Start is now available."
                    : "Upload failed: " + error.getDescription());
            post(this::renderMissionSync);
        });
    }

    private void download() {
        SessionTimelineStore.operator("MISSION_DOWNLOAD", "Download pressed");
        WaypointMissionState currentState = operator.getCurrentState();
        if (!WaypointMissionState.EXECUTING.equals(currentState)
                && !WaypointMissionState.EXECUTION_PAUSED.equals(currentState)) {
            status.setText("REMOTE UNKNOWN — DJI download is available only while executing or paused.");
            renderMissionSync();
            return;
        }
        status.setText("Downloading mission evidence from aircraft.");
        operator.downloadMission(error -> post(() -> {
            if (error != null) status.setText("Download failed: " + error.getDescription());
            else status.setText("Download request completed. Remote route content remains SDK-limited.");
            renderMissionSync();
        }));
    }

    private void start() {
        SessionTimelineStore.operator("MISSION_START", "Start pressed");
        if (rthActive) { status.setText("RTH is active; Start is unavailable."); return; }
        MissionDraft draft = requireCurrentLoadedDraft();
        if (draft == null || !uploaded) {
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
        operator.startMission(error -> {
            if (error != null) { status.setText("Start failed: " + error.getDescription()); return; }
            status.setText("Mission started. Returning to Operate.");
            if (getContext() instanceof Activity) ((Activity) getContext()).onBackPressed();
        });
    }

    private CommonCallbacks.CompletionCallback result(final String action) {
        return error -> status.setText(error == null ? action + " successful." : action + " failed: " + error.getDescription());
    }

    private MissionDraft loadInitialDraft() {
        java.util.List<MissionDraft> saved = library.loadAll();
        return saved.isEmpty() ? MissionDraft.empty() : saved.get(saved.size() - 1);
    }

    private MissionDraft currentDraft() {
        return new MissionDraft(name.getText().toString(), parse(altitude, "altitude"),
                parse(speed, "speed"), selectedFinishAction(),
                routeEditor.getDraft().getWaypoints(), routeEditor.getDraft().getPlanningReferenceLatitude(),
                routeEditor.getDraft().getPlanningReferenceLongitude());
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
        loadedMission = null;
        loadedDraft = null;
        uploaded = false;
        draftDirty = true;
        refreshRoute();
        status.setText(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void confirmSetDjiHome(int index) {
        MissionWaypoint waypoint = routeEditor.getDraft().getWaypoints().get(index);
        String coordinate = String.format(Locale.US, "%.6f, %.6f",
                waypoint.getLatitude(), waypoint.getLongitude());
        new AlertDialog.Builder(getContext())
                .setTitle("Set DJI Home Point?")
                .setMessage("Waypoint " + (index + 1) + "\n" + coordinate
                        + "\n\nThis changes the aircraft's real Return-to-Home location immediately."
                        + " It does not save, load, or upload this mission.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SET DJI HOME", (dialog, ignored) -> setDjiHome(waypoint, index))
                .show();
    }

    private void setDjiHome(MissionWaypoint waypoint, int index) {
        boolean sent = DjiDataHub.getInstance().setDjiHomeLocation(
                new LocationCoordinate2D(waypoint.getLatitude(), waypoint.getLongitude()), error ->
                        post(() -> {
                            if (error != null) {
                                status.setText("Set DJI Home failed: " + error.getDescription());
                                return;
                            }
                            // The command completion is authoritative. FlightControllerState can
                            // lag this state by one or more callbacks, so do not block LOAD/UPLOAD
                            // while waiting for that passive update.
                            confirmedDjiHome.confirm(waypoint.getLatitude(), waypoint.getLongitude());
                            renderConfirmedDjiHome();
                            status.setText("DJI Home set to waypoint " + (index + 1) + ".");
                        }));
        if (!sent) status.setText("DJI Home is unavailable: flight controller or coordinates not ready.");
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
        routeEditor.replace(draft);
        name.setText(draft.getName());
        altitude.setText(String.format(Locale.US, "%.1f", draft.getDefaultAltitudeMeters()));
        speed.setText(String.format(Locale.US, "%.1f", draft.getBaseSpeedMetersPerSecond()));
        finish.setSelection(draft.getFinishAction().ordinal());
        selectedWaypoint = -1;
        loadedMission = null;
        loadedDraft = null;
        uploaded = false;
        draftDirty = false;
        refreshRoute();
        status.setText("Opened " + draft.getName() + ". Edit, then LOAD when ready.");
    }

    private void centerOnCurrentLocation() {
        Location tabletLocation = latestTabletLocation();
        if (tabletLocation != null) {
            map.centerOn(tabletLocation.getLatitude(), tabletLocation.getLongitude(), 18.5d);
            status.setText("Centered on tablet location.");
        } else if (latestAircraftLatitude != null && latestAircraftLongitude != null) {
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
        for (String provider : manager.getProviders(true)) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (newest == null || candidate.getTime() > newest.getTime())) {
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
        String[] names = new String[profiles.size() + 1]; names[0] = "NEW LOCAL BOUNDARY";
        for (int index = 0; index < profiles.size(); index++) names[index + 1] = profiles.get(index).getName();
        new AlertDialog.Builder(getContext()).setTitle("LOCAL BOUNDARIES")
                .setItems(names, (dialog, index) -> { if (index == 0) chooseBoundaryType(); else selectBoundaryProfile(profiles.get(index - 1)); }).show();
    }

    private void chooseBoundaryType() {
        String[] types = {"EXCLUSION CIRCLE", "INCLUSION CIRCLE", "EXCLUSION POLYGON", "INCLUSION POLYGON"};
        new AlertDialog.Builder(getContext()).setTitle("NEW LOCAL BOUNDARY")
                .setItems(types, (dialog, index) -> editBoundary(types[index])).show();
    }

    private void editBoundary(String type) {
        LinearLayout panel = new LinearLayout(getContext()); panel.setOrientation(VERTICAL); panel.setPadding(dp(18), dp(6), dp(18), 0);
        EditText profileName = inspectorField("Local profile", android.text.InputType.TYPE_CLASS_TEXT);
        boolean circle = type.endsWith("CIRCLE");
        EditText geometry = inspectorField(circle ? "lat,lon,radius_m" : "lat,lon; lat,lon; lat,lon", android.text.InputType.TYPE_CLASS_TEXT);
        panel.addView(inspectorLabel("PROFILE NAME")); panel.addView(profileName); panel.addView(inspectorLabel(circle ? "CENTER / RADIUS" : "POLYGON VERTICES")); panel.addView(geometry);
        new AlertDialog.Builder(getContext()).setTitle(type).setView(panel).setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE LOCAL", (dialog, ignored) -> saveBoundary(type, profileName.getText().toString(), geometry.getText().toString())).show();
    }

    private void saveBoundary(String type, String name, String source) {
        try {
            boolean exclusion = type.startsWith("EXCLUSION"); PlanningBoundary.Kind kind = exclusion ? PlanningBoundary.Kind.EXCLUSION : PlanningBoundary.Kind.INCLUSION;
            PlanningBoundary boundary;
            if (type.endsWith("CIRCLE")) { String[] parts = source.split(","); if (parts.length != 3) throw new IllegalArgumentException("use lat,lon,radius_m");
                boundary = PlanningBoundary.circle(type, kind, Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()));
            } else { java.util.List<PlanningBoundary.Point> points = new java.util.ArrayList<>(); for (String pair : source.split(";")) { String[] parts = pair.split(","); if (parts.length != 2) throw new IllegalArgumentException("use lat,lon; lat,lon; lat,lon"); points.add(new PlanningBoundary.Point(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()))); }
                boundary = PlanningBoundary.polygon(type, kind, points); }
            PlanningBoundaryProfile profile = new PlanningBoundaryProfile(name, java.util.Collections.singletonList(boundary));
            if (!boundaryProfiles.save(profile)) throw new IllegalArgumentException("could not save profile");
            selectBoundaryProfile(profile); status.setText("Local profile saved. It is not DJI enforced.");
        } catch (RuntimeException error) { status.setText("Boundary not saved: " + error.getMessage()); }
    }

    private void selectBoundaryProfile(PlanningBoundaryProfile profile) {
        activeBoundaryProfile = profile;
        map.setPlanningBoundaries(profile.getBoundaries());
        renderBoundaryWarnings();
    }

    private void renderBoundaryWarnings() {
        if (boundaryWarning == null) return;
        if (activeBoundaryProfile == null) { boundaryWarning.setText("LOCAL PLANNING WARNING — NOT DJI ENFORCED"); return; }
        int count = BoundaryEvaluator.evaluate(routeEditor.getDraft(), activeBoundaryProfile.getBoundaries()).getWarningCount();
        boundaryWarning.setText(activeBoundaryProfile.getName() + " — " + count
                + " ROUTE WARNING" + (count == 1 ? "" : "S") + " · NOT DJI ENFORCED");
    }

    private void renderMissionSync() {
        if (sync == null) return;
        WaypointMissionState currentState = operator == null ? null : operator.getCurrentState();
        String stateName = currentState == null ? "" : currentState.getName();
        sync.setText(MissionSyncState.resolve(loadedMission != null && loadedDraft != null, uploaded,
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
        if (loadedMission == null || loadedDraft == null) {
            status.setText("Load a valid mission first.");
            return null;
        }
        final MissionDraft draft;
        try {
            draft = currentDraft();
        } catch (IllegalArgumentException error) {
            loadedMission = null;
            loadedDraft = null;
            uploaded = false;
            status.setText("Mission changed and is invalid: " + error.getMessage());
            return null;
        }
        if (!loadedDraft.equals(draft)) {
            loadedMission = null;
            loadedDraft = null;
            uploaded = false;
            status.setText("Mission changed. Load it again before upload or start.");
            return null;
        }
        return draft;
    }

    private void attachHub() {
        if (hubSubscription != null) return;
        hubSubscription = DjiDataHub.getInstance().addListener(new DjiDataHub.Listener() {
            @Override public void onFlightState(@NonNull FlightControllerState state) {
                post(() -> renderFlightState(state));
            }
        });
    }

    private void renderFlightState(FlightControllerState state) {
        flightStateAvailable = true;
        rthActive = state.isGoingHome();
        LocationCoordinate2D homeLocation = state.getHomeLocation();
        boolean reportedHomeValid = state.isHomeLocationSet() && homeLocation != null && homeLocation.isValid();
        if (reportedHomeValid) {
            confirmedDjiHome.reconcileReadback(true, homeLocation.getLatitude(),
                    homeLocation.getLongitude());
        }
        restoreConfirmedDjiHome();
        boolean confirmedHomeValid = confirmedDjiHome.isAvailable();
        boolean homeValid = reportedHomeValid || confirmedHomeValid;
        homeAvailable = homeValid;
        home.setText(rthActive ? "RTH — " + String.valueOf(state.getGoHomeExecutionState())
                : homeValid ? "HOME READY" : "HOME UNAVAILABLE");
        map.setDjiHome(homeValid ? confirmedDjiHome.getLatitude() : null,
                homeValid ? confirmedDjiHome.getLongitude() : null);
        LocationCoordinate3D aircraftLocation = state.getAircraftLocation();
        boolean aircraftValid = aircraftLocation != null && LocationCoordinate2D.isValid(
                aircraftLocation.getLatitude(), aircraftLocation.getLongitude());
        latestAircraftLatitude = aircraftValid ? aircraftLocation.getLatitude() : null;
        latestAircraftLongitude = aircraftValid ? aircraftLocation.getLongitude() : null;
        map.setAircraft(latestAircraftLatitude, latestAircraftLongitude);
    }

    private void restoreConfirmedDjiHome() {
        if (confirmedDjiHome.isAvailable()) return;
        LocationCoordinate2D confirmed = DjiDataHub.getInstance().getConfirmedDjiHomeLocation();
        if (confirmed == null || !confirmed.isValid()) return;
        confirmedDjiHome.confirm(confirmed.getLatitude(), confirmed.getLongitude());
    }

    private void renderConfirmedDjiHome() {
        restoreConfirmedDjiHome();
        boolean valid = confirmedDjiHome.isAvailable();
        if (!valid) return;
        homeAvailable = true;
        home.setText(rthActive ? "RTH — ACTIVE" : "HOME READY");
        map.setDjiHome(confirmedDjiHome.getLatitude(), confirmedDjiHome.getLongitude());
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
        FlightReadinessStore.getInstance().start();
        attachHub();
        renderConfirmedDjiHome();
        if (!missionListenerAttached) {
            operator.addListener(missionListener);
            missionListenerAttached = true;
        }
        renderMissionSync();
    }

    @Override protected void onDetachedFromWindow() {
        if (hubSubscription != null) hubSubscription.close();
        hubSubscription = null;
        if (missionListenerAttached) operator.removeListener(missionListener);
        missionListenerAttached = false;
        super.onDetachedFromWindow();
    }

    @Override public int getDescription() { return R.string.operate_menu_mission; }
    @NonNull @Override public String getHint() { return getClass().getSimpleName() + ".java"; }
}
