package com.baystudio.droide.core


internal object LocalAndroidBuildCommand {
    fun create(
        task: String,
        snapshot: LocalAndroidToolchainDiscovery.Snapshot,
        workers: Int,
        useBuildCache: Boolean,
        evidence: LocalAndroidBuildEvidence.Session? = null,
        legacyEvidence: Boolean = false,
    ): String {
        GradleTaskPath.parse(task)
        require(workers in 1..32)
        val q = LocalExecutionSubstrate::shellQuote
        val managedPathPrefix = listOf(
            "${snapshot.javaHome}/bin",
            "${snapshot.sdkRoot}/platform-tools",
            "${snapshot.sdkRoot}/cmdline-tools/latest/bin",
        ).joinToString(":")
        return buildString {
            append("set -eu; ")
            append("export JAVA_HOME=").append(q(snapshot.javaHome)).append("; ")
            append("export ANDROID_HOME=").append(q(snapshot.sdkRoot)).append("; ")
            // Some older Android plugins still consult this deprecated variable; keep it identical.
            append("export ANDROID_SDK_ROOT=").append(q(snapshot.sdkRoot)).append("; ")
            append("export GRADLE_USER_HOME=/root/.gradle; export TMPDIR=/tmp; ")
            append("export PATH=").append(q(managedPathPrefix)).append(":\"\$PATH\"; ")
            evidence?.let { session ->
                val manifest = session.manifest.absolutePath
                require(listOf(manifest, session.initScript.absolutePath).all { path -> path.none { it.code < 32 || it.code == 127 } }) { "Invalid Gradle evidence path" }
                append("export DROIDE_BUILD_OUTPUT_MANIFEST=").append(q(manifest)).append("; ")
            }
            append("sh ./gradlew ").append(q(task))
            append(" --console=plain --no-daemon --max-workers=").append(workers)
            evidence?.let { session ->
                append(" --init-script ").append(q(session.initScript.absolutePath))
                if (legacyEvidence) append(" -Dorg.gradle.configuration-cache=false -Dorg.gradle.unsafe.configuration-cache=false")
            }
            append(" -Dorg.gradle.vfs.watch=false")
            if (useBuildCache) append(" --build-cache") else append(" --no-build-cache")
            append(" -Pandroid.aapt2FromMavenOverride=").append(q(snapshot.aapt2Path))
        }
    }
}
