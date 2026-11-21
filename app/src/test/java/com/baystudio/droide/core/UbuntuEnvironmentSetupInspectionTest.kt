package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UbuntuEnvironmentSetupInspectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val guestName = FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR

    private fun inspect(files: File, verified: String? = null, health: () -> Boolean = { error("Unexpected probe") },
                        record: (String) -> Unit = {}) = runBlocking {
        UbuntuEnvironmentSetup.inspect(files, verified, false, { health() }, record)
    }

    private fun populatedGuest(files: File): File {
        val guest = files.resolve("guest/$guestName")
        for (name in listOf("launch", "BOOTSTRAP_LOCK.txt", "rootfs/usr/bin/apt-get", "rootfs/usr/bin/dpkg", "rootfs/usr/bin/bash")) {
            guest.resolve(name).apply { parentFile.mkdirs(); writeText("fixture $name") }
        }
        return guest
    }

    @Test fun freshAndInterruptedStagingDoNotPretendToBeInstalled() {
        val files = temporary.newFolder()
        assertEquals(EnvironmentSetupInspection(false), inspect(files))
        files.resolve("guest/.staging/incomplete/rootfs").mkdirs()
        assertEquals(EnvironmentSetupInspection(false), inspect(files))
    }

    @Test fun retainedDataAloneOffersRepairAfterControllerRecreation() {
        val files = temporary.newFolder()
        val guest = files.resolve("guest/$guestName")
        guest.mkdirs()
        val draft = guest.resolve("user-work.txt").apply { writeText("keep me") }
        repeat(2) { assertEquals(EnvironmentSetupInspection(false, recoveryRequired = true), inspect(files)) }
        assertEquals("keep me", draft.readText())
    }

    @Test fun previousGuestIsRecoverableAndNeverAcknowledgedAsActive() {
        val files = temporary.newFolder()
        val previous = files.resolve("guest/.previous/$guestName")
        previous.mkdirs()
        val draft = previous.resolve("draft.txt").apply { writeText("work") }
        assertEquals(EnvironmentSetupInspection(false, recoveryRequired = true), inspect(files, "old receipt"))
        assertEquals("work", draft.readText())
    }

    @Test fun failedFirstActivationStaysRecoverableAfterRestartAndRetryEntersWorkspace() = runBlocking {
        val files = temporary.newFolder()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        var receipt: String? = null
        var attempts = 0
        var healthy = false
        fun controller() = EnvironmentSetupController(scope, true, {
            UbuntuEnvironmentSetup.inspect(files, receipt, false, { healthy }, { receipt = it })
        }, {
            attempts++
            populatedGuest(files)
            if (attempts == 1) error("fixture activation interrupted")
            healthy = true
        }, {})
        try {
            val first = controller()
            first.installFromUser()
            assertEquals(EnvironmentSetupPhase.FAILED, first.state.value.phase)
            first.closeAndJoin()
            val reopened = controller()
            assertEquals(EnvironmentSetupPhase.AVAILABLE, reopened.state.value.phase)
            assertTrue(reopened.state.value.presented && reopened.state.value.recoveryRequired)
            reopened.show()
            assertTrue(reopened.state.value.presented)
            reopened.installFromUser()
            assertEquals(EnvironmentSetupPhase.READY, reopened.state.value.phase)
            assertFalse(reopened.state.value.presented || reopened.state.value.recoveryRequired)
            reopened.closeAndJoin()
            val ready = controller()
            assertEquals(EnvironmentSetupPhase.READY, ready.state.value.phase)
            assertEquals(2, attempts)
            ready.closeAndJoin()
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun migrationChecksHealthOnceAndRechecksReplacedInstallations() {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        assertTrue(inspect(files, health = { false }).recoveryRequired)
        var receipt: String? = null
        assertTrue(inspect(files, health = { true }, record = { receipt = it }).existingEnvironment)
        assertNotNull(receipt)
        repeat(3) { assertTrue(inspect(files, receipt).existingEnvironment) }
        guest.resolve("rootfs/usr/bin/dpkg").appendText("changed")
        assertTrue(inspect(files, receipt, { false }).recoveryRequired)
        assertFalse(inspect(files, health = { guest.resolve("launch").delete(); true }).existingEnvironment)
    }

    @Test fun reopeningAfterLauncherRefreshAndUbuntuUseNeverCallsSetup() {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        var receipt: String? = null
        assertTrue(inspect(files, health = { true }, record = { receipt = it }).existingEnvironment)
        val packageFile = guest.resolve("rootfs/usr/bin/my-tool").apply { writeText("installed package") }
        val userFile = guest.resolve("rootfs/root/notes").apply { parentFile.mkdirs(); writeText("my work") }
        val next = guest.resolve(".launch-next").apply { writeText("launcher from updated APK") }
        Files.move(next.toPath(), guest.resolve("launch").toPath(), StandardCopyOption.REPLACE_EXISTING)
        repeat(4) { assertTrue(inspect(files, receipt).existingEnvironment) }
        assertEquals("installed package", packageFile.readText())
        assertEquals("my work", userFile.readText())
    }

    @Test fun existingUbuntuIsAdoptedWhenItsLauncherChangesDuringMigration() {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        var receipt: String? = "receipt from the previous APK"
        assertTrue(inspect(files, receipt, {
            guest.resolve("launch").writeText("APK launcher refreshed while probing")
            true
        }, { receipt = it }).existingEnvironment)
        assertTrue(inspect(files, receipt).existingEnvironment)
        guest.resolve("rootfs/usr/bin/dpkg").writeText("new package version")
        var checks = 0
        repeat(4) {
            assertTrue(inspect(files, receipt, { checks++; true }, { receipt = it }).existingEnvironment)
        }
        assertEquals(1, checks)
    }

    @Test fun ownedHardLinksRemainUsableAndBrokenOrExternalTargetsRequireRepair() {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        val store = guest.resolve("rootfs/.l2s").apply { mkdir() }
        val binary = guest.resolve("rootfs/usr/bin/dpkg").toPath()
        val intermediate = store.resolve(".l2s.dpkg0001").toPath()
        val data = store.resolve(".l2s.dpkg0001.0002").toPath()
        Files.move(binary, data)
        Files.createSymbolicLink(intermediate, data)
        Files.createSymbolicLink(binary, intermediate)
        var receipt: String? = null
        assertTrue(inspect(files, health = { true }, record = { receipt = it }).existingEnvironment)
        assertTrue(inspect(files, receipt).existingEnvironment)
        Files.delete(intermediate)
        assertTrue(inspect(files, receipt).recoveryRequired)
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(intermediate, outside.toPath())
        assertTrue(inspect(files, receipt).recoveryRequired)
        assertEquals("keep", outside.readText())
    }

    @Test fun anInconclusiveProbeOpensTheEditorAndNeverStartsOrCertifiesAnInstall() = runBlocking {
        val files = temporary.newFolder()
        populatedGuest(files)
        val controller = EnvironmentSetupController(this, true, {
            UbuntuEnvironmentSetup.inspect(files, null, false, { null }, { error("Unverified receipt") })
        }, { error("Unexpected installer") }, {})
        kotlinx.coroutines.yield()
        assertTrue(controller.state.value.enteredWorkspace)
        assertFalse(controller.state.value.presented)
        controller.installFromUser()
        controller.show()
        kotlinx.coroutines.yield()
        assertFalse(controller.state.value.presented || controller.state.value.recoveryRequired)
        assertEquals(EnvironmentSetupPhase.CHECKING, controller.state.value.phase)
        controller.closeAndJoin()
    }

    @Test fun retryAfterAnotherOwnerVerifiedTheGuestSkipsInstaller() = runBlocking {
        val files = temporary.newFolder()
        var receipt: String? = null
        var installs = 0
        val controller = EnvironmentSetupController(this, true, {
            UbuntuEnvironmentSetup.inspect(files, receipt, false, { error("Unexpected health probe") }, { error("Unexpected receipt") })
        }, { installs++; error("First attempt interrupted") }, {})
        kotlinx.coroutines.yield()
        controller.installFromUser()
        kotlinx.coroutines.yield()
        assertEquals(EnvironmentSetupPhase.FAILED, controller.state.value.phase)
        populatedGuest(files)
        inspect(files, health = { true }, record = { receipt = it })
        controller.installFromUser()
        kotlinx.coroutines.yield()
        assertEquals(1, installs)
        assertEquals(EnvironmentSetupPhase.READY, controller.state.value.phase)
        controller.closeAndJoin()
    }

    @Test fun changedBinariesDuringAProbeDoNotTriggerSetupOrInheritTheOldReceipt() = runBlocking {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        val found = UbuntuEnvironmentSetup.inspect(files, null, false, {
            guest.resolve("rootfs/usr/bin/dpkg").appendText("new revision")
            true
        }, { error("Unstable receipt") })
        assertTrue(found.checkLater)
        assertFalse(found.existingEnvironment || found.recoveryRequired)
        assertTrue(inspect(files, health = { true }).existingEnvironment)
    }

    @Test fun aTemporarilyMissingBinaryWaitsForAnOngoingGuestMutation() = runBlocking {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        var receipt: String? = null
        inspect(files, health = { true }, record = { receipt = it })
        guest.resolve("rootfs/usr/bin/bash").delete()
        val busy = UbuntuEnvironmentSetup.inspect(files, receipt, false, { error("Unexpected probe") },
            { error("Unverified receipt") }, mutationInProgress = { true })
        assertTrue(busy.checkLater)
        assertTrue(inspect(files, receipt).recoveryRequired)
    }

    @Test fun aReplacedRootfsCannotInheritAReceiptEvenWhenItsContentsLookIdentical() {
        val files = temporary.newFolder()
        val guest = populatedGuest(files)
        var receipt: String? = null
        assertTrue(inspect(files, health = { true }, record = { receipt = it }).existingEnvironment)
        val root = guest.resolve("rootfs")
        val old = guest.resolve("retained")
        Files.move(root.toPath(), old.toPath())
        assertTrue(old.copyRecursively(root))
        assertTrue(inspect(files, receipt, { false }).recoveryRequired)
        assertTrue(old.resolve("usr/bin/bash").isFile)
    }

    @Test fun explicitRetryOnlyClearsAnEmptyActivationOrRestoresAnOwnedMissingLauncher() {
        val files = temporary.newFolder()
        val guest = files.resolve("guest/$guestName").apply { mkdirs() }
        assertTrue(UbuntuGuestRecoveryInspection.clearEmptyActivation(guest))
        populatedGuest(files)
        assertFalse(UbuntuGuestRecoveryInspection.clearEmptyActivation(guest))
        val launch = guest.resolve("launch").apply { delete() }
        UbuntuGuestRecoveryInspection.restoreMissingLauncher(guest, "expected") { error("Unowned profile") }
        assertFalse(launch.exists())
        guest.resolve("BOOTSTRAP_LOCK.txt").writeText("environment=expected\n")
        UbuntuGuestRecoveryInspection.restoreMissingLauncher(guest, "expected") { "verified launcher" }
        assertEquals("verified launcher", launch.readText())
        assertTrue(launch.canExecute())
        UbuntuGuestRecoveryInspection.restoreMissingLauncher(guest, "expected") { error("Existing launcher") }
        assertEquals("fixture rootfs/usr/bin/dpkg", guest.resolve("rootfs/usr/bin/dpkg").readText())
    }

    @Test fun unexpectedFilesAndSymlinksFailClosedWithoutFollowingOrRemovingThem() {
        for (relative in listOf("guest", "guest/.previous", "guest/$guestName")) {
            val files = temporary.newFolder()
            val path = files.resolve(relative); path.parentFile!!.mkdirs(); path.writeText("existing data")
            assertThrows(IllegalStateException::class.java) { inspect(files) }
            assertEquals("existing data", path.readText())
            path.delete()
            val outside = temporary.newFolder()
            outside.resolve("keep.txt").writeText("private")
            Files.createSymbolicLink(path.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { inspect(files) }
            assertTrue(Files.isSymbolicLink(path.toPath()))
            assertEquals("private", outside.resolve("keep.txt").readText())
        }
    }
}
