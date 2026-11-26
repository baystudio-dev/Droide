package com.baystudio.droide.ui

import androidx.compose.runtime.saveable.SaverScope
import com.baystudio.droide.core.WorkspaceSearchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTextStateSaverTest {
    @Test fun retainsSmallSearchStringsAndOmitsOversizedPastes() {
        val scope = SaverScope { true }
        val limit = "x".repeat(WorkspaceSearchInput.MAX_RAW_INPUT_CHARS)
        val oversized = limit + "y"
        with(SearchTextStateSaver) {
            assertEquals("", scope.save(""))
            assertEquals(limit, scope.save(limit))
            assertEquals(limit, restore(limit))
            assertNull(scope.save(oversized))
            assertEquals(oversized, restore(oversized))
        }
    }
}
