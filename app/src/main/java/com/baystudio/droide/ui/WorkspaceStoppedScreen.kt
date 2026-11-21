package com.baystudio.droide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.R

@Composable
internal fun WorkspaceStoppedScreen(
    canOpen: Boolean,
    failure: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IllustratedSessionPage(
        section = stringResource(R.string.session_paused_section),
        title = stringResource(R.string.session_paused_title),
        description = stringResource(R.string.session_paused_description),
        art = SessionIllustration.Paused,
        modifier = modifier,
        failure = failure,
    ) {
        SessionPrimaryAction(stringResource(R.string.session_open), canOpen, onOpen)
        Spacer(Modifier.height(9.dp))
        Text(stringResource(R.string.session_paused_hint), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = SessionPalette.Secondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
