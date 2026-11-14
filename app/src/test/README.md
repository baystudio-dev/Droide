# Application regressions

Packages mirror the production owners under `com.baystudio.droide`: core protocols/storage/processes, UI interaction and application lifecycle. Tests in this directory run on a host JVM; Android instrumentation belongs in `app/src/androidTest`.

Preserve regressions for retained drafts, publication conflicts, cancellation, stale owners, process cleanup, permission scopes and protocol failures. Older revision identifiers in class names remain stable where build selections and frozen manifests reference them. Comments describe fixture scope; execution status belongs in a test report.

Fixtures must not start real providers, publish content, send support messages or access user credentials. The explicitly separate public-internet probe is opt-in and is not part of offline JUnit execution. Host Android shadows and test JNI implementations must not be described as physical-device coverage.

See [testing](../../../docs/TESTING.md) for build-lane requirements, evidence and device acceptance.
