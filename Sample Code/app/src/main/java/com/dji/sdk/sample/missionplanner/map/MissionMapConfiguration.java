package com.dji.sdk.sample.missionplanner.map;

/**
 * Interactive default map style. It deliberately has no offline downloader or API credential.
 * Deployments that need satellite imagery or offline regions replace this style through the
 * provider adapter after their provider licence and cache policy are approved.
 */
public final class MissionMapConfiguration {
    private MissionMapConfiguration() { }

    public static final String INTERACTIVE_OSM_STYLE_JSON = "{"
            + "\"version\":8,"
            + "\"sources\":{\"osm\":{\"type\":\"raster\","
            + "\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],"
            + "\"tileSize\":256,\"attribution\":\"© OpenStreetMap contributors\"}},"
            + "\"layers\":[{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\","
            + "\"minzoom\":0,\"maxzoom\":19}]"
            + "}";
}
