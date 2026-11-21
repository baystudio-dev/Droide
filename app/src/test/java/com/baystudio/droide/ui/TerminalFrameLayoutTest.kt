package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class TerminalFrameLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardAndDockSizesMatchThePriorGutterWithoutRecomposingTheSurface() {
        val height = mutableIntStateOf(240)
        var previousHeight = -1
        var actualHeight = -1
        var previousCompositions = 0
        var compositions = 0
        compose.setContent {
            val pane = Modifier.width(120.dp).layout { measurable, constraints ->
                val h = height.intValue
                val child = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(child.width, h) { child.place(0, 0) }
            }
            Row {
                BoxWithConstraints(pane) {
                    val gutter = bottomTouchClearance(maxHeight)
                    SideEffect { previousCompositions++ }
                    Box(Modifier.fillMaxSize().padding(bottom = gutter).onSizeChanged { previousHeight = it.height })
                }
                Box(pane) {
                    Box(Modifier.fillMaxSize().terminalBottomTouchClearance().onSizeChanged { actualHeight = it.height }) {
                        SideEffect { compositions++ }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = previousCompositions
        val after = compositions
        val heights = listOf(220, 200, 180, 160, 140, 120, 90, 60, 30, 16, 4, 1, 0, 37, 87, 175, 300, 240)
        for (h in heights) {
            compose.runOnIdle { height.intValue = h }
            compose.waitForIdle()
            assertEquals("Pane height $h", previousHeight, actualHeight)
        }
        assertTrue(previousCompositions - before >= heights.size)
        assertEquals(after, compositions)
    }
}
