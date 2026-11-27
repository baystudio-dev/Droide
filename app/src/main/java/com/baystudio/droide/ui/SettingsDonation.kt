package com.baystudio.droide.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class QrSaveState { READY, SAVING, SAVED, FAILED }

@Composable
internal fun DonationQrCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveState by remember { mutableStateOf(QrSaveState.READY) }
    val recipient = stringResource(R.string.settings_qris_merchant)

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_qris_title), style = MaterialTheme.typography.titleMedium)
        OutlinedCard(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 272.dp).fillMaxWidth()) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = ImageBitmap.imageResource(R.drawable.donation_qris),
                    contentDescription = stringResource(R.string.settings_qris_image, recipient),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    filterQuality = FilterQuality.None,
                )
                SelectionContainer {
                    Text(stringResource(R.string.settings_qris_nmid), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        OutlinedButton(
            enabled = saveState == QrSaveState.READY || saveState == QrSaveState.FAILED,
            onClick = {
                if (saveState == QrSaveState.READY || saveState == QrSaveState.FAILED) {
                    saveState = QrSaveState.SAVING
                    scope.launchUiCatching(onError = { saveState = QrSaveState.FAILED }) {
                        saveDonationQrToGallery(context)
                        saveState = QrSaveState.SAVED
                    }
                }
            },
        ) { Text(stringResource(R.string.settings_qris_save)) }
        val feedback = when (saveState) {
            QrSaveState.SAVED -> R.string.settings_qris_saved
            QrSaveState.FAILED -> R.string.settings_qris_save_failed
            else -> null
        }
        if (feedback != null) {
            Text(stringResource(feedback), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.settings_qris_instructions, recipient), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Export the same PNG that is displayed. Only a complete image is published to the gallery. */
internal suspend fun saveDonationQrToGallery(context: Context) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val metadata = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Droide-QRIS.png")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Droide")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(images, metadata) ?: throw IOException("Cannot create gallery image")
    var published = false
    try {
        val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open gallery image")
        stream.use { output ->
            context.resources.openRawResource(R.drawable.donation_qris).use { input -> input.copyTo(output) }
        }
        currentCoroutineContext().ensureActive()
        val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(resolver.update(uri, ready, null, null) == 1) { "Cannot publish gallery image" }
        published = true
    } finally {
        if (!published) resolver.delete(uri, null, null)
    }
}
