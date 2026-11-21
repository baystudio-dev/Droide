package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidSdkComponentArchiveTest {
    private val catalog = listOf(File("src/main/assets"), File("app/src/main/assets"))
        .map { it.resolve("android-local-component-catalog.json") }.first(File::isFile)
        .let { Json.decodeFromString<AndroidLocalComponentCatalogDocument>(it.readText()) }
    private val platform = catalog.entries.single { it.kind == AndroidLocalComponentKind.SDK_PLATFORM }

    @Test fun catalogAdmitsOfficialInventoryAndKeepsThePlatformBoundSeparate() {
        AndroidLocalComponentCatalog.validate(catalog)
        assertEquals(11_342, platform.maxFiles)
        assertThrows(IllegalArgumentException::class.java) { platform.copy(maxFiles = 32_769).validate() }
        val tools = catalog.entries.single { it.kind == AndroidLocalComponentKind.BUILD_TOOLS_ARM64 }
        assertThrows(IllegalArgumentException::class.java) { tools.copy(maxFiles = 8_193).validate() }
    }

    @Test fun checksAllNamesAndBudgetsBeforeWritingAnyPayload() = fixture { parent, target ->
        val zip = parent.resolve("fixture.zip")
        fun archive(vararg names: String) = ZipOutputStream(zip.outputStream()).use { stream ->
            for (name in names) {
                stream.putNextEntry(ZipEntry("android-36/$name"))
                stream.write(byteArrayOf(1, 2, 3))
                stream.closeEntry()
            }
        }
        fun reject(component: AndroidLocalComponentCatalogEntry) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { AndroidSdkComponentArchive.extract(component, zip, target) { fail("Invalid archive reached storage admission") } }
            }
            assertTrue(target.list()!!.isEmpty())
        }
        archive("ok", "../escape"); reject(platform)
        archive("one", "two"); reject(platform.copy(maxFiles = 1))
        reject(platform.copy(maxUnpackedBytes = 4))
        runBlocking {
            AndroidSdkComponentArchive.extract(platform, zip, target) { assertEquals(6, it) }
        }
        assertEquals(2, target.list()!!.size)
    }

    @Test fun publishedArchivesPassInstallAndInstalledFileChecks() = fixture { parent, target ->
        val directory = System.getProperty("droide.androidArchives")?.let(::File)
        assumeTrue("Supply the pinned Google archives to run the publisher integration", directory != null)
        runBlocking {
            for (entry in catalog.entries) {
                val archive = File(directory, entry.fileName)
                AndroidSdkComponentArchive.verify(entry, archive)
                val stage = target.resolve(entry.id).apply { mkdirs() }
                val component = stage.resolve("payload/component").apply { mkdirs() }
                val admitted = if (entry.kind == AndroidLocalComponentKind.SDK_PLATFORM) null else AndroidBuildToolsPayload.archiveFiles
                val expected = ZipFile(archive).use { zip -> zip.entries().asSequence().filter {
                    !it.isDirectory && (admitted == null || it.name.substringAfter('/') in admitted)
                }.toList() }
                AndroidSdkComponentArchive.extract(entry, archive, component) { bytes -> assertEquals(expected.sumOf { it.size }, bytes) }
                assertEquals(expected.size, component.walkTopDown().count(File::isFile))
                if (entry.kind == AndroidLocalComponentKind.SDK_PLATFORM) {
                    assertEquals(11_342, expected.size)
                    assertEquals(106_177_284L, expected.sumOf { it.size })
                    assertTrue(component.resolve("android.jar").length() > 1_000_000)
                } else {
                    AndroidBuildToolsPayload.requireGoogleFiles(component)
                }
                LocalPackageInstallJournal.prepare(stage)
                stage.resolve("COMPONENT_LOCK.json").writeText("{}")
                LocalPackageIntegrity.seal(stage, maxFiles = entry.maxFiles + 2)
                assertTrue(LocalPackageIntegrity.verify(stage, stage.resolve("SHA256SUMS"), exactInventory = true))
                println("${entry.fileName}: ${expected.size} admitted files, ${expected.sumOf { it.size }} expanded bytes; installed inventory verified")
            }
            val bad = parent.resolve("corrupt.zip").apply { writeBytes(byteArrayOf(4, 3, 2, 1)) }
            assertThrows(IllegalStateException::class.java) { runBlocking {
                AndroidSdkComponentArchive.verify(platform.copy(sizeBytes = bad.length()), bad)
            } }
        }
    }

    private fun fixture(test: (File, File) -> Unit) {
        val parent = Files.createTempDirectory("droide-sdk-").toFile()
        try { test(parent, parent.resolve("stage").apply { mkdir() }) }
        finally { PathSecurity.deleteTreeNoFollow(parent) }
    }
}
