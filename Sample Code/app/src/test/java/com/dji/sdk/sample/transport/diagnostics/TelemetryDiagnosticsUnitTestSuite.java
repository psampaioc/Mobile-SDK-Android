package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.model.TelemetryEventJsonTest;
import com.dji.sdk.sample.transport.network.JsonWireEncoderTest;
import com.dji.sdk.sample.transport.network.ClockProtocolTest;
import com.dji.sdk.sample.transport.network.VideoSocketDiagnosticsTest;
import com.dji.sdk.sample.transport.network.RtpSequenceWindowMetricsTest;
import com.dji.sdk.sample.transport.network.TransportConfigTest;
import com.dji.sdk.sample.djihub.CallbackMulticasterTest;
import com.dji.sdk.sample.djihub.PrimaryVideoFanoutTest;
import com.dji.sdk.sample.operate.OperateStatusModelTest;
import com.dji.sdk.sample.operate.OperateStatusDetailStateTest;
import com.dji.sdk.sample.operate.MissionLocationFreshnessTest;
import com.dji.sdk.sample.djihub.DjiHomeStateTest;
import com.dji.sdk.sample.transport.TransportEndpointPolicyTest;
import com.dji.sdk.sample.transport.PrimaryTransportMonitorTest;
import com.dji.sdk.sample.transport.TransportEgressMetricsTest;

/** Dependency-free JVM test entrypoint because this legacy sample has no test dependency configured. */
public final class TelemetryDiagnosticsUnitTestSuite {
    private TelemetryDiagnosticsUnitTestSuite() { }

    public static void main(String[] args) throws Exception {
        CallbackMulticasterTest.run();
        PrimaryVideoFanoutTest.run();
        OperateStatusModelTest.run();
        OperateStatusDetailStateTest.run();
        DjiHomeStateTest.run();
        TransportEndpointPolicyTest.run();
        PrimaryTransportMonitorTest.run();
        TransportEgressMetricsTest.run();
        CallbackRateTrackerTest.run();
        TelemetryEventJsonTest.run();
        AsyncNdjsonDiagnosticWriterTest.run();
        SessionTimelineDiagnosticTest.run();
        JsonWireEncoderTest.run();
        ClockProtocolTest.run();
        VideoSocketDiagnosticsTest.run();
        RtpSequenceWindowMetricsTest.run();
        TransportConfigTest.run();
        MissionLocationFreshnessTest.run();
        System.out.println("Telemetry diagnostics unit tests passed");
    }
}
