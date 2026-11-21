package com.baystudio.droide.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.R
import com.baystudio.droide.core.EnvironmentSetupController
import com.baystudio.droide.core.EnvironmentSetupPhase
import com.baystudio.droide.core.EnvironmentSetupState
import com.baystudio.droide.core.WorkspaceStartupPhase
import java.math.RoundingMode
import java.text.NumberFormat

/** Once entered, the workspace stays mounted while an explicit setup request covers it. */
@Composable
internal fun EnvironmentSetupHost(controller: EnvironmentSetupController, allowed: Boolean, content: @Composable () -> Unit) {
    val state by controller.state.collectAsState()
    val checking = state.phase == EnvironmentSetupPhase.CHECKING
    val presented = allowed && state.presented && !checking
    val animationMillis = if (rememberDroideMotionEnabled()) 180 else 0
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(presented) { if (presented) { focus.clearFocus(force = true); keyboard?.hide() } }
    Box(Modifier.fillMaxSize()) {
        if (state.enteredWorkspace || !allowed) RetainedWorkspaceContent(!presented, content = content)
        if (allowed && checking && !state.enteredWorkspace) WorkspaceLoadingScreen(WorkspaceStartupPhase.PUBLISH_RUNTIME)
        AnimatedVisibility(presented, enter = fadeIn(tween(animationMillis)), exit = fadeOut(tween(animationMillis))) {
            EnvironmentSetupScreen(state, controller::installFromUser, controller::later, controller::cancel)
        }
    }
    BackHandler(allowed && state.presented) {
        if (state.busy) controller.cancel() else controller.later()
    }
}

@Composable
internal fun EnvironmentSetupScreen(
    state: EnvironmentSetupState, onInstall: () -> Unit, onLater: () -> Unit, onCancel: () -> Unit,
) {
    val downloading = state.phase == EnvironmentSetupPhase.DOWNLOADING
    val canInstall = state.phase == EnvironmentSetupPhase.AVAILABLE || state.phase == EnvironmentSetupPhase.FAILED
    val invitation = canInstall && !state.enteredWorkspace && state.error == null && !state.recoveryRequired
    val art = when {
        downloading -> SessionIllustration.Download
        invitation -> SessionIllustration.Invitation
        else -> SessionIllustration.Setup
    }
    val heading = when (art) {
        SessionIllustration.Download -> R.string.environment_setup_download_title
        SessionIllustration.Invitation -> R.string.environment_setup_title
        else -> R.string.environment_setup_build_title
    }
    val subtitle = when {
        downloading -> R.string.environment_setup_download_source
        state.recoveryRequired && canInstall -> R.string.environment_setup_repair_description
        invitation -> R.string.environment_setup_once
        else -> R.string.environment_setup_build_once
    }
    IllustratedSessionPage(
        section = stringResource(when (art) {
            SessionIllustration.Download -> R.string.environment_setup_section_download
            SessionIllustration.Invitation -> R.string.environment_setup_section_invite
            else -> R.string.environment_setup_section_build
        }),
        title = stringResource(heading),
        description = stringResource(subtitle),
        art = art,
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
        details = {
            if (canInstall) EnvironmentDetails(invitation, state.error)
            else if (state.phase != EnvironmentSetupPhase.READY) EnvironmentProgress(state)
        },
        notice = if (canInstall) ({ EnvironmentDownloadNotice() }) else null,
    ) { horizontalActions ->
        if (canInstall) {
            val action = when {
                state.recoveryRequired -> R.string.environment_setup_repair
                state.phase == EnvironmentSetupPhase.FAILED || state.error != null -> R.string.environment_setup_retry
                else -> R.string.environment_setup_install
            }
            val label = stringResource(action)
            val later = stringResource(R.string.environment_setup_later)
            if (horizontalActions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1.45f)) { SessionPrimaryAction(label, true, onInstall, arrow = false) }
                    Box(Modifier.weight(1f)) { SessionSecondaryAction(later, onLater) }
                }
            } else {
                SessionPrimaryAction(label, true, onInstall, arrow = invitation)
                Spacer(Modifier.height(7.dp))
                SessionSecondaryAction(later, onLater)
            }
        } else if (state.busy) {
            if (downloading) {
                Text(stringResource(R.string.environment_setup_wait), Modifier.fillMaxWidth(),
                    color = SessionPalette.Secondary, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
            }
            val cancel = if (downloading) R.string.environment_setup_cancel_download else R.string.environment_setup_pause
            SessionSecondaryAction(stringResource(cancel), onCancel, enabled = state.phase != EnvironmentSetupPhase.CANCELLING,
                accent = true)
        }
    }
}

@Composable
private fun EnvironmentDownloadNotice() {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 11.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, Modifier.padding(top = 1.dp).size(18.dp), tint = SessionPalette.Paper)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.environment_setup_consent), color = SessionPalette.Secondary,
            fontSize = 13.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun EnvironmentDetails(card: Boolean, failure: String?) {
    val modifier = if (card) Modifier.background(SessionPalette.Panel, RoundedCornerShape(16.dp)).padding(16.dp)
        else Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
    Column(Modifier.fillMaxWidth().then(modifier), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(stringResource(R.string.environment_setup_ubuntu), fontSize = 18.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.Bold, color = SessionPalette.Paper)
        Text(stringResource(R.string.environment_setup_capabilities), fontSize = 15.sp, lineHeight = 19.sp,
            color = SessionPalette.Secondary)
        Text(stringResource(R.string.environment_setup_size), fontSize = 14.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, color = SessionPalette.Link)
        if (failure != null) {
            Text(failure, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, lineHeight = 19.sp,
                color = SessionPalette.Error)
        }
    }
}

@Composable
private fun EnvironmentProgress(state: EnvironmentSetupState) {
    val transfer = state.progress
    val downloading = state.phase == EnvironmentSetupPhase.DOWNLOADING
    val total = transfer.totalBytes?.takeIf { downloading && it > 0 }
    val locale = LocalConfiguration.current.locales[0]
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale).apply { roundingMode = RoundingMode.DOWN } }
    val fraction = transfer.fraction.takeIf { total != null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.environment_setup_ubuntu), Modifier.weight(1f), color = SessionPalette.Paper,
                fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
            if (total != null) {
                val ratio = (transfer.downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0)
                Text(percent.format(ratio), color = SessionPalette.Paper, fontSize = 33.sp, lineHeight = 37.sp,
                    fontWeight = FontWeight.ExtraBold)
            }
        }
        val bar = Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)
        if (fraction == null) LinearProgressIndicator(bar, color = SessionPalette.Blue, trackColor = SessionPalette.Panel)
        else LinearProgressIndicator(progress = { fraction }, modifier = bar, color = SessionPalette.Blue,
            trackColor = SessionPalette.Panel, gapSize = 0.dp, drawStopIndicator = {})
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (downloading && (total != null || transfer.downloadedBytes > 0)) {
                val done = transferSize(transfer.downloadedBytes.coerceAtLeast(0L))
                val bytes = if (total != null) stringResource(R.string.environment_setup_download_progress, done, transferSize(total))
                    else stringResource(R.string.environment_setup_received, done)
                Text(bytes, Modifier.weight(1f).padding(end = 8.dp), color = SessionPalette.Secondary, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                Box(Modifier.size(7.dp).background(SessionPalette.Link, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(setupPhaseText(state.phase)), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = SessionPalette.Secondary, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun transferSize(bytes: Long): String {
    if (bytes < 1_000_000L) return Formatter.formatShortFileSize(LocalContext.current, bytes)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    return stringResource(R.string.environment_setup_megabytes, format.format(bytes / 1_000_000.0))
}

private fun setupPhaseText(phase: EnvironmentSetupPhase): Int = when (phase) {
    EnvironmentSetupPhase.CHECKING -> R.string.environment_setup_checking
    EnvironmentSetupPhase.DOWNLOADING -> R.string.environment_setup_downloading
    EnvironmentSetupPhase.VERIFYING -> R.string.environment_setup_verifying
    EnvironmentSetupPhase.EXTRACTING -> R.string.environment_setup_extracting
    EnvironmentSetupPhase.CONFIGURING -> R.string.environment_setup_configuring
    EnvironmentSetupPhase.CANCELLING -> R.string.environment_setup_pausing
    EnvironmentSetupPhase.READY -> R.string.environment_setup_ready
    else -> R.string.environment_setup_preparing
}
