package com.baystudio.droide.core.storage

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StorageToolAccessTest {
    @Test fun sessionGrantIsReusableButNeverOverridesDenyOrAnotherGeneration() = runBlocking {
        withTimeout(5000) {
            val approvals = ApprovalManager()
            val permissions = PermissionEngine(base = listOf(PermRule("*", "*", PermEffect.ASK),
                PermRule("edit", "/storage/card/protected", PermEffect.DENY)))
            val provenance = AgentRequestProvenance.primary("storage-session")
            suspend fun ask(path: String, generation: Int = 1) = StorageToolAccess.allow(permissions, approvals,
                "edit", path, "device-storage:$generation:/storage/card", "build", provenance, {})
            val first = async(start = CoroutineStart.UNDISPATCHED) { ask("/storage/card/one") }
            approvals.resolve(checkNotNull(approvals.pending.value).id, ApprovalResolution.ALLOW_SESSION)
            first.await()
            ask("/storage/card/two")
            assertNull(approvals.pending.value)
            assertTrue(runCatching { ask("/storage/card/protected") }.isFailure)
            val changed = async(start = CoroutineStart.UNDISPATCHED) { runCatching { ask("/storage/card/three", 2) } }
            assertNotNull(approvals.pending.value)
            approvals.resolve(false)
            assertTrue(changed.await().isFailure)
        }
    }

    @Test fun allowOnceDoesNotCreateVolumeGrant() = runBlocking {
        withTimeout(5000) {
            val approvals = ApprovalManager()
            val permissions = PermissionEngine(base = listOf(PermRule("read", "*", PermEffect.ASK)))
            suspend fun ask(path: String) = StorageToolAccess.allow(permissions, approvals, "read", path,
                "device-storage:1:/storage/card", "build", AgentRequestProvenance.primary("session"), {})
            val first = async(start = CoroutineStart.UNDISPATCHED) { ask("/storage/card/one") }
            approvals.resolve(true); first.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { ask("/storage/card/two") } }
            assertNotNull(approvals.pending.value)
            approvals.resolve(false)
            assertTrue(second.await().isFailure)
        }
    }

    @Test fun expiredOwnerCannotAcceptAnOutstandingPrompt() = runBlocking {
        withTimeout(5000) {
            var current = true
            val approvals = ApprovalManager()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { StorageToolAccess.allow(PermissionEngine(), approvals, "external_directory",
                    "/storage/card/file", "device-storage:1:/storage/card", "build",
                    AgentRequestProvenance.primary("session")) { check(current) } }
            }
            assertNotNull(approvals.pending.value)
            current = false
            approvals.resolve(true)
            assertTrue(request.await().isFailure)
        }
    }
}
