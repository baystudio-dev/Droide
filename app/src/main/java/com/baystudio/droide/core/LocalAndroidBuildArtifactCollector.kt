package com.baystudio.droide.core

import java.io.File

internal object LocalAndroidBuildArtifactCollector {
    fun collect(projectRoot: File, task: String, completedOutputRoots: List<File>): List<File> {
        val query = AndroidBuildArtifactPolicy.queryFor(task) ?: return emptyList()
        val root = projectRoot.canonicalFile
        require(root.isDirectory) { "Invalid local Android workspace" }
        require(completedOutputRoots.size <= 2048) { "Too many Gradle output roots" }
        val module = query.modulePath?.let { PathSecurity.resolveWithin(root, it) } ?: root
        require(module.isDirectory) { "Android module is unavailable" }

        val matches = linkedSetOf<File>()
        val visited = hashSetOf<File>()
        for (output in completedOutputRoots) {
            require(!PathSecurity.isSymbolicLink(output) && PathSecurity.contains(root, output)) { "Gradle output escaped workspace" }
            val start = output.canonicalFile
            val relative = start.relativeTo(root).invariantSeparatorsPath
            if (!start.toPath().startsWith(module.toPath()) || "/build/outputs/" !in "/$relative/") continue
            if (relative.split('/').any { it in SKIP_DIRS }) continue
            start.walkTopDown().onEnter { directory ->
                !PathSecurity.isSymbolicLink(directory) && directory.name !in SKIP_DIRS &&
                    directory.relativeTo(module).toPath().nameCount <= MAX_DEPTH && visited.add(directory)
            }.forEach { file ->
                if (PathSecurity.isSymbolicLink(file) || !file.isFile) return@forEach
                if (AndroidBuildArtifactPolicy.matchesRelativePath(query, file.relativeTo(root).invariantSeparatorsPath)) {
                    require(file.canonicalFile == file && PathSecurity.contains(root, file)) { "Build artifact escaped its declared directory" }
                    require(file.length() > 0L) { "Build artifact is empty: ${file.name}" }
                    matches += file
                    require(matches.size <= MAX_ARTIFACTS) { "Too many build artifacts; narrow the Gradle task" }
                }
            }
        }
        return matches.toList()
    }

    private val SKIP_DIRS = setOf(".git", ".gradle", ".idea", "node_modules", ".droide")
    private const val MAX_DEPTH = 12
    private const val MAX_ARTIFACTS = 40
}
