#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 /absolute/path/to/gradle /absolute/path/to/host-products" >&2
    exit 2
fi
gradle_executable=$1
product_directory=$2
cd "$(dirname "$0")/.."

# Keep the normal test source set intact so test fixtures also have to compile.
suites=(
    core.TypedChatCompactorTest
    core.OwnedChatIngressTest
    core.OwnedChatTransportTest
    core.ProviderWholePipelineCapacityTest
    core.ProviderPayloadLimitsTest
    core.ProviderEventIdleTest
    core.StreamingRetentionTest
    core.WorkspaceSearchReaderTest
    core.AcpTerminalUnicodeIndependentF152Test
    core.TrustedArtifactIntegrityRecoveryTest
    core.WorkspaceSourceToolsTest
    core.TerminalOutputRecordingTest
    core.SafUnchangedRefreshProofTest
    ui.EditorLiteralReplacementTest
    ui.SearchTextStateSaverTest
    ui.AgentExitDraftRecoveryR64Test
    ui.ExternalPendingRecoveryTest
)
# These fixtures exercise actual compilers and use Assume when invoked independently.
# The release gate must run them even on a freshly provisioned runner.
: "${DROIDE_TEST_TYPESCRIPT_MODULE:?Set the TypeScript package directory}"
: "${DROIDE_TEST_GO_BIN:?Set the Go bin directory}"
test -f "$DROIDE_TEST_TYPESCRIPT_MODULE/package.json"
test -x "$DROIDE_TEST_GO_BIN/go"
command -v node >/dev/null

# Host-side file publication uses the same JNI implementation with Linux headers.
# Compile in the host-products directory, outside of the APK packaging inputs.
host_jni="$product_directory/test-jni"
mkdir -p "$host_jni"
cc -O2 -fPIC -shared -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    app/src/main/cpp/droide_files.c -o "$host_jni/libdroide_files.so"
export LD_LIBRARY_PATH="$host_jni${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

framework_args=()
if [[ -n "${DROIDE_TEST_ROBOLECTRIC_DIR:-}" ]]; then
    framework_args+=("-Ddroide.robolectric.dir=$DROIDE_TEST_ROBOLECTRIC_DIR")
fi

selectors=()
for suite in "${suites[@]}"; do
    selectors+=(--tests "com.baystudio.droide.$suite")
done

exec "$gradle_executable" --no-daemon --no-build-cache --no-configuration-cache \
    --dependency-verification=strict --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx4100m -XX:MaxMetaspaceSize=700m -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process -Pandroid.builder.sdkDownload=false \
    -PDROIDE_HOST_JVM_TESTS_ONLY=true -PDROIDE_FDROID_BUILD=true -PDROIDE_DEVICE_STORAGE=true \
    "-PDROIDE_FDROID_TERMINAL_DIR=$product_directory/terminal" \
    "-PDROIDE_FDROID_JGIT_DIR=$product_directory/jgit" \
    "${framework_args[@]}" -I tools/host-regression.init.gradle :app:testDebugUnitTest "${selectors[@]}"
