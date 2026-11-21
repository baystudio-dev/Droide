package com.baystudio.droide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R

internal enum class SettingsGroup(val label: Int) {
    WORKBENCH(R.string.settings_group_workbench),
    DEVELOPMENT(R.string.settings_group_development),
    AGENTS(R.string.settings_group_agents),
    APPLICATION(R.string.settings_group_application),
}

@Composable
internal fun SettingsToolbar(directory: Boolean, wide: Boolean, onBack: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (directory) 16.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (directory) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_droide_foreground), null, Modifier.requiredSize(45.dp))
            }
            Text(if (wide) stringResource(R.string.settings_title) else "Droide",
                Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        } else {
            Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = onBack).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back),
                    Modifier.padding(horizontal = 10.dp).size(20.dp))
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleSmall)
            }
        }
        if (!wide) SettingsClose(onClose)
    }
}

@Composable
internal fun SettingsClose(onClose: () -> Unit) {
    IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, stringResource(R.string.settings_close), Modifier.size(21.dp))
    }
}

@Composable
internal fun SettingsTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun ScopeSelector(scope: SettingsScope, onSelected: (SettingsScope) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        for (option in SettingsScope.entries) {
            val active = option == scope
            Box(Modifier.selectable(active, role = Role.Tab) { if (!active) onSelected(option) }
                .widthIn(min = 64.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (option == SettingsScope.USER) R.string.settings_user else R.string.settings_workspace),
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge,
                    color = if (active) colors.primary else colors.onSurfaceVariant)
                if (active) Spacer(Modifier.matchParentSize().wrapContentHeight(Alignment.Bottom)
                    .fillMaxWidth().height(2.dp).background(colors.primary))
            }
        }
    }
}

@Composable
private fun SettingsSearch(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val label = stringResource(R.string.settings_search)
    BasicTextField(value = query, onValueChange = onQuery, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.small)
            .background(colors.surfaceVariant).semantics { contentDescription = label }, decorationBox = { field ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 8.dp)) {
                    if (query.isEmpty()) Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    field()
                }
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.settings_clear), Modifier.size(18.dp))
                }
            }
        })
}

@Composable
internal fun SettingsDirectory(
    categories: List<SettingsCategory>, selected: SettingsCategory?, wide: Boolean,
    scope: SettingsScope, onScope: (SettingsScope) -> Unit, query: String, onQuery: (String) -> Unit,
    state: LazyListState, onSelect: (SettingsCategory) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val headerLimit = maxHeight * .55f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState())) {
                if (!wide) SettingsTitle(stringResource(R.string.settings_title),
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                SettingsSearch(query, onQuery)
                if (!wide) {
                    ScopeSelector(scope, onScope, Modifier.padding(start = 12.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (categories.isEmpty()) item("empty") {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.settings_no_results), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.settings_try_search), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                for (group in SettingsGroup.entries) {
                    val rows = categories.filter { it.group == group }
                    if (rows.isEmpty()) continue
                    item(group) {
                        Text(stringResource(group.label), Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = 17.dp, bottom = 3.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                    }
                    items(rows, key = { it.name }) { category ->
                        val active = wide && selected == category
                        val navigate = { onSelect(category) }
                        Column(Modifier.padding(horizontal = if (wide) 8.dp else 16.dp)) {
                            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall)
                                .background(if (active) colors.primary.copy(alpha = .11f) else Color.Transparent)
                                .then(if (wide) Modifier.selectable(active, role = Role.Button, onClick = navigate)
                                      else Modifier.clickable(role = Role.Button, onClick = navigate))
                                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (wide) Spacer(Modifier.width(3.dp).height(25.dp).background(if (active) colors.primary else Color.Transparent))
                                Icon(category.icon, null, Modifier.padding(start = if (wide) 12.dp else 1.dp).size(20.dp),
                                    tint = if (active) colors.primary else colors.onSurfaceVariant)
                                Text(category.title, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                                if (!wide) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                            }
                            if (!wide) HorizontalDivider(color = colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
