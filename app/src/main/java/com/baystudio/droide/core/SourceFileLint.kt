package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SourceFileLint {
    private enum class Check(val tool: String, vararg val extensions: String) {
        PYTHON("ruff", "py", "pyi"), JAVASCRIPT("eslint", "js", "jsx", "mjs", "cjs"),
        TYPESCRIPT("tsc", "ts", "tsx", "mts", "cts"), KOTLIN("ktlint", "kt", "kts"), GO("go", "go"),
        C("gcc", "c", "h"), CPP("g++", "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++"),
        SHELL("shellcheck", "sh", "bash"), ZSH("zsh", "zsh"), PHP("php", "php"), RUBY("ruby", "rb"), DART("dart", "dart"),
    }

    private fun check(path: String): Check? {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext == "C" || ext == "H") return Check.CPP
        return Check.entries.firstOrNull { ext.lowercase() in it.extensions }
    }

    fun supports(path: String): Boolean = check(path) != null

    private fun typeScriptArgs(root: File, file: File, source: String): List<String> {
        var directory = requireNotNull(file.parentFile)
        while (true) {
            val config = File(directory, "tsconfig.json")
            if (config.isFile && PathSecurity.contains(root, config)) {
                return listOf("--noEmit", "--project", "./" + config.relativeTo(root).invariantSeparatorsPath)
            }
            if (directory == root) break
            directory = requireNotNull(directory.parentFile)
        }
        return listOf("--noEmit", "--target", "ES2022", "--module", "NodeNext", "--jsx", "preserve", source)
    }

    fun command(root: File, path: String): Pair<String, List<String>> {
        val file = PathSecurity.resolveWithin(root, path)
        require(file.isFile) { "Source file is unavailable: $path" }
        val relative = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        val source = "./$relative"
        val check = check(relative) ?: error("No built-in file check for $relative")
        val args = when (check) {
            Check.PYTHON -> listOf("check", "--no-fix", "--no-cache", "--", source)
            Check.JAVASCRIPT -> listOf("--no-fix", "--", source)
            Check.TYPESCRIPT -> typeScriptArgs(root.canonicalFile, file, source)
            Check.KOTLIN -> listOf(source)
            Check.GO -> listOf("vet", source)
            Check.C, Check.CPP -> listOf("-fsyntax-only", "-Wall", "-Wextra", "-x",
                if (check == Check.C) "c" else "c++", if (check == Check.C) "-std=c17" else "-std=c++17", source)
            Check.SHELL -> listOf("--", source)
            Check.ZSH -> listOf("-n", source)
            Check.PHP -> listOf("-l", source)
            Check.RUBY -> listOf("-c", source)
            Check.DART -> listOf("analyze", source)
        }
        val argv = listOf(check.tool) + args
        val invocation = if (check in setOf(Check.JAVASCRIPT, Check.TYPESCRIPT)) WorkspaceNpmTool.command(relative, argv) else argv
        return argv.joinToString(" ") to invocation
    }

    suspend fun execute(session: ITerminalSession, root: File, path: String): RunnerExecutionResult = withContext(Dispatchers.IO) {
        val (description, command) = command(root, path)
        val argv = Runner(session, root).resolveCompilerAlias(command)
        val result = WorkspaceCommandInvocation.execute(session, root, root, argv, Runner.DEFAULT_RUN_TIMEOUT_MS)
        val output = buildString {
            appendLine(if (argv.first() != command.first()) argv.joinToString(" ") else description)
            appendLine(boundedRunOutput(result.output, 16_000))
            if (result.outputIncomplete) appendLine("[Command output capture was incomplete]")
            if (result.timedOut) appendLine("File check timed out.")
            if (result.exitCode in setOf(126, 127)) appendLine("Check that ${description.substringBefore(' ')} is executable in this workspace's Ubuntu terminal.")
            append("exit=${result.exitCode}")
        }
        RunnerExecutionResult(output, result.exitCode, result.timedOut, processStarted = true)
    }
}
