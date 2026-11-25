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

    private fun nearestTypeScriptConfig(root: File, file: File): String? {
        var directory = requireNotNull(file.parentFile)
        while (true) {
            val config = File(directory, "tsconfig.json")
            if (config.isFile && PathSecurity.contains(root, config)) return "./" + config.relativeTo(root).invariantSeparatorsPath
            if (directory == root) return null
            directory = requireNotNull(directory.parentFile)
        }
    }

    // A positional filename makes the tsc CLI ignore tsconfig. Use its public compiler API
    // for the selected source while retaining the project's options, globals and references.
    private val checkTypeScriptFile = """
        const fs = require("fs"), path = require("path");
        const [file, config] = process.argv.slice(1).map(value => path.resolve(value));
        function compilerBeside(binary) {
            if (!fs.existsSync(binary)) return;
            const lib = path.resolve(path.dirname(fs.realpathSync(binary)), "../lib/typescript.js");
            if (fs.existsSync(lib)) return require(lib);
        }
        let ts;
        for (let dir = path.dirname(file); ; dir = path.dirname(dir)) {
            const pkg = path.join(dir, "node_modules", "typescript");
            if (fs.existsSync(path.join(pkg, "package.json"))) ts = require(pkg);
            else ts = compilerBeside(path.join(dir, "node_modules", ".bin", "tsc"));
            if (ts || dir === process.cwd() || dir === path.dirname(dir)) break;
        }
        if (!ts) for (const dir of (process.env.PATH || "").split(path.delimiter)) {
            ts = compilerBeside(path.join(dir, "tsc"));
            if (ts) break;
        }
        if (!ts) { console.error("Install TypeScript in this project or the active Ubuntu terminal."); process.exit(127); }
        const format = {
            getCurrentDirectory: ts.sys.getCurrentDirectory,
            getCanonicalFileName: ts.sys.useCaseSensitiveFileNames ? value => value : value => value.toLowerCase(),
            getNewLine: () => ts.sys.newLine
        };
        const report = diagnostics => {
            if (diagnostics.length) process.stderr.write(ts.formatDiagnostics(diagnostics, format));
            if (diagnostics.some(d => d.category === ts.DiagnosticCategory.Error)) process.exitCode = 2;
        };
        const parsed = ts.getParsedCommandLineOfConfigFile(config, {}, {
            ...ts.sys, onUnRecoverableConfigFileDiagnostic: diagnostic => report([diagnostic])
        });
        if (!parsed) process.exit(2);
        // TS18003 describes an empty project root set. This action supplies the open file explicitly.
        report(parsed.errors.filter(diagnostic => diagnostic.code !== 18003));
        if (process.exitCode) process.exit(process.exitCode);
        if (!parsed.fileNames.includes(file)) console.log("Checking the selected file explicitly with " + config);
        const rootNames = Array.from(new Set([...parsed.fileNames, file]));
        const program = ts.createProgram({ rootNames, options: { ...parsed.options, noEmit: true },
                                           projectReferences: parsed.projectReferences });
        const source = program.getSourceFile(file);
        if (!source) { console.error("TypeScript could not load the selected file."); process.exit(2); }
        report(ts.getPreEmitDiagnostics(program, source));
    """.trimIndent()

    private val checkGoPackage = """
        cd "${'$'}1" || exit 1
        module=${'$'}(go env GOMOD) || exit ${'$'}?
        if [ "${'$'}module" = /dev/null ]; then
            work=${'$'}(go env GOWORK) || exit ${'$'}?
            case "${'$'}work" in ''|off) export GO111MODULE=off ;; esac
        fi
        exec go vet .
    """.trimIndent()

    fun command(root: File, path: String): Pair<String, List<String>> {
        val file = PathSecurity.resolveWithin(root, path)
        require(file.isFile) { "Source file is unavailable: $path" }
        val relative = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        val source = "./$relative"
        val check = check(relative) ?: error("No built-in file check for $relative")
        val args = when (check) {
            Check.PYTHON -> listOf("check", "--no-fix", "--no-cache", "--", source)
            Check.JAVASCRIPT -> listOf("--no-fix", "--", source)
            Check.TYPESCRIPT -> {
                val config = nearestTypeScriptConfig(root.canonicalFile, file)
                if (config != null) return "TypeScript file check: $relative ($config)" to
                    listOf("node", "-e", checkTypeScriptFile, source, config)
                listOf("--noEmit", "--target", "ES2022", "--module", "NodeNext", "--jsx", "preserve", source)
            }
            Check.KOTLIN -> listOf(source)
            Check.GO -> {
                val directory = source.substringBeforeLast('/')
                return "go vet . (package $directory)" to listOf("sh", "-c", checkGoPackage, "droide-go-check", directory)
            }
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
            if (result.timedOut) appendLine("Check timed out.")
            if (result.exitCode in setOf(126, 127)) {
                if (check(path) == Check.TYPESCRIPT) appendLine("Check that Node.js and TypeScript are installed in the active Ubuntu workspace.")
                else appendLine("Check that ${description.substringBefore(' ')} is executable in this workspace's Ubuntu terminal.")
            }
            append("exit=${result.exitCode}")
        }
        RunnerExecutionResult(output, result.exitCode, result.timedOut, processStarted = true)
    }
}
