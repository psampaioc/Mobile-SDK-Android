package com.dji.sdk.sample.missionplanner.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dji.sdk.sample.missionplanner.MissionDraft;
import com.dji.sdk.sample.missionplanner.MissionWaypoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// MapLibre 9 retains the historical Mapbox package namespace.
import com.mapbox.mapboxsdk.annotations.Marker;
import com.mapbox.mapboxsdk.annotations.MarkerOptions;
import com.mapbox.mapboxsdk.annotations.PolylineOptions;
import com.mapbox.mapboxsdk.annotations.Icon;
import com.mapbox.mapboxsdk.annotations.IconFactory;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.WellKnownTileServer;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.maps.Style;

/** MapLibre-specific rendering boundary for mission geometry and selection. */
public final class MapLibreMissionMapView extends FrameLayout {
    public interface Listener {
        void onMapTap(double latitude, double longitude);
        /** Position is relative to this map view, allowing the editor to open in place. */
        void onWaypointTap(int index, float x, float y);
    }

    private final MapView mapView;
    private final Map<Marker, Integer> waypointMarkers = new HashMap<>();
    private final Map<Integer, Icon> waypointIcons = new HashMap<>();
    private MapboxMap map;
    private MissionDraft pendingDraft;
    private Listener listener;
    private LatLng djiHome;
    private LatLng aircraft;

    public MapLibreMissionMapView(@NonNull Context context) {
        super(context);
        // MapLibre 9 keeps Mapbox's initialization contract even when the map
        // uses our public OSM style and no Mapbox access token.
        // The one-argument initializer leaves TileServerOptions null in
        // MapLibre 9.5. That reaches FileSource's JNI setup as a null object
        // and aborts the process on Android 16. We use the MapLibre server
        // profile because this view supplies its own public OSM style.
        Mapbox.getInstance(context.getApplicationContext(), null, WellKnownTileServer.MapLibre);
        mapView = new MapView(context);
        addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapView.onCreate(null);
        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override public void onMapReady(@NonNull MapboxMap readyMap) {
                map = readyMap;
                map.addOnMapClickListener(point -> {
                    if (listener != null) listener.onMapTap(point.getLatitude(), point.getLongitude());
                    return true;
                });
                map.setOnMarkerClickListener(marker -> {
                    Integer index = waypointMarkers.get(marker);
                    if (index != null && listener != null) {
                        PointF point = map.getProjection().toScreenLocation(marker.getPosition());
                        listener.onWaypointTap(index, point.x, point.y);
                    }
                    return index != null;
                });
                map.setStyle(new Style.Builder().fromJson(MissionMapConfiguration.INTERACTIVE_OSM_STYLE_JSON),
                        style -> renderPending());
            }
        });
    }

    public void setListener(@Nullable Listener nextListener) { listener = nextListener; }

    public void render(@NonNull MissionDraft draft) {
        pendingDraft = draft;
        renderPending();
    }

    public void setDjiHome(@Nullable Double latitude, @Nullable Double longitude) {
        djiHome = latitude == null || longitude == null ? null : new LatLng(latitude, longitude);
        renderPending();
    }

    public void setAircraft(@Nullable Double latitude, @Nullable Double longitude) {
        aircraft = latitude == null || longitude == null ? null : new LatLng(latitude, longitude);
        renderPending();
    }

    public void centerOn(double latitude, double longitude, double zoom) {
        if (map != null) map.setCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(latitude, longitude)).zoom(zoom).build());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mapView.onStart();
    }

    @Override protected void onDetachedFromWindow() {
        mapView.onStop();
        super.onDetachedFromWindow();
    }

    private void renderPending() {
        if (map == null || pendingDraft == null || map.getStyle() == null) return;
        map.clear();
        waypointMarkers.clear();
        if (djiHome != null) map.addMarker(new MarkerOptions().position(djiHome).title("DJI HOME"));
        if (aircraft != null) map.addMarker(new MarkerOptions().position(aircraft).title("AIRCRAFT"));
        List<LatLng> route = new ArrayList<>();
        int number = 1;
        for (MissionWaypoint waypoint : pendingDraft.getWaypoints()) {
            LatLng point = new LatLng(waypoint.getLatitude(), waypoint.getLongitude());
            route.add(point);
            Marker marker = map.addMarker(new MarkerOptions().position(point)
                    .title("WP " + number).icon(waypointIcon(number)));
            waypointMarkers.put(marker, number - 1);
            number++;
        }
        if (route.size() > 1) map.addPolyline(new PolylineOptions().addAll(route)
                .color(0xFF2A9D4B).width(4f));
    }

    /** Creates a stable, numbered marker so the visible order equals the mission order. */
    @NonNull
    private Icon waypointIcon(int number) {
        Icon cached = waypointIcons.get(number);
        if (cached != null) return cached;
        int size = dp(34);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(Color.rgb(42, 157, 75));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), circle);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(dp(number > 9 ? 11 : 13));
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(String.valueOf(number), size / 2f,
                size / 2f - (metrics.ascent + metrics.descent) / 2f, text);
        Icon icon = IconFactory.getInstance(getContext()).fromBitmap(bitmap);
        waypointIcons.put(number, icon);
        return icon;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
