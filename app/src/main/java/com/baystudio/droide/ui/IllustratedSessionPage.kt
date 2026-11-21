package com.baystudio.droide.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.R

internal object SessionPalette {
    val Background = Color(0xFF0F1923)
    val Panel = Color(0xFF1B2734)
    val Paper = Color(0xFFFAEDD9)
    val Secondary = Color(0xFFDBD7CF)
    val Amber = Color(0xFFF4B55E)
    val Blue = Color(0xFF0072EE)
    val Link = Color(0xFF58B6FF)
    val Stroke = Color(0xFF71808E)
    val Error = Color(0xFFFFB4A7)
}

internal enum class SessionIllustration(@DrawableRes val drawable: Int, val aspect: Float) {
    Paused(R.drawable.session_paused, 896f / 838f),
    Invitation(R.drawable.session_invitation, 960f / 662f),
    Setup(R.drawable.session_setup, 1.45f),
    Download(R.drawable.session_download, 1.5f),
}

/** The Activity supplies safe window bounds. Short panes and enlarged text can scroll independently of the artwork. */
@Composable
internal fun IllustratedSessionPage(
    section: String,
    title: String,
    description: String,
    art: SessionIllustration,
    modifier: Modifier = Modifier,
    failure: String? = null,
    details: (@Composable () -> Unit)? = null,
    notice: (@Composable () -> Unit)? = null,
    footer: @Composable (horizontalActions: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(SessionPalette.Background), contentAlignment = Alignment.Center) {
        val height = maxHeight
        val wide = maxWidth >= 600.dp && maxWidth > height
        val width = maxWidth.coerceAtMost(if (wide) 1_080.dp else 500.dp)
        val compact = height < 690.dp
        val paused = art == SessionIllustration.Paused
        val fontScale = LocalDensity.current.fontScale
        val headingSize = when {
            wide -> 32
            paused && width >= 350.dp -> 48
            width >= 360.dp -> 40
            else -> 35
        }
        val header: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 20.dp else 26.dp)) {
                SessionWordmark(section)
                Spacer(Modifier.height(if (paused && !compact) 24.dp else 12.dp))
                Text(title, Modifier.semantics { heading() }, color = SessionPalette.Paper,
                    fontSize = headingSize.sp, lineHeight = (headingSize + 1).sp, letterSpacing = (-0.8).sp,
                    fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
                if (failure == null) {
                    Text(description, color = SessionPalette.Secondary, fontSize = 17.sp, lineHeight = 22.sp)
                } else {
                    Text(failure, Modifier.semantics { liveRegion = LiveRegionMode.Polite; error(failure) },
                        color = SessionPalette.Error, fontSize = 16.sp, lineHeight = 22.sp)
                }
            }
        }
        val actionArea: @Composable (Boolean) -> Unit = { includeNotice ->
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = if (compact) 8.dp else 14.dp)) {
                if (includeNotice) notice?.invoke()
                footer(wide && height < 500.dp)
            }
        }
        if (wide) {
            Row(Modifier.width(width).fillMaxHeight()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    SessionPoster(height, art, header, details = null)
                }
                val fitActions = details != null && height >= 135.dp * fontScale
                val rightBody: @Composable () -> Unit = {
                    if (details != null) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) { details() }
                    if (notice != null) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { notice() }
                }
                if (fitActions) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center) { rightBody() }
                        actionArea(false)
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .heightIn(min = height), verticalArrangement = Arrangement.Center) {
                        rightBody()
                        actionArea(false)
                    }
                }
            }
        } else if (height < (if (paused) 105.dp else 205.dp) * fontScale) {
            Column(Modifier.width(width).fillMaxHeight().verticalScroll(rememberScrollState())) {
                SessionPoster(0.dp, art, header, details)
                actionArea(true)
            }
        } else {
            Column(Modifier.width(width).fillMaxHeight()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val available = maxHeight
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SessionPoster(available, art, header, details)
                    }
                }
                actionArea(true)
            }
        }
    }
}

/** Measure text at the user's font scale first. Artwork uses the remaining space, down to a scrollable minimum. */
@Composable
private fun SessionPoster(
    available: Dp,
    art: SessionIllustration,
    header: @Composable () -> Unit,
    details: (@Composable () -> Unit)?,
) {
    Layout(
        content = {
            Box { header() }
            SessionArtwork(art, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { details?.invoke() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { children, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val heading = children[0].measure(loose)
        val info = children[2].measure(loose)
        val width = constraints.maxWidth
        val natural = (width / art.aspect).toInt()
        val viewport = available.roundToPx()
        val top = (if (available >= 460.dp) 24.dp else 12.dp).roundToPx()
        val afterHeading = (if (art == SessionIllustration.Paused) 16.dp else 10.dp).roundToPx()
        val afterArt = (if (art == SessionIllustration.Download) 20.dp else 8.dp).roundToPx()
        val fixed = top + heading.height + afterHeading + afterArt + info.height + 6.dp.roundToPx()
        val imageHeight = if (viewport == 0) natural else
            (viewport - fixed).coerceIn(minOf(96.dp.roundToPx(), natural), natural)
        val image = children[1].measure(loose.copy(minHeight = imageHeight, maxHeight = imageHeight))
        val height = maxOf(viewport, fixed + image.height)
        val slack = height - fixed - image.height
        val start = top + minOf(slack / 4, 18.dp.roundToPx())
        layout(width, height) {
            heading.placeRelative(0, start)
            val imageTop = start + heading.height + afterHeading
            image.placeRelative(0, imageTop)
            info.placeRelative(0, imageTop + image.height + afterArt)
        }
    }
}

@Composable
private fun SessionWordmark(section: String) {
    Column {
        Text("DROIDE", color = SessionPalette.Paper, fontSize = 19.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        Text(section, color = SessionPalette.Amber, fontSize = 11.sp, lineHeight = 16.sp,
            letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SessionArtwork(art: SessionIllustration, modifier: Modifier) {
    val duration = if (rememberDroideMotionEnabled()) DroideMotion.ContentMillis else 0
    Crossfade(art, modifier = modifier, animationSpec = tween(duration), label = "Workspace illustration") { scene ->
        Image(painterResource(scene.drawable), contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun SessionPrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit, arrow: Boolean = true) {
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SessionPalette.Blue, contentColor = Color.White,
            disabledContainerColor = SessionPalette.Panel, disabledContentColor = SessionPalette.Secondary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, Modifier.padding(horizontal = if (arrow) 25.dp else 0.dp), textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp)
            if (arrow) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                modifier = Modifier.align(Alignment.CenterEnd).size(22.dp))
        }
    }
}

@Composable
internal fun SessionSecondaryAction(label: String, onClick: () -> Unit, enabled: Boolean = true, accent: Boolean = false) {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SessionPalette.Stroke),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (accent) SessionPalette.Link else SessionPalette.Paper,
            disabledContentColor = SessionPalette.Secondary)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}
