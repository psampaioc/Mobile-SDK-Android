package com.dji.sdk.sample.transport.diagnostics;

import com.dji.sdk.sample.transport.model.TelemetryEventJsonTest;
import com.dji.sdk.sample.transport.network.JsonWireEncoderTest;
import com.dji.sdk.sample.transport.network.ClockProtocolTest;
import com.dji.sdk.sample.djihub.CallbackMulticasterTest;
import com.dji.sdk.sample.operate.OperateStatusModelTest;
import com.dji.sdk.sample.operate.ConfirmedDjiHomeStateTest;
import com.dji.sdk.sample.transport.TransportEndpointPolicyTest;

/** Dependency-free JVM test entrypoint because this legacy sample has no test dependency configured. */
public final class TelemetryDiagnosticsUnitTestSuite {
    private TelemetryDiagnosticsUnitTestSuite() { }

    public static void main(String[] args) throws Exception {
        CallbackMulticasterTest.run();
        OperateStatusModelTest.run();
        ConfirmedDjiHomeStateTest.run();
        TransportEndpointPolicyTest.run();
        CallbackRateTrackerTest.run();
        TelemetryEventJsonTest.run();
        AsyncNdjsonDiagnosticWriterTest.run();
        JsonWireEncoderTest.run();
        ClockProtocolTest.run();
        System.out.println("Telemetry diagnostics unit tests passed");
    }
}
