package com.baystudio.droide.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baystudio.droide.core.storage.StorageAccessController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StorageAccessSection(workspace: java.io.File?, projectName: String) {
    val context = LocalContext.current
    val controller = remember(context) { StorageAccessController.get(context) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun refresh() { scope.launch {
        try { withContext(Dispatchers.IO) { controller.refresh() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Storage is unavailable" }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text("Device storage", style = MaterialTheme.typography.titleSmall)
    if (!state.supported) {
        Text("Use Open folder, Import and Export through Android's file picker on this build or Android version.",
            style = MaterialTheme.typography.bodyMedium)
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Access shared files", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(state.enabled, enabled = !busy, onCheckedChange = { enabled ->
                busy = true
                scope.launch {
                    try {
                        error = null
                        val updated = withContext(Dispatchers.IO) { controller.setEnabled(enabled) }
                        if (enabled && !updated.granted) permission.launch(controller.permissionIntent())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Could not change storage access" }
                    finally { busy = false }
                }
            })
        }
        Text(when {
            !state.enabled -> "Off for new terminal and CLI sessions."
            !state.granted -> "Allow file access in Android settings to continue."
            state.volumes.isEmpty() -> "Permission granted. No shared storage volume is currently mounted."
            else -> "Available in new terminal and CLI sessions. Agent permissions still apply."
        }, style = MaterialTheme.typography.bodyMedium)
        state.volumes.forEach { volume ->
            Text("${volume.label}: ${volume.path}${if (volume.writable) "" else " (read only)"}",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = !busy, onClick = {
            try { permission.launch(controller.permissionIntent()) }
            catch (failure: Exception) { error = failure.message ?: "Android storage settings are unavailable" }
        }) { Text("Android file access") }
        Text("Open a new terminal or reconnect the CLI after changing access. Existing sessions keep their mounts; close them or revoke access in Android settings to remove access. Shared-file edits are direct and are not part of project review or Undo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    workspace?.let { SharedProjectSection(it, projectName) }
}

@Composable
private fun SharedProjectSection(workspace: java.io.File, name: String) {
    val context = LocalContext.current
    val store = remember(context) { com.baystudio.droide.core.storage.SharedWorkspaceStore(context) }
    val scope = rememberCoroutineScope()
    var shared by remember(workspace) { mutableStateOf(false) }
    var ready by remember(workspace) { mutableStateOf(false) }
    var error by remember(workspace) { mutableStateOf<String?>(null) }
    LaunchedEffect(workspace) {
        try { shared = withContext(Dispatchers.IO) { store.entries().any { it.root == workspace.canonicalFile } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Project sharing is unavailable" }
        finally { ready = true }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Show project in Android Files", style = MaterialTheme.typography.bodyLarge)
            Text(name, style = MaterialTheme.typography.bodySmall)
        }
        Switch(shared, enabled = ready, onCheckedChange = { enabled ->
            ready = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.setShared(workspace, name, enabled) }
                    shared = enabled; error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Could not change sharing" }
                finally { ready = true }
            }
        })
    }
    Text("Read and copy saved files through Android's file picker. Unsaved edits, internal state and sensitive files are excluded. Edit the project in Droide.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
