package com.baystudio.droide.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRecoveryScheduleTest {
    @Test fun largeFileCannotStarveAnotherDraftOrWaitForeverForIdle() {
        val schedule = EditorRecoverySchedule()
        val small = mapOf("notes" to 1)
        assertTrue(schedule.due(0, "start", small, mapOf("large" to 1)))
        schedule.saved(0, "start", small)
        for (tick in 1..4) assertFalse(schedule.due(tick * 2_000L, "edit $tick", small, mapOf("large" to tick + 1)))
        assertTrue(schedule.due(10_000, "edit 5", small, mapOf("large" to 6)))
        schedule.saved(10_000, "edit 5", small)
        assertTrue(schedule.due(12_000, "both", mapOf("notes" to 2), mapOf("large" to 7)))
    }

    @Test fun movingSelectionDoesNotResetLargeContentIdleTime() {
        val schedule = EditorRecoverySchedule()
        val large = mapOf("large" to 1)
        assertFalse(schedule.due(0, "line 1", emptyMap(), large))
        assertFalse(schedule.due(2_000, "line 2", emptyMap(), large))
        assertTrue(schedule.due(4_000, "line 3", emptyMap(), large))
        schedule.saved(4_000, "line 3", emptyMap())
        assertFalse(schedule.due(6_000, "line 3", emptyMap(), large))
    }
}
