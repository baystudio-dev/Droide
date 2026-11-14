package com.baystudio.droide.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExecutionDiagnosticsTest {
    @Test fun guestResetAndLastOverrideDetermineVisibleEnvironmentWithoutCredentials() {
        val spec = TerminalLaunchSpec(prefix = listOf("proot", "/usr/bin/env", "-i", "PATH=/base", "PATH=/user",
            "SECRET_TOKEN=never-persist", "HOME=/root"), environment = mapOf("PATH" to "/android"))
        val launch = ExecutionDiagnostics.record(File("/workspace"), "Run", "/workspace/sub", spec, "/usr/bin/python3",
            mapOf("PATH" to "/project", "AWS_SECRET_ACCESS_KEY" to "never-persist"))
        assertEquals("/project", launch.environment["PATH"])
        assertEquals("/usr/bin/python3", launch.program)
        assertEquals(2, launch.omittedVariables)
        assertFalse(launch.describe().contains("never-persist"))
        assertFalse(launch.describe().contains("/android"))
    }

    @Test fun metadataIsBoundedAndCannotInjectTerminalControlSequences() {
        val before = ExecutionDiagnostics.record(File("/workspace"), "initial", "/one", TerminalLaunchSpec())
        repeat(40) { ExecutionDiagnostics.record(File("/workspace"), "owner\u001b[2J", "/two",
            TerminalLaunchSpec(environment = mapOf("PATH" to "https://user:pass@host/tool"))) }
        val records = ExecutionDiagnostics.launches.value
        assertEquals(32, records.size)
        assertTrue(records.none { it.id == before.id })
        assertFalse(records.last().describe().contains("user:pass"))
        assertFalse(records.last().describe().contains('\u001b'))
    }

    @Test fun guestLaunchRecordsItsActualDirectoryInsteadOfAnUnrelatedInteractiveShellHint() {
        val spec = TerminalLaunchSpec(prefix = listOf("proot", "-r", "/guest", "-w", "/project",
            "/usr/bin/env", "-i", "PATH=/usr/bin"), shell = "/bin/bash")
        val launch = ExecutionDiagnostics.record(File("/project"), "Agent interactive PTY", "/sdcard/Download", spec)
        assertEquals("/project", launch.cwd)
        assertEquals("/sdcard/Download", ExecutionDiagnostics.record(File("/project"), "Native shell",
            "/sdcard/Download", TerminalLaunchSpec()).cwd)
    }

    @Test fun ambiguousOrCustomWrappersKeepTheSuppliedWorkingDirectory() {
        for (prefix in listOf(
            listOf("custom", "-w", "/unrelated"),
            listOf("proot", "-r", "/guest", "-w", "/first", "-w", "/second", "/usr/bin/env", "-i"),
            listOf("proot", "-r", "/guest", "-w", "relative", "/usr/bin/env", "-i"),
            listOf("proot", "-r", "relative", "-w", "/first", "/usr/bin/env", "-i"),
        )) {
            val launch = ExecutionDiagnostics.record(File("/project"), "Custom task", "/project/task",
                TerminalLaunchSpec(prefix = prefix))
            assertEquals(prefix.toString(), "/project/task", launch.cwd)
        }
    }
}
