package com.baystudio.droide.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small content gutter; Android's existing inset owner still handles the keyboard. */
internal fun bottomTouchClearance(availableHeight: Dp): Dp {
    if (!availableHeight.value.isFinite()) return if (availableHeight == Dp.Infinity) 24.dp else 0.dp
    if (availableHeight <= 0.dp) return 0.dp
    // Follow real pane constraints continuously, including keyboard and dock resize.
    // In a very short pane retain at least three quarters of the height for content.
    return (availableHeight * .10f).coerceIn(8.dp, 24.dp).coerceAtMost(availableHeight * .25f)
}

internal fun Modifier.terminalBottomTouchClearance(): Modifier = layout { measurable, constraints ->
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight.toDp() else Dp.Infinity
    val clearance = bottomTouchClearance(height).roundToPx()
    val child = measurable.measure(constraints.offset(vertical = -clearance))
    layout(child.width, constraints.constrainHeight(child.height + clearance)) {
        child.placeRelative(0, 0)
    }
}
