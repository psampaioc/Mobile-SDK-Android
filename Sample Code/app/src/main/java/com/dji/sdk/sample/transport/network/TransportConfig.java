package com.dji.sdk.sample.transport.network;

/** Immutable UDP endpoint configuration for one explicitly started transport session. */
public final class TransportConfig {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_TELEMETRY_PORT = 5500;
    public static final int DEFAULT_METADATA_PORT = 5501;
    public static final int DEFAULT_CLOCK_PORT = 5502;
    public static final int DEFAULT_PRIMARY_RTP_PORT = 5600;
    public static final int DEFAULT_SECONDARY_RTP_PORT = 5610;
    public static final int MAX_JSON_DATAGRAM_BYTES = 1200;

    public final String edgeHost;
    public final int telemetryPort;
    public final int metadataPort;
    public final int clockPort;
    public final int primaryRtpPort;
    public final int secondaryRtpPort;

    public TransportConfig(String edgeHost) {
        this(edgeHost, DEFAULT_TELEMETRY_PORT, DEFAULT_METADATA_PORT, DEFAULT_CLOCK_PORT,
                DEFAULT_PRIMARY_RTP_PORT, DEFAULT_SECONDARY_RTP_PORT);
    }

    public TransportConfig(String edgeHost, int telemetryPort, int metadataPort, int clockPort,
            int primaryRtpPort, int secondaryRtpPort) {
        if (edgeHost == null || edgeHost.trim().isEmpty()) {
            throw new IllegalArgumentException("edge host is required");
        }
        this.edgeHost = edgeHost.trim();
        this.telemetryPort = port(telemetryPort);
        this.metadataPort = port(metadataPort);
        this.clockPort = port(clockPort);
        this.primaryRtpPort = port(primaryRtpPort);
        this.secondaryRtpPort = port(secondaryRtpPort);
    }

    private static int port(int value) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("invalid UDP port");
        return value;
    }
}
