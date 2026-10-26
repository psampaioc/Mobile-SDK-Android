package com.dji.sdk.sample.transport.network;

import java.net.InetAddress;

/** Immutable UDP endpoint configuration for one explicitly started transport session. */
public final class TransportConfig {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_TELEMETRY_PORT = 5500;
    public static final int DEFAULT_METADATA_PORT = 5501;
    public static final int DEFAULT_CLOCK_PORT = 5502;
    public static final int DEFAULT_PRIMARY_RTP_PORT = 5600;
    public static final int MAX_JSON_DATAGRAM_BYTES = 1200;

    public final String edgeHost;
    public final int telemetryPort;
    public final int metadataPort;
    public final int clockPort;
    public final int primaryRtpPort;

    public TransportConfig(String edgeHost) {
        this(edgeHost, DEFAULT_TELEMETRY_PORT, DEFAULT_METADATA_PORT, DEFAULT_CLOCK_PORT,
                DEFAULT_PRIMARY_RTP_PORT);
    }

    public TransportConfig(String edgeHost, int telemetryPort, int metadataPort, int clockPort,
            int primaryRtpPort) {
        if (edgeHost == null || edgeHost.trim().isEmpty()) {
            throw new IllegalArgumentException("edge host is required");
        }
        this.edgeHost = edgeHost.trim();
        if (!isLiteralAddress(this.edgeHost)) {
            throw new IllegalArgumentException("Edge endpoint must be a literal IPv4 or IPv6 address");
        }
        this.telemetryPort = port(telemetryPort);
        this.metadataPort = port(metadataPort);
        this.clockPort = port(clockPort);
        this.primaryRtpPort = port(primaryRtpPort);
    }

    private static int port(int value) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException("invalid UDP port");
        return value;
    }

    private static boolean isLiteralAddress(String host) {
        if (host.indexOf(':') >= 0) {
            if (host.indexOf('%') >= 0 || host.startsWith("[") || host.endsWith("]")) return false;
            try { return InetAddress.getByName(host) != null; }
            catch (Exception ignored) { return false; }
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            int value = 0;
            for (int index = 0; index < octet.length(); index++) {
                char digit = octet.charAt(index);
                if (digit < '0' || digit > '9') return false;
                value = value * 10 + digit - '0';
            }
            if (value > 255) return false;
        }
        return true;
    }
}
