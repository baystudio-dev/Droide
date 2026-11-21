package com.baystudio.droide.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.baystudio.droide.R
import com.baystudio.droide.core.AgentBrowserViewport
import kotlin.math.roundToInt

internal class DesktopBrowserPreview(private val profile: AgentBrowserViewport) {
    var onTransformChanged: (() -> Unit)? = null
    var zoom by mutableFloatStateOf(1f)
        private set
    private var center by mutableStateOf(Offset(.5f, .5f))
    var directInput by mutableStateOf(false)
        private set

    private fun fittedSize(pane: Size): Size {
        val fit = minOf(pane.width / profile.width, pane.height / profile.height)
        return Size(profile.width * fit, profile.height * fit)
    }

    private fun translationLimit(pane: Size, fit: Size, scale: Float) = Offset(
        ((fit.width * scale - pane.width) / 2f).coerceAtLeast(0f),
        ((fit.height * scale - pane.height) / 2f).coerceAtLeast(0f),
    )

    private fun Offset.bounded(limit: Offset) =
        Offset(x.coerceIn(-limit.x, limit.x), y.coerceIn(-limit.y, limit.y))

    fun translation(pane: Size): Offset {
        val fit = fittedSize(pane)
        return Offset((.5f - center.x) * fit.width * zoom, (.5f - center.y) * fit.height * zoom)
            .bounded(translationLimit(pane, fit, zoom))
    }

    fun transform(pane: Size, anchor: Offset, pan: Offset, factor: Float) {
        if (pane.isEmpty() || !factor.isFinite() || factor <= 0f || directInput) return
        val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        if (next == 1f) { fit(); return }
        val fitted = fittedSize(pane)
        val focal = anchor - pane.center
        val offset = ((translation(pane) - focal) * (next / zoom) + focal + pan)
            .bounded(translationLimit(pane, fitted, next))
        center = Offset(.5f - offset.x / (fitted.width * next), .5f - offset.y / (fitted.height * next))
        zoom = next
        onTransformChanged?.invoke()
    }

    fun fit() {
        if (zoom == 1f) return
        zoom = 1f
        center = Offset(.5f, .5f)
        onTransformChanged?.invoke()
    }

    fun switchInput() {
        fit()
        directInput = !directInput
    }

    companion object { const val MAX_ZOOM = 8f }
}

@Composable
internal fun DesktopBrowserPreviewFrame(
    preview: DesktopBrowserPreview?,
    presented: Boolean,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Box(modifier.clipToBounds()) {
        content(Modifier.matchParentSize())
        if (preview != null && !preview.directInput && presented) {
            val label = stringResource(R.string.browser_preview_label)
            Box(Modifier.matchParentSize().testTag("desktop-browser-preview")
                .semantics {
                    contentDescription = label
                    stateDescription = "${(preview.zoom * 100f).roundToInt()}%"
                }
                .pointerInput(preview) {
                    detectTransformGestures(panZoomLock = true) { anchor, pan, factor, _ ->
                        preview.transform(size.toSize(), anchor, pan, factor)
                    }
                }
                .pointerInput(preview) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (preview.zoom > 1f) preview.fit() else preview.transform(size.toSize(), at, Offset.Zero, 2f)
                    })
                })
        }
    }
}

@Composable
internal fun DesktopBrowserPreviewMenu(preview: DesktopBrowserPreview, pane: Size, dismiss: () -> Unit) {
    if (!preview.directInput) {
        val smaller = stringResource(R.string.browser_preview_zoom_out)
        val larger = stringResource(R.string.browser_preview_zoom_in)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { preview.transform(pane, pane.center, Offset.Zero, .5f) },
                enabled = preview.zoom > 1f, modifier = Modifier.semantics { contentDescription = smaller }) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = { preview.fit(); dismiss() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.browser_preview_fit))
            }
            IconButton(onClick = { preview.transform(pane, pane.center, Offset.Zero, 2f) },
                enabled = preview.zoom < DesktopBrowserPreview.MAX_ZOOM, modifier = Modifier.semantics { contentDescription = larger }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    val inputLabel = if (preview.directInput) R.string.browser_preview_observe else R.string.browser_preview_interact
    DropdownMenuItem(text = { Text(stringResource(inputLabel)) }, onClick = { preview.switchInput(); dismiss() })
}
