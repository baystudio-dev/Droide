package com.baystudio.droide.core

import android.os.FileObserver
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LspIndexInputWatchTest {
    @Test fun onlyChangesToIndexInputsInvalidateAndEachWatcherRetiresOnce() {
        val root = Files.createTempDirectory("droide-lsp-watch-").toFile()
        try {
            for ((id, input) in mapOf("terraform-ls" to "vars.tfvars.json", "lemminx" to "types.xsd",
                "buf" to "buf.lock", "zls" to ".zigversion")) {
                var retirements = 0
                var releases = 0
                lateinit var event: (Int, String?) -> Unit
                val watch = LspIndexInputWatch(LanguageServerRegistry.builtIns.single { it.id == id }, root,
                    { retirements++ }) { _, callback -> event = callback; AutoCloseable { releases++ } }
                watch.watch(root)
                watch.watch(root)
                event(FileObserver.CLOSE_WRITE, "notes.txt")
                event(FileObserver.OPEN, input)
                assertFalse(watch.dirty)
                event(FileObserver.MOVED_TO, input)
                event(FileObserver.CLOSE_WRITE, input)
                assertTrue(watch.dirty)
                assertEquals(1, retirements)
                watch.close()
                assertEquals(1, releases)
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
