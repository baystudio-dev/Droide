package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSourceToolsTest {
    @get:Rule val temp = TemporaryFolder()

    private class Shell(val path: String, val cwd: File) : ITerminalSession {
        override val output = MutableStateFlow("")
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
            val child = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
                .apply { environment()["PATH"] = path }.start()
            val output = child.inputStream.bufferedReader().use { it.readText() }
            return ExecResult(child.waitFor(), output, false)
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Use argv")
        override fun start() {}
        override fun clear() {}
        override fun interrupt() {}
        override fun destroy() {}
        override fun send(cmd: String) {}
    }

    private fun file(root: File, path: String, text: String): File = root.resolve(path).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun tool(root: File, path: String, body: String): File =
        file(root, path, "#!/bin/sh\n$body\n").also { check(it.setExecutable(true)) }

    @Test fun dashFindsClangAndPreservesACompilerErrorWithoutReplayingIt() = runBlocking {
        val root = temp.newFolder("source")
        val bin = temp.newFolder("tools")
        Files.createSymbolicLink(File(bin, "sh").toPath(), File("/bin/sh").toPath())
        tool(bin, "clang", """echo clang; exit 23""")
        tool(bin, "cc", """echo wrong-compiler; exit 0""")
        file(root, "- a.c", "int main(void) {}")
        val shell = Shell(bin.path, temp.newFolder("elsewhere"))
        val lint = SourceFileLint.execute(shell, root, "- a.c")
        assertEquals(23, lint.exitCode)
        assertTrue(lint.output.contains("clang -fsyntax-only"))
        assertFalse(lint.output.contains("wrong-compiler"))
        assertFalse(root.resolve(".droide").exists())
        val run = Runner(shell, root).runPlanResult(SourceRunPreparation.nativePlan("- a.c"))
        assertEquals(23, run.exitCode)
        assertFalse(run.success)
    }

    @Test fun projectPackageWinsThenWorkspacePackageThenBothInstallSourcesOnPath() = runBlocking {
        val root = temp.newFolder("workspace")
        val user = temp.newFolder("terminal")
        val extension = temp.newFolder("extension")
        val source = "packages/nested/a ' file.js"
        file(root, source, "0;")
        val global = tool(user, "eslint", "echo terminal")
        tool(extension, "eslint", "echo extension")
        val workspace = tool(root, "node_modules/.bin/eslint", "echo workspace")
        val nearest = tool(root, "packages/nested/node_modules/.bin/eslint", """printf 'nearest\n'; printf '%s\n' "${'$'}PWD" "${'$'}@" """)
        val shell = Shell("${user.path}:${extension.path}:/usr/bin:/bin", temp.newFolder("elsewhere"))
        suspend fun inspect() = SourceFileLint.execute(shell, root, source).also { assertTrue(it.output, it.success) }.output
        val seen = inspect()
        assertTrue(seen.contains("nearest\n${root.canonicalPath}\n--no-fix\n--\n./$source"))
        assertTrue(nearest.delete()); assertTrue(inspect().contains("\nworkspace\n"))
        assertTrue(workspace.delete()); assertTrue(inspect().contains("\nterminal\n"))
        assertTrue(global.delete()); assertTrue(inspect().contains("\nextension\n"))
    }

    @Test fun typeCheckSelectsTheNearestConfigAndDoesNotRunTheSource() {
        val root = temp.newFolder("typescript")
        val path = "app/src/test.ts"
        file(root, path, "throw new Error()")
        file(root, "tsconfig.json", "{}")
        val nearest = file(root, "app/tsconfig.json", "{}")
        val command = SourceFileLint.command(root, path).second
        assertEquals(listOf("node", "-e"), command.take(2))
        assertEquals(listOf("./$path", "./app/tsconfig.json"), command.takeLast(2))
        assertTrue(nearest.delete())
        assertEquals("./tsconfig.json", SourceFileLint.command(root, path).second.last())
        assertTrue(root.resolve("tsconfig.json").delete())
        assertEquals("./$path", SourceFileLint.command(root, path).second.last())
        assertFalse(SourceFileLint.supports("plain.txt"))
    }

    @Test fun typeCheckReportsOnlyTheSelectedFileWithTheProjectOptions() = runBlocking {
        val module = System.getenv("DROIDE_TEST_TYPESCRIPT_MODULE")?.let(::File)
        org.junit.Assume.assumeTrue("Provide an installed TypeScript package", module?.resolve("package.json")?.isFile == true)
        val root = temp.newFolder()
        file(root, "tsconfig.json", """{"compilerOptions":{"strict":true},"files":["sibling.ts"]}""")
        file(root, "sibling.ts", "export const ok: number = 1")
        val selected = file(root, "selected.ts", "export const bad: number = 'text'")
        val packages = root.resolve("node_modules").also { it.mkdir() }
        Files.createSymbolicLink(packages.resolve("typescript").toPath(), requireNotNull(module).toPath())
        val shell = Shell(System.getenv("PATH"), root)
        val failure = SourceFileLint.execute(shell, root, selected.name)
        assertEquals(2, failure.exitCode)
        assertTrue(failure.output.contains("selected.ts") && failure.output.contains("TS2322"))
        selected.writeText("export const ok: number = 1")
        file(root, "sibling.ts", "export const bad: number = 'text'")
        assertEquals(0, SourceFileLint.execute(shell, root, selected.name).exitCode)
        selected.writeText("export function fn(arg) { return arg }")
        assertTrue(SourceFileLint.execute(shell, root, selected.name).output.contains("TS7006"))
    }

    @Test fun goVetHandlesNestedModulesAndUsesSiblingDeclarations() = runBlocking {
        val go = System.getenv("DROIDE_TEST_GO_BIN")
        org.junit.Assume.assumeTrue("Provide a Go toolchain", go != null && File(go, "go").canExecute())
        val root = temp.newFolder()
        file(root, "nested package/go.mod", "module sample.test/nested\n\ngo 1.20\n")
        val selected = file(root, "nested package/a.go", "package sample\nfunc A() int { return b() }\n")
        file(root, "nested package/b.go", "package sample\nfunc b() int { return 1 }\n")
        val shell = Shell("$go:${System.getenv("PATH")}", root)
        val path = selected.relativeTo(root).invariantSeparatorsPath
        assertEquals(0, SourceFileLint.execute(shell, root, path).exitCode)
        selected.writeText("package sample\nfunc A() int { return missing() }\n")
        assertNotEquals(0, SourceFileLint.execute(shell, root, path).exitCode)
    }

    @Test fun compiledOutputsBelongToTheSelectedSource() {
        for (ext in listOf("kt", "rs")) {
            val first = requireNotNull(LanguageRegistry.runPlanFor("a/main.$ext"))
            val second = requireNotNull(LanguageRegistry.runPlanFor("b/main.$ext"))
            assertNotEquals(first.preparation!!.directory, second.preparation!!.directory)
            val output = first.steps.first().last()
            assertTrue(output.startsWith(first.preparation.directory + "/"))
            assertTrue(first.steps.last().last().removePrefix("./") == output)
        }
        assertEquals(listOf("python3", "./-example.py"), LanguageRegistry.runPlanFor("-example.py")!!.steps.single())
    }
}
