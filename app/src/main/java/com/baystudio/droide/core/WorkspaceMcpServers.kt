package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Projects enabled workspace servers into a newly started external agent session. */
internal object WorkspaceMcpServers {
    suspend fun project(
        projectRoot: File,
        mapper: WorkspacePathMapper,
        processHost: StdioProcessHost,
    ): JsonArray {
        val servers = McpManager(projectRoot, AgentPluginSource.EMPTY).list().filter { it.enabled }
        val executables = mutableMapOf<String, String?>()
        suspend fun resolve(command: String): String? {
            if (!executables.containsKey(command)) {
                executables[command] = runSuspendCatching { processHost.resolveExecutable(command) }.getOrNull()
            }
            return executables[command]
        }
        // Local CLI processes share the workspace-bound Unix socket. An ADB guest does not.
        val binding = if (processHost.executionScope == ProcessExecutionScope.LOCAL_LINUX_ARM64)
            ExternalIdeToolRegistry.forProject(projectRoot) else null
        val cli = binding?.let { resolve("droide") }
        val shell = cli?.let { resolve("sh") }
        return buildJsonArray {
            if (binding != null && cli != null && shell != null) {
                val existing = servers.mapTo(hashSetOf()) { it.name.take(80) }
                var name = "droide-ide"
                var suffix = 2
                while (name in existing) name = "droide-ide-${suffix++}"
                add(ExternalIdeProtocol.configuration(binding, cli, shell, name))
            }
            var projected = 0
            for (server in servers) {
                if (projected == MAX_EXTERNAL_MCP_SERVERS) break
                val executable = resolve(server.argv.first()) ?: continue
                val args = server.argv.drop(1).mapNotNull { mapArgument(projectRoot, it, mapper) }
                if (args.size != server.argv.size - 1) continue
                if (runCatching { ProcessSecurityPolicy.validateArgv(listOf(executable) + args) }.isFailure) continue
                add(buildJsonObject {
                    put("name", server.name.take(80))
                    put("command", executable)
                    put("args", JsonArray(args.map(::JsonPrimitive)))
                    put("env", JsonArray(emptyList()))
                })
                projected++
            }
        }
    }

    private fun mapArgument(projectRoot: File, raw: String, mapper: WorkspacePathMapper): String? {
        if (raw.length > 8_000 || raw.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
        if (raw.contains(".droide/.runtime-agent-plugins") || raw.startsWith(".droide/")) return null
        val root = projectRoot.canonicalFile
        val absolute = File(raw)
        if (absolute.isAbsolute) {
            val canonical = runCatching { absolute.canonicalFile }.getOrNull() ?: return null
            if (!PathSecurity.contains(root, canonical)) return null
            if (!visible(canonical.relativeTo(root).invariantSeparatorsPath)) return null
            return mapper.localToRemote(canonical)
        }
        val candidate = runCatching { PathSecurity.resolveWithin(root, raw) }.getOrNull()
        if (candidate?.exists() == true) {
            val canonical = candidate.canonicalFile
            if (!visible(canonical.relativeTo(root).invariantSeparatorsPath)) return null
            return mapper.localToRemote(canonical)
        }
        return raw
    }

    private fun visible(relativePath: String): Boolean =
        relativePath.replace('\\', '/').substringBefore('/') !in setOf(".git", ".gradle", "build", ".droide", "node_modules")

    private const val MAX_EXTERNAL_MCP_SERVERS = 16
}
