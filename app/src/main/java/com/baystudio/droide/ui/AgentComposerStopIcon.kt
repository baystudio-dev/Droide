package com.baystudio.droide.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val StopSurfaceShape = RoundedCornerShape(10.dp)
private val StopGlyphShape = RoundedCornerShape(3.dp)
private val StopSurfaceAnimation = tween<Color>(140)
private val StopPressAnimation = tween<Float>(120)

/** Stop artwork; the composer button owns the 48 dp touch target and cancellation action. */
@Composable
internal fun AgentComposerStopIcon(
    size: Dp,
    actionLabel: String,
    interactionSource: InteractionSource,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val accent = lerp(DroideColors.Error, DroideColors.Text, .22f)
    val surface by animateColorAsState(
        targetValue = lerp(DroideColors.Surface2, accent, if (pressed) .22f else .10f),
        animationSpec = StopSurfaceAnimation,
        label = "Stop surface",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) .94f else 1f,
        animationSpec = StopPressAnimation,
        label = "Stop press",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(surface, StopSurfaceShape)
            .border(1.dp, lerp(DroideColors.Surface2, accent, .38f), StopSurfaceShape)
            .semantics { contentDescription = actionLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(12.dp).background(accent, StopGlyphShape))
    }
}
