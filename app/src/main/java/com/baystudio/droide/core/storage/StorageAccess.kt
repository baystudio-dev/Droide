package com.baystudio.droide.core.storage

import com.baystudio.droide.core.TerminalLaunchSpec
import java.io.File

enum class StorageLaunchPurpose { PRIVATE, USER_TERMINAL, USER_TASK, AGENT_CLI }

interface StorageAccessSource {
    fun refresh(): StorageAccessSnapshot
    fun requireCurrent(snapshot: StorageAccessSnapshot)
}

data class SharedStorageVolume(
    val path: String,
    val label: String,
    val primary: Boolean,
    val writable: Boolean,
)

data class StorageAccessSnapshot(
    val supported: Boolean,
    val enabled: Boolean,
    val granted: Boolean,
    val volumes: List<SharedStorageVolume>,
    val generation: Long = 0,
) {
    val available: Boolean get() = supported && enabled && granted

    fun volumeFor(path: String): SharedStorageVolume? = if (!available) null else volumes
        .filter { path == it.path || path.startsWith(it.path + "/") }
        .maxByOrNull { it.path.length }
}

data class StorageLaunchAccess(
    val purpose: StorageLaunchPurpose = StorageLaunchPurpose.PRIVATE,
    val generation: Long = 0,
    val paths: List<String> = emptyList(),
)

object ExternalMountPlan {
    fun validVolumePath(path: String): Boolean = path.startsWith("/storage/") &&
        path.length > "/storage/".length && path.none { it == ':' || it == '\\' || it.code < 32 } &&
        path.split('/').none { it == "." || it == ".." } && !path.endsWith('/')

    fun apply(spec: TerminalLaunchSpec, snapshot: StorageAccessSnapshot,
              purpose: StorageLaunchPurpose): TerminalLaunchSpec {
        require(spec.storageAccess.paths.isEmpty()) { "Storage mounts are already assigned" }
        val volumes = if (purpose == StorageLaunchPurpose.PRIVATE || !snapshot.available) emptyList()
            else snapshot.volumes.filter { validVolumePath(it.path) }.distinctBy { it.path }
        val access = StorageLaunchAccess(purpose, snapshot.generation, volumes.map { it.path })
        if (volumes.isEmpty()) return spec.copy(storageAccess = access)
        require(spec.shell == "/bin/bash" && "-r" in spec.prefix) { "Shared storage requires a Linux launch" }
        val insertion = spec.prefix.indexOf("-w")
        require(insertion >= 0) { "Linux launch has no working directory" }
        val bindings = buildList {
            volumes.forEach { add("-b"); add("${it.path}:${it.path}") }
            volumes.singleOrNull { it.primary }?.let { add("-b"); add("${it.path}:/sdcard") }
        }
        return spec.copy(prefix = spec.prefix.take(insertion) + bindings + spec.prefix.drop(insertion),
            storageAccess = access)
    }

    fun resolve(snapshot: StorageAccessSnapshot, path: String): File {
        check(snapshot.available) { "Enable device storage in Settings > Files and grant Android file access" }
        require(path.startsWith('/') && path.length <= 4096 && path.none { it.code < 32 }) { "Use an absolute device path" }
        val primary = snapshot.volumes.singleOrNull { it.primary }
        val expanded = if (path == "/sdcard" || path.startsWith("/sdcard/")) {
            requireNotNull(primary) { "Primary shared storage is unavailable" }.path + path.removePrefix("/sdcard")
        } else path
        val candidate = File(expanded)
        val volume = requireNotNull(snapshot.volumeFor(candidate.absolutePath)) { "Path is outside available shared storage" }
        require(File(volume.path).isAbsolute && volume.path != "/") { "Invalid storage volume" }
        val canonical = candidate.canonicalFile
        require(snapshot.volumeFor(canonical.path)?.path == volume.path) { "Path escaped its storage volume" }
        return canonical
    }
}
