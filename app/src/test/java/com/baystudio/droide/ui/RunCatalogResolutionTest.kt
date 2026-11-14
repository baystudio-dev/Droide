package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunCatalogResolutionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun standaloneJvmProgramsInAnAndroidProjectUseTheirLanguageRunner() {
        temp.root.resolve("settings.gradle").writeText("include ':app'")
        temp.root.resolve("app/build.gradle").apply {
            parentFile!!.mkdirs()
            writeText("plugins { id 'com.android.application' }\nandroid { compileSdk 36 }")
        }
        assertNotNull(AndroidProjectDetector.detect(temp.root))
        val sources = mapOf(
            "okjiss.kt" to "fun main(){ println(\"Hello\") }",
            "app/src/main/java/Hello.java" to "class Hello { public static void main(String[] args) { System.out.println(\"Hello\"); } }",
        )
        for ((path, text) in sources) {
            temp.root.resolve(path).apply { parentFile!!.mkdirs(); writeText(text) }
            assertEquals(path, PrimaryRunTarget.FILE, primaryRunTarget(path, androidProject = true))
            val resolution = Runner.resolveFilePlan(path, temp.root)
            assertTrue(path, resolution is Runner.FilePlanResolution.Ready)
            val plan = (resolution as Runner.FilePlanResolution.Ready).plan
            val expected = if (path.endsWith(".kt")) listOf(
                listOf("kotlinc", path, "-include-runtime", "-d", ".droide/run/kotlin.jar"),
                listOf("java", "-jar", ".droide/run/kotlin.jar"),
            ) else listOf(listOf("java", path))
            assertEquals(path, expected, plan.steps)
            assertEquals(RunExecutionPolicy.Target.LOCAL_LINUX_ARM64, RunExecutionPolicy.decide(plan, true).target)
            assertEquals(RunExecutionPolicy.Target.UNAVAILABLE, RunExecutionPolicy.decide(plan, false).target)
            assertEquals(text, temp.root.resolve(path).readText())
        }
        assertFalse(temp.root.resolve(".droide").exists())
    }

    @Test fun allCatalogFilesResolveToLanguageCommandsOrAnExplanationWithoutInstallingAnything() {
        val commands = mapOf("python" to "python3", "javascript" to "node", "typescript" to "tsc", "go" to "go",
            "rust" to "rustc", "java" to "java", "kotlin" to "kotlinc", "c" to "gcc", "cpp" to "g++",
            "csharp" to "dotnet", "php" to "php", "ruby" to "ruby", "swift" to "swift", "dart" to "dart",
            "r" to "Rscript", "lua" to "lua", "perl" to "perl", "haskell" to "runhaskell", "scala" to "scala",
            "elixir" to "elixir", "erlang" to "escript", "clojure" to "clojure", "zig" to "zig", "nim" to "nim",
            "assembly" to "gcc", "powershell" to "pwsh", "goose" to "goose-lang")
        val noDirectRun = setOf("h", "hpp", "hh", "hxx", "h++", "hrl", "sbt", "zon", "psm1", "psd1",
            "cljs", "cljc", "edn", "bb", "cljd", "clj_kondo")
        for (language in LanguageRegistry.all) for (extension in language.extensions) {
            val path = "source files/hello.$extension"
            val file = temp.root.resolve(path)
            requireNotNull(file.parentFile).mkdirs()
            file.writeText(language.template)
            var contributions = 0
            val resolution = Runner.resolveFilePlan(path, temp.root) { contributions++; null }
            val executable = if (language.id == "shell") when (extension) { "bash" -> "bash"; "zsh" -> "zsh"; else -> "sh" }
                else commands[language.id]
            if (executable != null && extension !in noDirectRun) {
                assertTrue(path, resolution is Runner.FilePlanResolution.Ready)
                val plan = (resolution as Runner.FilePlanResolution.Ready).plan
                assertEquals(path, executable, plan.steps.first().first())
                assertEquals(0, contributions)
                assertEquals(RunExecutionPolicy.Target.LOCAL_LINUX_ARM64, RunExecutionPolicy.decide(plan, true).target)
                assertEquals(RunExecutionPolicy.Target.UNAVAILABLE, RunExecutionPolicy.decide(plan, false).target)
                assertTrue("Source identity must survive plan preparation: $path",
                    plan.steps.flatten().contains(path) || plan.preparation?.files?.values?.any { it.contains(path) } == true)
            } else {
                assertTrue(path, resolution is Runner.FilePlanResolution.Error)
                assertTrue((resolution as Runner.FilePlanResolution.Error).message.isNotBlank())
                assertEquals(1, contributions)
            }
            assertEquals(language.template, file.readText())
            assertFalse("Resolving a Run action must not install or generate project files", temp.root.resolve(".droide").exists())
            println("RUN_RESOLUTION_CHECK language=${language.id} extension=$extension result=${resolution.javaClass.simpleName}")
        }
    }

    @Test fun contributedRunnerReceivesTheSelectedFileWithoutSubstitutingAnotherLanguage() {
        val selected = "project files/program.custom-language"
        temp.root.resolve(selected).apply { requireNotNull(parentFile).mkdirs(); writeText("custom source") }
        val paths = mutableListOf<String>()
        val plan = RunPlan(listOf(listOf("custom-language-runtime", selected)), "Run selected custom source")
        val result = Runner.resolveFilePlan(selected, temp.root) { path -> paths += path; plan }
        assertEquals(listOf(selected), paths)
        assertEquals(plan, (result as Runner.FilePlanResolution.Ready).plan)
        assertFalse(temp.root.resolve(".droide").exists())
    }
}
