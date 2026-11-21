package com.baystudio.droide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R
import com.baystudio.droide.core.CodeStyleDefaults
import com.baystudio.droide.core.DroideEditorPalette
import com.baystudio.droide.core.IndentStyle
import com.baystudio.droide.core.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsCodePreview(palette: DroideEditorPalette, defaults: CodeStyleDefaults, wrap: Boolean) {
    val inset = if (defaults.indentStyle == IndentStyle.TABS) defaults.tabWidth else defaults.indentSize
    val lines = remember(palette, inset) {
        listOf(buildAnnotatedString {
            withStyle(SpanStyle(color = Color(palette.keyword))) { append("fun") }
            append(" ")
            withStyle(SpanStyle(color = Color(palette.function))) { append("main") }
            append("() {")
        }, buildAnnotatedString {
            append(" ".repeat(inset.coerceIn(1, 16)))
            withStyle(SpanStyle(color = Color(palette.function))) { append("println") }
            append("(")
            withStyle(SpanStyle(color = Color(palette.literal))) { append("\"Droide\"") }
            append(")")
        }, buildAnnotatedString { append("}") })
    }
    val shape = MaterialTheme.shapes.small
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.settings_code_preview)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(palette.background))
        .border(1.dp, colors.outlineVariant, shape).clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FileTypeBrandIcon("main.kt", size = 15.dp)
            Text("main.kt", Modifier.padding(start = 6.dp), color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
        }
        val overflow = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
        Column(Modifier.fillMaxWidth().then(overflow).padding(horizontal = 12.dp, vertical = 8.dp)) {
            lines.forEachIndexed { index, line ->
                Row {
                    Text((index + 1).toString(), color = Color(palette.lineNumber), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                    Text(line, Modifier.padding(start = 12.dp), color = Color(palette.foreground), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, softWrap = wrap)
                }
            }
        }
    }
}

internal fun settingsThemeLabel(name: String) = when (name) {
    "droide-dark" -> "Droide Dark"
    "droide-light" -> "Droide Light"
    "tokyonight" -> "Tokyo Night"
    "matrix" -> "Matrix"
    else -> name
}

@Composable
internal fun SettingsThemeChoices(themes: ThemeManager, currentName: String, enabled: Boolean, onTheme: (String) -> Unit) {
    val options by produceState<List<Pair<String, List<Color>>>?>(null, themes) {
        value = withContext(Dispatchers.IO) {
            themes.list().map { theme ->
                val snapshot = themes.buildSnapshot(theme)
                theme.name to listOf(Color(snapshot.editor.background), snapshot.ui.text, snapshot.ui.primary, Color(snapshot.editor.function))
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        val rows = options
        if (rows == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else for ((name, swatches) in rows) {
            val active = name == currentName
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .selectable(active, enabled = enabled, role = Role.RadioButton) { if (!active) onTheme(name) }
                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(settingsThemeLabel(name), Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    swatches.forEach { color ->
                        Spacer(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(color)
                            .border(1.dp, colors.outlineVariant, RoundedCornerShape(3.dp)))
                    }
                }
                RadioButton(active, onClick = null, enabled = enabled, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

@Composable
internal fun SettingsNumberChoice(label: String, value: Int, options: List<Int>, enabled: Boolean, onValue: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled, role = Role.Button) { open = true },
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 5.dp).size(18.dp), tint = colors.onSurfaceVariant)
        }
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
                for (candidate in options) {
                    DropdownMenuItem(text = { Text(candidate.toString()) }, onClick = {
                        open = false
                        if (candidate != value) onValue(candidate)
                    }, trailingIcon = { RadioButton(candidate == value, onClick = null) })
                }
            }
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
internal fun SettingsIndentStyle(selected: IndentStyle, enabled: Boolean, onValue: (IndentStyle) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.settings_indent_style)
    val choices: @Composable () -> Unit = {
        Row(Modifier.selectableGroup().clip(MaterialTheme.shapes.small).background(colors.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically) {
            for (style in listOf(IndentStyle.SPACES, IndentStyle.TABS)) {
                val active = selected == style
                Box(Modifier.selectable(active, enabled, Role.RadioButton) { if (!active) onValue(style) }
                    .heightIn(min = 48.dp).padding(3.dp).clip(MaterialTheme.shapes.extraSmall)
                    .background(if (active) colors.primaryContainer else Color.Transparent).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(if (style == IndentStyle.SPACES) R.string.settings_spaces else R.string.settings_tabs),
                        color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (maxWidth < 265.dp * LocalDensity.current.fontScale) {
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 4.dp))
                choices()
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f).padding(end = 6.dp), style = MaterialTheme.typography.bodyLarge)
            choices()
        }
    }
    HorizontalDivider(color = colors.outlineVariant)
}
