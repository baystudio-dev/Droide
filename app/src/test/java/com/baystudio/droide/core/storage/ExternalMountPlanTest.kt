package com.baystudio.droide.core.storage

import com.baystudio.droide.core.TerminalLaunchSpec
import org.junit.Assert.*
import org.junit.Test

class ExternalMountPlanTest {
    private val snapshot = StorageAccessSnapshot(true, true, true, listOf(
        SharedStorageVolume("/storage/emulated/10", "Primary", true, true),
        SharedStorageVolume("/storage/A123-4567", "SD", false, true)), 4)
    private val original = TerminalLaunchSpec(prefix = listOf("proot", "-r", "/private/rootfs", "-b", "/dev", "-w", "/private/project",
        "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/bin"), shell = "/bin/bash")

    @Test fun newUserLaunchAddsActualVolumesWithoutChangingCwdOrEnvironment() {
        for (purpose in StorageLaunchPurpose.entries.filter { it != StorageLaunchPurpose.PRIVATE }) {
            val next = ExternalMountPlan.apply(original, snapshot, purpose)
            assertTrue(next.prefix.contains("/storage/emulated/10:/sdcard"))
            assertTrue(next.prefix.contains("/storage/A123-4567:/storage/A123-4567"))
            assertEquals(original.prefix.drop(original.prefix.indexOf("-w")), next.prefix.drop(next.prefix.indexOf("-w")))
            assertEquals(original.environment, next.environment)
            assertEquals(4L, next.storageAccess.generation)
            assertEquals(purpose, next.storageAccess.purpose)
        }
        assertTrue(original.storageAccess.paths.isEmpty())
        assertFalse(original.prefix.any { it.startsWith("/storage/") })
    }

    @Test fun noMountsWithoutBothUserChoiceAndOsGrantOrForPrivateProbe() {
        for (state in listOf(snapshot.copy(supported = false), snapshot.copy(enabled = false), snapshot.copy(granted = false))) {
            assertEquals(original.prefix, ExternalMountPlan.apply(original, state, StorageLaunchPurpose.USER_TERMINAL).prefix)
        }
        assertEquals(original.prefix, ExternalMountPlan.apply(original, snapshot, StorageLaunchPurpose.PRIVATE).prefix)
    }

    @Test fun malformedOrPrivateVolumePathsCannotBecomeProotArguments() {
        for (path in listOf("/", "/data", "/data/user/0", "/storage/", "/storage/a:/data", "/storage/a\n-b", "/storage/../data", "/storage/a/")) {
            assertFalse(path, ExternalMountPlan.validVolumePath(path))
            val state = snapshot.copy(volumes = listOf(SharedStorageVolume(path, "bad", true, true)))
            assertEquals(original.prefix, ExternalMountPlan.apply(original, state, StorageLaunchPurpose.USER_TERMINAL).prefix)
        }
    }

    @Test fun remountingAnExistingSpecIsRejected() {
        val mounted = ExternalMountPlan.apply(original, snapshot, StorageLaunchPurpose.USER_TERMINAL)
        assertThrows(IllegalArgumentException::class.java) { ExternalMountPlan.apply(mounted, snapshot, StorageLaunchPurpose.USER_TASK) }
    }

    @Test fun volumeMatchingDoesNotConfusePathPrefixes() {
        assertNull(snapshot.volumeFor("/storage/A123-45678/file"))
        assertNotNull(snapshot.volumeFor("/storage/A123-4567/file"))
        assertNull(snapshot.copy(enabled = false).volumeFor("/storage/A123-4567/file"))
    }
}
