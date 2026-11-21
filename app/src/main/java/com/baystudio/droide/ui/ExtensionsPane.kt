package com.baystudio.droide.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.baystudio.droide.R
import com.baystudio.droide.core.DeclarativeExtensionRuntime
import com.baystudio.droide.core.ExecutionScope
import com.baystudio.droide.core.ExtensionMarketplaceManager
import com.baystudio.droide.core.MarketplaceExtensionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import com.baystudio.droide.core.AndroidDevelopmentManager
import com.baystudio.droide.core.DevelopmentExtensionsManager
import com.baystudio.droide.core.ExtensionUpdatePolicy
import com.baystudio.droide.core.ExtensionCategory
import com.baystudio.droide.core.ExtensionState
import com.baystudio.droide.core.ExtensionVersionState
import com.baystudio.droide.core.DeviceBridgeManager
import com.baystudio.droide.core.resolvedVersion
import com.baystudio.droide.core.UbuntuAptCatalog
import com.baystudio.droide.core.LanguageRuntimePresentation
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ExtensionSelection(val familyId: String, val version: String, val scope: ExecutionScope? = null) : java.io.Serializable {
    fun matches(item: ExtensionVersionState): Boolean =
        item.family.id == familyId && item.version.version == version && (scope == null || scope == item.version.scope)
    companion object {
        fun from(item: ExtensionVersionState) = ExtensionSelection(item.family.id, item.version.version, item.version.scope)
    }
}

/** Keep deep-link navigation pending until discovery replaces the initial detection placeholders. */
@Composable
internal fun ExtensionNavigationEffects(
    items: List<ExtensionVersionState>,
    initialFamilyId: String?,
    initialSnapshotPending: Boolean,
    selection: ExtensionSelection?,
    onSelect: (ExtensionSelection?) -> Unit,
    onInitialFamilyHandled: () -> Unit,
) {
    LaunchedEffect(items, initialFamilyId, initialSnapshotPending, selection) {
        if (initialFamilyId != null) {
            if (!initialSnapshotPending) {
                extensionDefaultChoice(items.filter { it.family.id == initialFamilyId })?.let { choice ->
                    onSelect(ExtensionSelection.from(choice))
                    onInitialFamilyHandled()
                }
            }
        } else if (selection != null && items.none(selection::matches)) {
            val replacement = extensionDefaultChoice(items.filter { it.family.id == selection.familyId })
            onSelect(replacement?.let(ExtensionSelection::from))
        }
    }
}

/** Lifecycle refreshes only; the toolbar's explicit refresh remains a fresh manager call. */
@Composable
internal fun ExtensionCatalogRefreshEffects(
    managerIdentity: Any,
    activeFile: String,
    bridgeConnection: Any?,
    installerState: com.baystudio.droide.core.ManagedExtensionInstaller.State,
    refresh: suspend (String) -> Unit,
    onFailure: (String) -> Unit,
    environmentRevision: Long = 0L,
) {
    // This is one view's handled-input marker, not a catalog cache. Explicit
    // refreshes and every real terminal installer transition remain fresh requests.
    var handledInputs by remember(managerIdentity) { mutableStateOf<Triple<String, Any?, Long>?>(null) }
    LaunchedEffect(managerIdentity, activeFile, bridgeConnection, installerState.phase, installerState.operationActive, environmentRevision) {
        if (installerState.running) return@LaunchedEffect
        val inputs = Triple(activeFile, bridgeConnection, environmentRevision)
        val inputsChanged = handledInputs != inputs
        val terminalPhase = installerState.phase in setOf(
            com.baystudio.droide.core.ManagedExtensionInstaller.Phase.READY,
            com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED,
            com.baystudio.droide.core.ManagedExtensionInstaller.Phase.CANCELED,
        )
        if (!inputsChanged && !terminalPhase) return@LaunchedEffect
        val result = runSuspendCatching { refresh(activeFile) }
        // A non-cooperative callback can return/fail after cancellation. It cannot
        // publish an obsolete error or mark these inputs as handled.
        currentCoroutineContext().ensureActive()
        result.onFailure { if (inputsChanged) onFailure(it.message ?: "Extensions refresh failed") }
        // Cancellation escapes runSuspendCatching. Do not mark cancelled inputs as
        // handled: a phase change must not strand the latest file/bridge request.
        handledInputs = inputs
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsPane(
    manager: DevelopmentExtensionsManager,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    activeFile: String,
    initialFamilyId: String? = null,
    environmentRevision: Long = 0L,
    onOpenTerminal: (() -> Unit)? = null,
    onExit: (() -> Unit)? = null,
    handleSystemBack: Boolean = true,
    onInitialFamilyHandled: () -> Unit = {},
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val exitPage = onExit ?: { dispatcher?.onBackPressed(); Unit }
    manager.temporaryRecoveryReason?.let { reason ->
        BackHandler(enabled = handleSystemBack && onExit != null, onBack = exitPage)
        Column {
            ExtensionPageHeader(false, exitPage)
            Text(reason + " Normal startup returns after the app process restarts.", modifier = Modifier.padding(16.dp))
        }
        return
    }

    val snapshot by manager.snapshot.collectAsState()
    val installerState by manager.installer.state.collectAsState()
    val marketplaceSnapshot by manager.marketplace.snapshot.collectAsState()
    val bridgeState by deviceBridge.state.collectAsState()
    val scope = rememberWorkspaceCoroutineScope()
    var query by rememberSaveable(manager.workspaceId) { mutableStateOf("") }
    var category by rememberSaveable(manager.workspaceId) { mutableStateOf<ExtensionCategory?>(null) }
    var group by rememberSaveable(manager.workspaceId) { mutableStateOf<ExtensionBrowseGroup?>(null) }
    var allCatalog by rememberSaveable(manager.workspaceId) { mutableStateOf(false) }
    var installedOnly by rememberSaveable(manager.workspaceId) { mutableStateOf(false) }
    var selectedIdentity by rememberSaveable(manager.workspaceId) { mutableStateOf<ExtensionSelection?>(null) }
    var openedByDeepLink by rememberSaveable(manager.workspaceId) { mutableStateOf(false) }
    var familyHistory by rememberSaveable(manager.workspaceId) { mutableStateOf(arrayListOf<ExtensionSelection>()) }
    var openedFromInstalled by rememberSaveable(manager.workspaceId) { mutableStateOf(false) }
    var marketplaceSelection by rememberSaveable(manager.workspaceId) { mutableStateOf<String?>(null) }
    val selectedMarketplace = remember(marketplaceSelection) { marketplaceSelection?.let { Json.decodeFromString<MarketplaceExtensionSummary>(it) } }
    var refreshMessage by remember(manager) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val home = group == null && !allCatalog && !installedOnly && query.isBlank()
    val listState = key(group, allCatalog, installedOnly, category, query.trim()) { rememberLazyListState() }

    fun back() {
        when {
            selectedMarketplace != null -> marketplaceSelection = null
            selectedIdentity != null -> {
                selectedIdentity = familyHistory.lastOrNull()
                if (familyHistory.isEmpty() && openedByDeepLink) {
                    openedByDeepLink = false
                    exitPage()
                } else familyHistory = ArrayList(familyHistory.dropLast(1))
            }
            query.isNotEmpty() -> { focus.clearFocus(); query = "" }
            installedOnly -> installedOnly = false
            category != null -> category = null
            group != null || allCatalog -> { group = null; allCatalog = false }
            else -> exitPage()
        }
    }
    BackHandler(enabled = handleSystemBack && (onExit != null || !home || selectedIdentity != null || selectedMarketplace != null), onBack = ::back)
    ExtensionNavigationEffects(
        items = snapshot.items, initialFamilyId = initialFamilyId,
        initialSnapshotPending = snapshot.message == "Checking extensions…", selection = selectedIdentity,
        onSelect = { selectedIdentity = it; marketplaceSelection = null },
        onInitialFamilyHandled = { familyHistory = arrayListOf(); openedByDeepLink = true; onInitialFamilyHandled() },
    )
    val selected = selectedIdentity?.let { id -> snapshot.items.firstOrNull(id::matches) }
    ExtensionCatalogRefreshEffects(
        manager, activeFile, bridgeState.connected, installerState,
        refresh = { manager.refresh(it); Unit }, environmentRevision = environmentRevision,
        onFailure = { refreshMessage = it },
    )
    val remoteQuery = query.trim()
    val searchRemote = !installedOnly && category == null && group == null &&
        remoteQuery.length >= ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS
    LaunchedEffect(manager, searchRemote, remoteQuery) {
        if (searchRemote) { delay(350); manager.marketplace.search(remoteQuery) }
    }
    val refresh: () -> Unit = {
        scope.launch {
            runSuspendCatching { manager.refreshUserEnvironment(activeFile) }
                .onSuccess { refreshMessage = null; if (searchRemote) manager.marketplace.search(remoteQuery) }
                .onFailure { refreshMessage = it.message ?: "Extensions refresh failed" }
        }
    }
    val detail = selected != null || selectedMarketplace != null
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ExtensionPageHeader(detail, ::back, if (detail) null else refresh)
        if (installerState.running && selected?.family?.id != installerState.familyId) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(installerState.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = { manager.installer.cancelInstallation() }) { Text(stringResource(R.string.ext_cancel_install)) }
            }
            if (installerState.log.isNotBlank()) Text(installerState.log.takeLast(800),
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2)
        }
        val notice = refreshMessage ?: marketplaceSnapshot.message.takeIf {
            !detail && searchRemote && marketplaceSnapshot.query.equals(remoteQuery, ignoreCase = true) &&
                (marketplaceSnapshot.loading || marketplaceSnapshot.offlineCache)
        }
        if (!notice.isNullOrBlank()) DismissibleNotice(notice, ownerKey = manager,
            color = if (marketplaceSnapshot.offlineCache) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        when {
            selectedMarketplace != null -> MarketplaceExtensionDetail(selectedMarketplace, manager, openedFromInstalled)
            selected != null -> ExtensionDetail(
                item = selected, manager = manager, onOpenTerminal = onOpenTerminal,
                onSelectItem = { next ->
                    if (next.family.id != selected.family.id) familyHistory = ArrayList(familyHistory + ExtensionSelection.from(selected))
                    selectedIdentity = ExtensionSelection.from(next)
                }, onRefresh = { scope.launch { runSuspendCatching { manager.refreshUserEnvironment(activeFile) } } },
            )
            else -> {
                ExtensionSearchControls(query, { query = it.take(120) }, installedOnly, group == null && !allCatalog && query.isBlank()) {
                    focus.clearFocus()
                    if (installedOnly != it) {
                        installedOnly = it
                    } else if (!it && (group != null || category != null)) {
                        group = null
                        category = null
                        allCatalog = true
                    }
                }
                val filtered = remember(snapshot.items, remoteQuery, category, group, installedOnly, home) {
                    if (home) emptyList() else extensionCatalogCards(snapshot.items, remoteQuery, category.takeUnless { installedOnly }).filter { card ->
                        (installedOnly || group?.includes(card.primary.family) != false) && (!installedOnly || card.installed.isNotEmpty())
                    }
                }
                val marketplaceOperation by manager.marketplace.operation.collectAsState()
                val marketItems = if (installedOnly) {
                    remember(snapshot.items, marketplaceOperation.phase, remoteQuery) {
                        DeclarativeExtensionRuntime.records().filter { record ->
                            listOf(record.extensionId, record.displayName, record.publisher).any { it.contains(remoteQuery, true) }
                        }.map { record ->
                            MarketplaceExtensionSummary(record.extensionId, record.publisher, record.extensionId.substringAfter('.'),
                                record.displayName, record.version, metadataUrl = "")
                        }
                    }
                } else if (searchRemote && marketplaceSnapshot.query.equals(remoteQuery, ignoreCase = true)) {
                    marketplaceSnapshot.page?.extensions.orEmpty()
                } else emptyList()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item("heading") {
                        Text(stringResource(when {
                            installedOnly -> R.string.ext_offline_records
                            query.isNotBlank() -> R.string.ext_results
                            home -> R.string.ext_by_task
                            else -> group?.title ?: R.string.ext_catalog
                        }), Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (home) {
                        items(ExtensionBrowseGroup.entries.toList(), key = { it.name }) { task ->
                            ExtensionTaskRow(task) { focus.clearFocus(); group = task }
                        }
                        item("all") { TextButton(onClick = { focus.clearFocus(); allCatalog = true },
                            modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.ext_all_packages)) } }
                    } else {
                        if (allCatalog && group == null && !installedOnly) item("categories") {
                            Box(Modifier.padding(horizontal = 8.dp)) { ExtensionCategoryMenu(category) { category = it } }
                        }
                        items(filtered, key = { "local:" + it.primary.family.id }) { card ->
                            ExtensionRow(card) {
                                focus.clearFocus()
                                openedByDeepLink = false
                                familyHistory = arrayListOf()
                                selectedIdentity = ExtensionSelection.from(card.primary)
                            }
                        }
                        if (filtered.isEmpty() && marketItems.isEmpty()) item("empty") {
                            Text(stringResource(if (installedOnly) R.string.ext_none_installed else R.string.ext_empty),
                                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (searchRemote || marketItems.isNotEmpty()) {
                            item("open-vsx-heading") {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(if (installedOnly) R.string.ext_editor_extensions else R.string.ext_openvsx),
                                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    if (searchRemote && marketplaceSnapshot.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                            items(marketItems, key = { "ovsx:" + it.id + "@" + it.version }) { offer ->
                                MarketplaceExtensionRow(offer, manager, installedOnly) {
                                    focus.clearFocus(); openedFromInstalled = installedOnly; marketplaceSelection = Json.encodeToString(offer)
                                }
                            }
                            if (searchRemote && !marketplaceSnapshot.loading && marketItems.isEmpty() &&
                                marketplaceSnapshot.query.equals(remoteQuery, true)) item("open-vsx-empty") {
                                Text(marketplaceSnapshot.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun extensionDetailStatus(item: ExtensionVersionState): String {
    val detail = item.detail.trim()
    val concise = detail.takeIf { it.isNotBlank() && it.length <= 80 }
    return when (item.state) {
        ExtensionState.BUILT_IN -> if (item.family.category == ExtensionCategory.LANGUAGES) LanguageRuntimePresentation.syntaxStatus(item.family.id.removePrefix("language.")) else "Built into Droide"
        ExtensionState.INSTALLED -> concise ?: "Installed"
        ExtensionState.EXTERNAL -> concise ?: "Detected on workstation"
        ExtensionState.AVAILABLE -> concise ?: "Ready to install"
        ExtensionState.MANUAL -> "Managed through the Ubuntu terminal"
        ExtensionState.PROJECT_DEPENDENCY -> "Managed by the project"
        ExtensionState.CHECKING -> "Checking installation status…"
        ExtensionState.UNAVAILABLE -> concise ?: "Not available for this device or setup"
    }
}

@Composable
private fun MarketplaceExtensionRow(
    item: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    installedLibrary: Boolean,
    onClick: () -> Unit,
) {
    val installedVersion = manager.marketplace.installedVersion(item.id)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 68.dp).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MarketplaceBrandIcon(item, manager, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium)
            Text(if (installedLibrary) item.namespace else "${item.namespace} · Open VSX",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            if (item.deprecated) Text("Deprecated", style = MaterialTheme.typography.labelMedium)
            if (installedVersion != null) Text(stringResource(R.string.ext_installed_version, installedVersion), style = MaterialTheme.typography.labelMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 14.dp))
}

@Composable
private fun MarketplaceBrandIcon(
    item: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    size: androidx.compose.ui.unit.Dp,
) {
    val iconPath by produceState<String?>(initialValue = item.iconCachePath, item.id, item.iconUrl) {
        if (value == null) value = manager.marketplace.icon(item)
    }
    val path = iconPath
    if (path != null && File(path).isFile) {
        AsyncImage(
            model = File(path),
            contentDescription = "${item.displayName} icon",
            modifier = Modifier.size(size),
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
        ) {
            Icon(Icons.Default.Extension, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarketplaceExtensionDetail(
    summary: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    fromInstalled: Boolean,
) {
    val scope = rememberWorkspaceCoroutineScope()
    val operation by manager.marketplace.operation.collectAsState()
    var inspection by remember(summary.id) { mutableStateOf<com.baystudio.droide.core.MarketplaceInspection?>(null) }
    var loading by remember(summary.id) { mutableStateOf(true) }
    var message by remember(summary.id) { mutableStateOf<String?>(null) }
    var confirmInstall by remember(summary.id) { mutableStateOf(false) }
    var confirmUninstall by remember(summary.id) { mutableStateOf(false) }

    val installedVersion = remember(summary.id, operation.running) { manager.marketplace.installedVersion(summary.id) }
    val owned by produceState<Boolean?>(null, summary.id, installedVersion, operation.running) {
        if (operation.running) return@produceState
        value = if (installedVersion == null) false else runSuspendCatching { manager.marketplace.ownsInstalledRecord(summary.id) }.getOrDefault(false)
    }
    val rollbackAvailable by produceState(false, summary.id, owned, operation.running) {
        if (operation.running) return@produceState
        value = owned == true && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.marketplace.rollbackAvailable(summary.id) }
    }
    LaunchedEffect(summary.id, fromInstalled, if (fromInstalled) owned else null) {
        if (fromInstalled && owned != true) {
            loading = owned == null
            inspection = null
            return@LaunchedEffect
        }
        loading = true
        runSuspendCatching { manager.marketplace.inspect(summary.id) }
            .onSuccess { inspection = it }
            .onFailure { message = it.message ?: "Could not inspect marketplace extension" }
        loading = false
    }
    val current = inspection

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarketplaceBrandIcon(summary, manager, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(current?.detail?.displayName ?: summary.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (fromInstalled && owned != true) summary.namespace else "${current?.detail?.namespace ?: summary.namespace} · Open VSX",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking extension…", style = MaterialTheme.typography.bodySmall)
        }

        installedVersion?.let { DetailLine(stringResource(R.string.ext_installed), it) }
        if (fromInstalled && owned == false && installedVersion != null) Text(stringResource(R.string.ext_external_editor))
        message?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.primary) }
        current?.let { inspected ->
            val detail = inspected.detail
            Text(detail.description, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            DetailLine("Version", detail.version)
            DetailLine("Publisher", detail.namespace)
            DetailLine("Publisher status", if (detail.verifiedPublisher) "Verified" else "Not verified")
            DetailLine("Trusted publishing", if (detail.trustedPublishing) "Yes" else "Not reported")
            detail.license?.let { DetailLine("License", it) }
            detail.targetPlatform?.let { DetailLine("Registry target", it) }
            inspected.engineConstraint?.let { DetailLine("VS Code engine", it) }
            DetailLine(
                "Compatibility",
                inspected.compatibility.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            )
            DetailLine("Install mode", if (inspected.installable) "Declarative data only" else "Unavailable in Droide")
            Text(
                "Install supported language definitions, grammars, snippets and themes from Open VSX. Extensions requiring the VS Code runtime are not supported here. Tools use separate installers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inspected.supportedContributionKinds.isNotEmpty()) {
                DetailLine("Contributes", inspected.supportedContributionKinds.sorted().joinToString(", "))
            }
            if (inspected.extensionDependencies.isNotEmpty()) {
                DetailLine("Dependencies", inspected.extensionDependencies.sorted().joinToString(", "))
            }
            if (detail.deprecated) {
                Text("Deprecated by the registry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!detail.verifiedPublisher) {
                Text(
                    "Publisher not verified by Open VSX. Only supported declarative resources can be installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            inspected.reasons.forEach { reason ->
                Text("• $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (operation.extensionId == summary.id && operation.message.isNotBlank()) {
                Text(operation.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (operation.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            val installLabel = if (installedVersion == null) R.string.ext_install else if (installedVersion != detail.version) R.string.ext_update else null
            if (installLabel != null && inspected.installable) {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        if (detail.verifiedPublisher) {
                            scope.launch {
                                message = null
                                runSuspendCatching { manager.marketplace.install(summary.id) }
                                    .onSuccess { record ->
                                        message = "${record.displayName} ${record.version} is active"
                                        inspection = runSuspendCatching { manager.marketplace.inspect(summary.id, forceRefresh = true) }.getOrNull() ?: inspection
                                    }
                                    .onFailure { message = it.message ?: "Marketplace install failed" }
                            }
                        } else confirmInstall = true
                    },
                ) { Text(stringResource(installLabel)) }
            }
            if (!inspected.installable) {
                Text(
                    if (inspected.executable) "Install disabled: this package needs VS Code executable activation, which this Droide marketplace cannot run." else "Install disabled: package is blocked or outside Droide's supported declarative subset. See compatibility reasons above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (owned == true && installedVersion != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rollbackAvailable) {
                    OutlinedButton(
                        enabled = !operation.running,
                        onClick = {
                            scope.launch {
                                message = null
                                runSuspendCatching { manager.marketplace.rollback(summary.id) }
                                    .onSuccess { message = "Rolled back to ${it.version}" }
                                    .onFailure { message = it.message ?: "Rollback failed" }
                            }
                        },
                    ) { Text("Rollback") }
                }
                TextButton(enabled = !operation.running, onClick = { confirmUninstall = true }) { Text("Uninstall") }
            }
        }
    }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmInstall = false },
            title = { Text("Install unverified publisher extension?") },
            text = {
                Text("Publisher not verified. Executable code is blocked.")
            },
            confirmButton = {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        confirmInstall = false
                        scope.launch {
                            message = null
                            runSuspendCatching { manager.marketplace.install(summary.id) }
                                .onSuccess { message = "${it.displayName} ${it.version} is active" }
                                .onFailure { message = it.message ?: "Marketplace install failed" }
                        }
                    },
                ) { Text("Install data-only") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text(stringResource(R.string.ext_cancel)) } },
        )
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmUninstall = false },
            title = { Text("Uninstall ${summary.displayName}?") },
            text = { Text("Remove this extension? Rollback history is kept temporarily.") },
            confirmButton = {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        confirmUninstall = false
                        scope.launch {
                            message = null
                            runSuspendCatching { manager.marketplace.uninstall(summary.id) }
                                .onSuccess { message = "Extension removed" }
                                .onFailure { message = it.message ?: "Uninstall failed" }
                        }
                    },
                ) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.ext_cancel)) } },
        )
    }
}

@Composable
private fun ExtensionStateBadge(state: ExtensionState, modifier: Modifier = Modifier) {
    val (image, tint) = when (state) {
        ExtensionState.BUILT_IN, ExtensionState.INSTALLED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        ExtensionState.UNAVAILABLE -> Icons.Default.Warning to MaterialTheme.colorScheme.onSurfaceVariant
        else -> return
    }
    Surface(
        modifier = modifier.size(17.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Icon(image, state.name, modifier = Modifier.padding(1.dp), tint = tint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionDetail(
    item: ExtensionVersionState,
    manager: DevelopmentExtensionsManager,
    onSelectItem: (ExtensionVersionState) -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onRefresh: () -> Unit,
) {
    val scope = rememberWorkspaceCoroutineScope()
    val snapshot by manager.snapshot.collectAsState()
    val installState by manager.installer.state.collectAsState()
    val androidProviderState by manager.installer.androidProviderState.collectAsState()
    val packageProviderState by manager.installer.packageProviderState.collectAsState()
    var plan by remember(item.family.id, item.version.version, item.version.scope) { mutableStateOf<com.baystudio.droide.core.ExtensionInstallPlan?>(null) }
    var installJob by remember(item.family.id, item.version.version, item.version.scope) { mutableStateOf<Job?>(null) }
    var installMessage by remember(item.family.id, item.version.version, item.version.scope) { mutableStateOf<String?>(null) }
    var confirmUninstall by remember(item.family.id, item.version.version, item.version.scope) { mutableStateOf(false) }
    var elpHealthRequest by remember(item.family.id,item.version.version) { mutableStateOf<Pair<com.baystudio.droide.core.ExtensionInstallPlan.LocalVendor,com.baystudio.droide.core.ElpRuntimeHealthApprovalRequest>?>(null) }
    var showVersionRequest by remember(item.family.id) { mutableStateOf(false) }
    var requestedVersion by remember(item.family.id) { mutableStateOf("latest") }
    val reviewedAndroid = extensionReviewedAndroidChoice(snapshot.items, item.family.id)
    val updatePolicy = remember(item.family) { ExtensionUpdatePolicy.forFamily(item.family) }
    val uriHandler = LocalUriHandler.current
    fun prepareVersion(value: String) {
        if (installJob != null || installState.running) return
        installMessage = null
        installJob = scope.launch {
            try {
                runSuspendCatching { manager.prepareRequestedVersion(item.family.id, value) }
                    .onSuccess { prepared -> plan = prepared }
                    .onFailure { installMessage = it.message ?: "Could not resolve this version" }
            } finally { installJob = null }
        }
    }
    if (showVersionRequest) {
        AlertDialog(onDismissRequest = { showVersionRequest = false },
            title = { Text(stringResource(R.string.ext_version_of, item.family.name)) },
            text = { Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (reviewedAndroid != null) stringResource(R.string.ext_reviewed_hint, reviewedAndroid.version.version)
                    else stringResource(R.string.ext_latest_hint))
                OutlinedTextField(value = requestedVersion, onValueChange = { requestedVersion = it.take(100) },
                    label = { Text(stringResource(R.string.ext_version)) }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { showVersionRequest = false; prepareVersion(requestedVersion) }, enabled = requestedVersion.isNotBlank()) { Text(stringResource(R.string.ext_continue)) } },
            dismissButton = { TextButton(onClick = { showVersionRequest = false }) { Text(stringResource(R.string.ext_cancel)) } })
    }
    val versionChoices = remember(snapshot.items, item.family.id) {
        extensionSelectableChoices(snapshot.items.filter { it.family.id == item.family.id })
    }
    DisposableEffect(item.family.id, item.version.version, item.version.scope) {
        onDispose { installJob?.cancel() }
    }

    var showTechnical by rememberSaveable(item.family.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ExtensionBrandIcon(family = item.family, size = 48.dp)
                    ExtensionStateBadge(item.state, Modifier.align(Alignment.BottomEnd))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.family.name, style = MaterialTheme.typography.headlineSmall)
                    if (item.family.publisher.isNotBlank()) {
                        Text(item.family.publisher, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(extensionPurpose(item.family), style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            DetailLine(stringResource(R.string.ext_version), if (item.family.category == ExtensionCategory.LANGUAGES) "Editor syntax" else item.version.version)
            if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT) {
                snapshot.androidDownloadBytes[item.family.id to item.version.version]?.let { bytes ->
                    DetailLine(stringResource(R.string.ext_download), stringResource(R.string.ext_package_size, bytes / (1024.0 * 1024.0)))
                }
            }
            DetailLine(stringResource(R.string.ext_environment), extensionExecutionLabel(item))

            if (updatePolicy.hasVersionPicker && versionChoices.any {
                    it.state == ExtensionState.AVAILABLE || it.state == ExtensionState.INSTALLED || it.state == ExtensionState.EXTERNAL
                }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (reviewedAndroid != null) {
                        if (!ExtensionSelection.from(reviewedAndroid).matches(item)) Button(onClick = { prepareVersion(reviewedAndroid.version.version) },
                            enabled = reviewedAndroid.state == ExtensionState.AVAILABLE && installJob == null && !installState.running) {
                            Text(stringResource(if (reviewedAndroid.state == ExtensionState.INSTALLED) R.string.ext_installed_version else R.string.ext_confirmed_review, reviewedAndroid.version.version))
                        }
                    } else {
                        Button(onClick = { prepareVersion("latest") }, enabled = installJob == null && !installState.running) { Text(stringResource(R.string.ext_updated)) }
                    }
                    OutlinedButton(onClick = { showVersionRequest = true }, enabled = installJob == null && !installState.running) { Text(stringResource(R.string.ext_pick)) }
                }
            }
            for (relatedFamily in updatePolicy.relatedFamilies) {
                val target = extensionDefaultChoice(snapshot.items.filter { it.family.id == relatedFamily })
                if (target != null) TextButton(onClick = { onSelectItem(target) }, enabled = installJob == null && !installState.running) {
                    Text("Open ${target.family.name}")
                }
            }
            updatePolicy.documentationUrl?.let { url ->
                TextButton(onClick = { runCatching { uriHandler.openUri(url) }
                    .onFailure { installMessage = "Could not open update instructions: $url" } }) {
                    Text(stringResource(R.string.ext_update_instructions))
                }
            }
            if (versionChoices.any { it.version.version != item.version.version || it.version.scope != item.version.scope }) {
                ExtensionVersionChoices(
                    choices = versionChoices,
                    selected = item,
                    enabled = installJob == null && !installState.running,
                    onSelect = onSelectItem,
                )
            }
            if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) {
                Text(
                    "Managed Android components share one JDK/SDK/Build Tools pack. Removing any component removes the entire pack and its managed records; individual versions cannot be removed here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            HorizontalDivider()
            TextButton(onClick = { showTechnical = !showTechnical }) {
                Text(stringResource(R.string.ext_technical))
                Icon(if (showTechnical) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showTechnical) {
                Text(updatePolicy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (extensionPurpose(item.family) != item.family.description) Text(item.family.description, style = MaterialTheme.typography.bodySmall)
                DetailLine("Category", item.family.category.label)
                DetailLine("Channel", item.version.channel)
                DetailLine("Install mode", when (item.version.installKind) {
                    com.baystudio.droide.core.ExtensionInstallKind.BUILT_IN -> if (item.family.category == ExtensionCategory.LANGUAGES) "Editor syntax only" else "Bundled Droide feature"
                    com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> "Managed Android pack · shared removal"
                    com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> "Reviewed Android SDK component · local Ubuntu"
                    com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                    com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                    com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE -> "Reviewed managed tool"
                    com.baystudio.droide.core.ExtensionInstallKind.USER_MANAGED -> "User-managed upstream version"
                    com.baystudio.droide.core.ExtensionInstallKind.CATALOG_ONLY ->
                        if (item.state == ExtensionState.EXTERNAL) "Detected tool · managed outside Droide" else "Detection only · no managed recipe"
                })
                if (item.version.provides.isNotEmpty()) DetailLine("Provides", item.version.provides.sorted().joinToString(", "))
                if (item.version.requires.isNotEmpty()) DetailLine("Requires", item.version.requires.sorted().joinToString(", "))
            }
            if (item.family.category == ExtensionCategory.LANGUAGES) {
                val languageId = item.family.id.removePrefix("language.")
                when (LanguageRuntimePresentation.kind(languageId)) {
                    LanguageRuntimePresentation.Kind.DATA_OR_DOCUMENT -> {
                        DetailLine("Runtime / compiler", "No standalone runtime / compiler required")
                        Text("Syntax highlighting is ready. Add a language server or formatter for more features.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    LanguageRuntimePresentation.Kind.LINUX_SHELL -> {
                        val shell = LanguageRuntimePresentation.shellStatus(snapshot.localLinuxExecutables)
                        DetailLine("Linux shell", shell.detail)
                        Text(shell.guidance, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onRefresh, enabled = !installState.running) { Text("Verify shell status") }
                    }
                    LanguageRuntimePresentation.Kind.SEPARATE_TOOL -> {
                        val familyId = UbuntuAptCatalog.languageRuntime[languageId]
                        val choices = snapshot.items.filter { it.family.id == familyId }
                        val installed = choices.firstOrNull { it.state in setOf(ExtensionState.INSTALLED, ExtensionState.EXTERNAL) }
                        DetailLine("Runtime / compiler", installed?.let { "${it.family.name}: ${it.detail}" }
                            ?: if (familyId == null) "No managed runtime mapped" else "Not installed")
                        val target = installed ?: choices.firstOrNull { it.state == ExtensionState.AVAILABLE && it.version.recommended }
                            ?: choices.firstOrNull { it.state == ExtensionState.AVAILABLE }
                        if (target != null) Button(onClick = { onSelectItem(target) }, enabled = !installState.running) {
                            Text(if (installed != null) "Open runtime / compiler" else "Install runtime / compiler")
                        } else {
                            OutlinedButton(onClick = {}, enabled = false) { Text("Runtime installer unavailable") }
                            Text("Editor support is ready. Install a compatible compiler or runtime in the Linux terminal, if available.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(
                extensionDetailStatus(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            if(item.family.id=="lsp.elp" && item.state==ExtensionState.INSTALLED) {
                OutlinedButton(enabled=installJob==null&&!installState.running,onClick={
                    installJob=scope.launch { try {
                        runSuspendCatching { manager.installer.prepare(item) as com.baystudio.droide.core.ExtensionInstallPlan.LocalVendor }
                            .onSuccess { prepared -> runSuspendCatching{manager.installer.requestElpHealthApproval(prepared)}
                                .onSuccess{elpHealthRequest=prepared to it}.onFailure{installMessage=it.message} }
                            .onFailure{installMessage=it.message}
                    } finally {installJob=null} }
                }) {Text("Approve runtime health recheck")}
            }
            installMessage?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.primary) }
            if (installState.familyId == item.family.id && installState.message.isNotBlank()) {
                Text(installState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (installState.familyId == item.family.id && installState.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { manager.installer.cancelInstallation(); installJob?.cancel() }) { Text(stringResource(R.string.ext_cancel)) }
            }
            if (installState.familyId == item.family.id && installState.log.isNotBlank()) {
                Text(installState.log, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
            if (item.state == ExtensionState.UNAVAILABLE) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall)
            }

            when (item.state) {
                ExtensionState.MANUAL, ExtensionState.PROJECT_DEPENDENCY -> onOpenTerminal?.let { open ->
                    Button(onClick = open) {
                        Text(if (item.state == ExtensionState.PROJECT_DEPENDENCY) "Open project terminal" else "Open Terminal")
                    }
                }
                ExtensionState.INSTALLED -> {
                    val record = manager.registry.find(item.family.id, item.version.version)
                    val workspaceSelected = manager.workspaceVersion(item.family.id) == item.version.version
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh, enabled = installJob == null && !installState.running) { Text("Verify status") }
                        if (item.version.installKind in setOf(
                                com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                                com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                                com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                                com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                            ) && record != null && !record.active) {
                            Button(
                                enabled = installJob == null && !installState.running,
                                onClick = {
                                    installJob = scope.launch {
                                        try {
                                            runSuspendCatching { manager.unifiedAuthority.activate(item.family.id, item.version.version) }
                                                .onSuccess { installMessage = it.message; if (it.success) onRefresh() }
                                                .onFailure { installMessage = it.message ?: "Activation failed" }
                                        } finally {
                                            installJob = null
                                        }
                                    }
                                },
                            ) { Text("Set global default") }
                        }
                    }
                    if (item.version.installKind in setOf(
                            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                            com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                        ) && record != null) {
                        OutlinedButton(
                            enabled = installJob == null && !installState.running,
                            onClick = {
                                installJob = scope.launch {
                                    try {
                                        runSuspendCatching {
                                            if (workspaceSelected) {
                                                manager.clearWorkspaceVersion(item.family.id)
                                                "Workspace override cleared."
                                            } else {
                                                val result = manager.unifiedAuthority.activate(item.family.id, item.version.version, manager.workspaceId)
                                                check(result.success) { result.message }
                                                result.message
                                            }
                                        }.onSuccess { installMessage = it; onRefresh() }
                                            .onFailure { installMessage = it.message ?: "Workspace toolchain selection failed" }
                                    } finally {
                                        installJob = null
                                    }
                                }
                            },
                        ) { Text(if (workspaceSelected) "Use global default" else "Use in workspace") }
                    }
                    if (item.version.installKind in setOf(
                            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                            com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN,
                        )
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = installJob == null && !installState.running,
                                onClick = {
                                    installJob = scope.launch {
                                        try {
                                            runSuspendCatching { manager.unifiedAuthority.repairVersion(item.family.id, item.version.version) }
                                                .onSuccess { txResult ->
                                                    if (txResult.success) {
                                                        installMessage = txResult.message
                                                        onRefresh()
                                                    } else {
                                                        installMessage = txResult.message
                                                    }
                                                }
                                                .onFailure { installMessage = it.message ?: "Repair failed" }
                                        } finally {
                                            installJob = null
                                        }
                                    }
                                },
                            ) { Text("Repair") }
                            TextButton(
                                enabled = installJob == null && !installState.running,
                                onClick = { confirmUninstall = true },
                            ) { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove Android pack" else if (item.version.version == UbuntuAptCatalog.VERSION) "Remove integration" else "Uninstall") }
                        }
                    }
                }
                ExtensionState.EXTERNAL -> {
                    Text("This command is detected in its environment. Droide does not own this installation or remove it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onRefresh, enabled = installJob == null && !installState.running) { Text("Verify detection") }
                    versionChoices.firstOrNull { it.state == ExtensionState.AVAILABLE }?.let { managed ->
                        Button(onClick = { onSelectItem(managed) }, enabled = installJob == null && !installState.running) { Text("Install managed version") }
                    }
                }
                else -> Unit
            }
        }
        if (item.state == ExtensionState.AVAILABLE) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 500) {
                    Text(stringResource(R.string.ext_review_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        installJob = scope.launch {
                            try {
                                val result = runSuspendCatching { manager.installer.prepare(item) }
                                result.onSuccess { prepared -> plan = prepared }
                                    .onFailure { installMessage = it.message ?: "Could not prepare installation" }
                            } finally {
                                installJob = null
                            }
                        }
                    },
                ) { Text(if (installState.familyId == item.family.id && installState.phase in setOf(
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED,
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.CANCELED)) stringResource(R.string.ext_retry) else stringResource(R.string.ext_review)) }
            }
        }
    }


    elpHealthRequest?.let { (healthPlan,request) ->
        AlertDialog(onDismissRequest={request.decline();elpHealthRequest=null;installMessage="ELP health declined; no selected tool was run."},
            title={Text(request.title)},
            text={Column(Modifier.verticalScroll(rememberScrollState())){Text(request.detail)}},
            confirmButton={TextButton(onClick={
                manager.installWithElpHealthApproval(healthPlan,request)
                elpHealthRequest=null
            },enabled=installJob==null&&!installState.running){Text("Run this health check")}},
            dismissButton={TextButton(onClick={request.decline();elpHealthRequest=null;installMessage="ELP health attempt inactive; previous package unchanged."}){Text("Decline")}})
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { if (installJob == null && !installState.running) confirmUninstall = false },
            title = { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove entire Android toolchain pack?" else if (item.version.version == UbuntuAptCatalog.VERSION) "Remove command integration?" else "Uninstall ${item.family.name}?") },
            text = {
                Text(
                    if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN)
                        "This removes the managed Android JDK, SDK and Build Tools together, including records for other Android components. Projects using this pack will need it installed again. Continue?"
                    else if (item.version.version == UbuntuAptCatalog.VERSION)
                        "Command integration will be removed. Shared Ubuntu packages remain installed for the Linux terminal."
                    else
                        "Remove ${item.family.name} ${item.version.version}? Required dependencies block removal."
                )
            },
            confirmButton = {
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        installJob = scope.launch {
                            try {
                                runSuspendCatching { manager.unifiedAuthority.uninstall(item.family.id, item.version.version) }
                                    .onSuccess { txResult ->
                                        if (txResult.success) {
                                            installMessage = txResult.message
                                            confirmUninstall = false
                                            onRefresh()
                                        } else {
                                            installMessage = txResult.message
                                        }
                                    }
                                    .onFailure { installMessage = it.message ?: "Uninstall failed" }
                            } finally {
                                installJob = null
                            }
                        }
                    },
                ) { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove entire pack" else if (item.version.version == UbuntuAptCatalog.VERSION) "Remove integration" else "Uninstall") }
            },
            dismissButton = {
                TextButton(
                    enabled = installJob == null && !installState.running,
                    onClick = { confirmUninstall = false },
                ) { Text("No") }
            },
        )
    }

    plan?.let { currentPlan ->
        val androidPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Android
        val localAndroidPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.AndroidLocalComponent
        val androidWorkstationPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.AndroidLocalWorkstation
        val packagePlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Package
        val recipePlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Recipe
        AlertDialog(
            onDismissRequest = { if (installJob == null && !installState.running) plan = null },
            title = { Text(stringResource(R.string.ext_review_title, currentPlan.familyName)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${currentPlan.resolvedVersion}", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.ext_will_install), style = MaterialTheme.typography.labelMedium)
                    currentPlan.components.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    manager.installer.externalDownloadNotice(currentPlan)?.let { notice ->
                        Text(notice, style = MaterialTheme.typography.bodyMedium)
                    }
                    androidPlan?.let { ap ->
                        Text("${ap.toolchainEntry.abi} · SHA-256 ${ap.toolchainEntry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(ap.toolchainEntry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (ap.licenseAccepted) {
                            Text("Android SDK license accepted for this revision.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    localAndroidPlan?.let { ap ->
                        Text("${ap.entry.kind.name.replace('_', ' ')} · SHA-256 ${ap.entry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(ap.entry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Ubuntu target: ${com.baystudio.droide.core.LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${ap.entry.guestTarget}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (ap.licenseAccepted) {
                            Text("Android SDK license accepted for this component install.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    packagePlan?.entries?.lastOrNull()?.let { entry ->
                        Text("${entry.abi} · SHA-256 ${entry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(entry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidWorkstationPlan?.let { ap ->
                        HorizontalDivider()
                        if (ap.licenseAccepted) Text("Android SDK license accepted for this workstation install.", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    recipePlan?.let { rp ->
                        Text("Exact npm recipe: ${rp.recipe.packageName}@${rp.recipe.packageVersion}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text("Source-reviewed install. Integrity and health checks run before activation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rp.recipe.provenanceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (installState.familyId == item.family.id) {
                        if (installState.message.isNotBlank()) Text(installState.message, style = MaterialTheme.typography.bodySmall,
                            color = if (installState.phase == com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (installState.running) {
                            val fraction = when (currentPlan) {
                                is com.baystudio.droide.core.ExtensionInstallPlan.Android -> androidProviderState.fraction
                                is com.baystudio.droide.core.ExtensionInstallPlan.Package -> packageProviderState.fraction
                                else -> null
                            }
                            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (installState.log.isNotBlank()) Text(installState.log,
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    installMessage?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                val needsLicense = (androidPlan != null && !androidPlan.licenseAccepted) ||
                    (localAndroidPlan != null && !localAndroidPlan.licenseAccepted) ||
                    (androidWorkstationPlan != null && !androidWorkstationPlan.licenseAccepted)
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        if (needsLicense) {
                            installJob = scope.launch {
                                try {
                                    val result = runSuspendCatching {
                                        when {
                                            androidPlan != null -> manager.installer.acceptLicense(androidPlan)
                                            localAndroidPlan != null -> manager.installer.acceptLicense(localAndroidPlan)
                                            androidWorkstationPlan != null -> manager.installer.acceptLicense(androidWorkstationPlan)
                                            else -> error("No Android license plan is active")
                                        }
                                    }
                                    result.onSuccess {
                                        plan = when {
                                            androidPlan != null -> androidPlan.copy(licenseAccepted = true)
                                            localAndroidPlan != null -> localAndroidPlan.copy(licenseAccepted = true)
                                            androidWorkstationPlan != null -> androidWorkstationPlan.copy(licenseAccepted = true)
                                            else -> currentPlan
                                        }
                                    }.onFailure { installMessage = it.message ?: "License acceptance failed" }
                                } finally {
                                    installJob = null
                                }
                            }
                        } else {
                            // Only the confirmation is pane-local. The manager owns the job and
                            // progress presentation before this dialog leaves the Composition.
                            installJob = scope.launch {
                                try {
                                    runSuspendCatching {
                                        manager.installer.acceptExternalDownloadFromUser(currentPlan)
                                        if (currentPlan is com.baystudio.droide.core.ExtensionInstallPlan.LocalVendor && currentPlan.familyId == "lsp.elp") {
                                            elpHealthRequest = currentPlan to manager.installer.requestElpHealthApproval(currentPlan)
                                        } else manager.installInBackground(currentPlan)
                                        plan = null
                                    }.onFailure { installMessage = it.message ?: "Installation could not start" }
                                } finally { installJob = null }
                            }
                        }
                    },
                ) { Text(stringResource(if (needsLicense) R.string.ext_license else if (installState.familyId == item.family.id && installState.phase in setOf(
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED,
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.CANCELED)) R.string.ext_retry else R.string.ext_install)) }
            },
            dismissButton = {
                if (installState.running) {
                    TextButton(onClick = { manager.installer.cancelInstallation(); installJob?.cancel() }) { Text(stringResource(R.string.ext_cancel)) }
                } else {
                    TextButton(onClick = { plan = null }) { Text(stringResource(R.string.ext_close)) }
                }
            },
        )
    }

}

@Composable
internal fun ExtensionVersionChoices(
    choices: List<ExtensionVersionState>,
    selected: ExtensionVersionState,
    enabled: Boolean,
    onSelect: (ExtensionVersionState) -> Unit,
) {
    var expanded by remember(selected.family.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }, enabled = enabled) { Text(stringResource(R.string.ext_versions)) }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { choice ->
                    val current = choice.version.version == selected.version.version && choice.version.scope == selected.version.scope
                    OutlinedButton(
                        onClick = { expanded = false; onSelect(choice) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled && !current,
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(extensionChoiceLabel(choice), style = MaterialTheme.typography.labelMedium)
                            if (current) Text(stringResource(R.string.ext_selected), style = MaterialTheme.typography.labelSmall)
                            else if (choice.version.recommended) Text(stringResource(R.string.ext_recommended), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f).padding(start = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = MaterialTheme.typography.bodyMedium)
    }
}
