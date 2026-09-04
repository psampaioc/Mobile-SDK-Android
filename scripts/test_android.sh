#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/android_env.sh
source "$script_dir/lib/android_env.sh"
"$script_dir/verify_dji_callback_ownership.sh"
args=()
if [[ "${1:-}" == "--offline" ]]; then
    args+=(--offline)
fi

"$script_dir/build_android.sh" "${args[@]}" :app:testDebugUnitTest

# The legacy sample has no JUnit dependency. TransportPrimitivesTest is a
# dependency-free JVM test executable, so Gradle compiles it but reports zero
# JUnit tests. Execute it explicitly when present.
test_source="$sample_root/app/src/test/java/com/dji/sdk/sample/transport/video/TransportPrimitivesTest.java"
test_class="$sample_root/app/build/intermediates/javac/debugUnitTest/classes/com/dji/sdk/sample/transport/video/TransportPrimitivesTest.class"
if [[ -f "$test_source" ]]; then
    prepare_android_build_env
    [[ -f "$test_class" ]] || die "Gradle did not compile TransportPrimitivesTest"
    classpath="$sample_root/app/build/intermediates/javac/debug/classes:$sample_root/app/build/intermediates/javac/debugUnitTest/classes"
    "$JAVA_HOME/bin/java" -cp "$classpath" com.dji.sdk.sample.transport.video.TransportPrimitivesTest
fi

telemetry_suite="$sample_root/app/build/intermediates/javac/debugUnitTest/classes/com/dji/sdk/sample/transport/diagnostics/TelemetryDiagnosticsUnitTestSuite.class"
if [[ -f "$telemetry_suite" ]]; then
    "$JAVA_HOME/bin/java" -cp "$classpath" com.dji.sdk.sample.transport.diagnostics.TelemetryDiagnosticsUnitTestSuite
else
    die "Gradle did not compile TelemetryDiagnosticsUnitTestSuite"
fi

mission_planner_suite="$sample_root/app/build/intermediates/javac/debugUnitTest/classes/com/dji/sdk/sample/missionplanner/MissionPlannerUnitTestSuite.class"
if [[ -f "$mission_planner_suite" ]]; then
    "$JAVA_HOME/bin/java" -cp "$classpath" com.dji.sdk.sample.missionplanner.MissionPlannerUnitTestSuite
fi

flight_readiness_suite="$sample_root/app/build/intermediates/javac/debugUnitTest/classes/com/dji/sdk/sample/operate/FlightReadinessModelTest.class"
if [[ -f "$flight_readiness_suite" ]]; then
    "$JAVA_HOME/bin/java" -cp "$classpath" com.dji.sdk.sample.operate.FlightReadinessModelTest
else
    die "Gradle did not compile FlightReadinessModelTest"
fi

flight_readiness_session_suite="$sample_root/app/build/intermediates/javac/debugUnitTest/classes/com/dji/sdk/sample/operate/FlightReadinessSessionTest.class"
if [[ -f "$flight_readiness_session_suite" ]]; then
    "$JAVA_HOME/bin/java" -cp "$classpath" com.dji.sdk.sample.operate.FlightReadinessSessionTest
else
    die "Gradle did not compile FlightReadinessSessionTest"
fi

flight_safety_configuration_suite="$sample_root/app/build/intermediates/javac/debugUnitTest/classes/com/dji/sdk/sample/operate/FlightSafetyConfigurationTest.class"
if [[ -f "$flight_safety_configuration_suite" ]]; then
    "$JAVA_HOME/bin/java" -cp "$classpath" com.dji.sdk.sample.operate.FlightSafetyConfigurationTest
else
    die "Gradle did not compile FlightSafetyConfigurationTest"
fi
