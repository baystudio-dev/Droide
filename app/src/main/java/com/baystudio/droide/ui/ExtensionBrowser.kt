package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baystudio.droide.R
import com.baystudio.droide.core.ExtensionCategory
import com.baystudio.droide.core.ExtensionFamily
import com.baystudio.droide.core.ExtensionState

internal enum class ExtensionBrowseGroup(val title: Int, val examples: Int, val icon: ImageVector) {
    RUN(R.string.ext_run, R.string.ext_run_examples, Icons.Default.PlayArrow),
    ANDROID(R.string.ext_android, R.string.ext_android_examples, Icons.Default.Android),
    AGENTS(R.string.ext_agents, R.string.ext_agents_examples, Icons.Default.SmartToy),
    FORMAT(R.string.ext_format, R.string.ext_format_examples, Icons.Default.FormatAlignLeft);

    fun includes(family: ExtensionFamily): Boolean = when (this) {
        RUN -> family.category == ExtensionCategory.RUNTIMES || family.category == ExtensionCategory.TOOLCHAINS
        ANDROID -> "android" in family.keywords || family.id in setOf("toolchain.jdk", "build.gradle", "build.android-tools", "sdk.android")
        AGENTS -> family.category == ExtensionCategory.PLUGINS && family.keywords.any { it == "agent" || it == "acp" }
        FORMAT -> family.category == ExtensionCategory.FORMATTERS_LINTERS
    }
}

private val ExtensionCategory.title: Int get() = when (this) {
    ExtensionCategory.LANGUAGES -> R.string.ext_cat_languages
    ExtensionCategory.SDKS -> R.string.ext_cat_sdks
    ExtensionCategory.TOOLCHAINS -> R.string.ext_cat_tools
    ExtensionCategory.BUILD_TOOLS -> R.string.ext_cat_build
    ExtensionCategory.RUNTIMES -> R.string.ext_cat_runtime
    ExtensionCategory.PACKAGE_MANAGERS -> R.string.ext_cat_pkgs
    ExtensionCategory.CLI_TOOLS -> R.string.ext_cat_cli
    ExtensionCategory.PLUGINS -> R.string.ext_cat_plugins
    ExtensionCategory.LANGUAGE_SERVERS -> R.string.ext_cat_lsp
    ExtensionCategory.FORMATTERS_LINTERS -> R.string.ext_cat_format
    ExtensionCategory.DEBUGGERS -> R.string.ext_cat_debug
    ExtensionCategory.TESTING -> R.string.ext_cat_test
    ExtensionCategory.FRAMEWORKS -> R.string.ext_cat_framework
    ExtensionCategory.THEMES -> R.string.ext_cat_themes
}

@Composable
internal fun ExtensionPageHeader(detail: Boolean, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (detail) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ext_back), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ext_back)) }
            Text(stringResource(R.string.ext_screen), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        if (onRefresh != null) IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, stringResource(R.string.ext_refresh))
        }
    }
}

@Composable
internal fun ExtensionSearchControls(query: String, onQuery: (String) -> Unit, installed: Boolean, home: Boolean, onTab: (Boolean) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        placeholder = { Text(stringResource(R.string.ext_search)) },
        leadingIcon = { Icon(Icons.Default.Search, stringResource(R.string.ext_search)) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.ext_clear)) } }
        },
    )
    ScrollableTabRow(selectedTabIndex = if (installed) 1 else 0, edgePadding = 8.dp,
        containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }) {
        Tab(selected = !installed, onClick = { onTab(false) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { Text(stringResource(if (home) R.string.ext_browse else R.string.ext_all)) })
        Tab(selected = installed, onClick = { onTab(true) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(stringResource(R.string.ext_installed)) })
    }
}

@Composable
internal fun ExtensionTaskRow(group: ExtensionBrowseGroup, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(group.icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(stringResource(group.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(group.examples), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
}

@Composable
internal fun ExtensionCategoryMenu(category: ExtensionCategory?, onSelect: (ExtensionCategory?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(category?.title ?: R.string.ext_categories))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + ExtensionCategory.entries).forEach { choice ->
                DropdownMenuItem(text = { Text(stringResource(choice?.title ?: R.string.ext_categories)) },
                    onClick = { expanded = false; onSelect(choice) },
                    trailingIcon = { if (choice == category) Icon(Icons.Default.Check, stringResource(R.string.ext_selected)) })
            }
        }
    }
}

@Composable
internal fun extensionPurpose(family: ExtensionFamily): String {
    val label = when (family.id) {
        "runtime.python" -> R.string.ext_py
        "runtime.node" -> R.string.ext_node
        "quality.ruff" -> R.string.ext_ruff
        "quality.prettier" -> R.string.ext_prettier
        "sdk.android" -> R.string.ext_sdk
        else -> if (family.category == ExtensionCategory.LANGUAGES) R.string.ext_syntax else null
    }
    return label?.let { stringResource(it) } ?: family.description
}

@Composable
internal fun ExtensionRow(card: ExtensionCatalogCard, onClick: () -> Unit) {
    val item = card.primary
    val state = item.state
    val status = when (state) {
        ExtensionState.AVAILABLE -> R.string.ext_available
        ExtensionState.INSTALLED, ExtensionState.EXTERNAL -> R.string.ext_installed
        ExtensionState.MANUAL -> R.string.ext_manual
        ExtensionState.PROJECT_DEPENDENCY -> R.string.ext_project
        ExtensionState.BUILT_IN -> R.string.ext_builtin
        ExtensionState.CHECKING -> R.string.ext_checking
        ExtensionState.UNAVAILABLE -> R.string.ext_unavailable
    }
    val action = when (state) {
        ExtensionState.AVAILABLE -> R.string.ext_install
        ExtensionState.INSTALLED, ExtensionState.EXTERNAL -> R.string.ext_manage
        else -> null
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ExtensionBrandIcon(item.family, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.family.name, style = MaterialTheme.typography.titleMedium)
            Text(extensionPurpose(item.family), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(status), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.installed.any { it != item }) extensionInstalledSummary(card)?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
            if (extensionSelectableChoices(card.choices).size > 1) {
                Text(stringResource(R.string.ext_versions_hint), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (action == R.string.ext_install) {
            Button(onClick = onClick, shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(action)) }
        } else if (action != null) {
            OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(action)) }
        } else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 14.dp))
}
