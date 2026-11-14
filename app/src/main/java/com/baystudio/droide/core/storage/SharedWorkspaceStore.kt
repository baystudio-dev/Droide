package com.baystudio.droide.core.storage

import android.content.Context
import android.net.Uri
import com.baystudio.droide.core.ProjectWorkspacePolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.json.*

internal class SharedWorkspaceStore(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("shared-workspaces", Context.MODE_PRIVATE)
    data class Entry(val id: String, val label: String, val root: File, val identity: String)

    fun entries(): List<Entry> = synchronized(lock) {
        val text = prefs.getString("entries", "[]").orEmpty()
        if (text.length > 65536) return@synchronized emptyList()
        runCatching { Json.parseToJsonElement(text).jsonArray.take(32).mapNotNull { value ->
            runCatching {
            val item = value.jsonObject
            val root = ProjectWorkspacePolicy.requireDirectChild(ProjectWorkspacePolicy.projectsRoot(context.filesDir), File(item.getValue("root").jsonPrimitive.content))
            val key = item.getValue("identity").jsonPrimitive.content
            val attrs = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory && attrs.fileKey().toString() == key)
            val id = item.getValue("id").jsonPrimitive.content
            UUID.fromString(id)
            Entry(id, item.getValue("label").jsonPrimitive.content.take(120), root, key)
            }.getOrNull()
        } }.getOrDefault(emptyList())
    }

    fun setShared(root: File, label: String, enabled: Boolean) = synchronized(lock) {
        val canonical = ProjectWorkspacePolicy.requireDirectChild(ProjectWorkspacePolicy.projectsRoot(context.filesDir), root)
        val all = entries().toMutableList()
        val old = all.firstOrNull { it.root == canonical }
        all.removeAll { it.root == canonical }
        if (enabled) {
            require(all.size < 32) { "At most 32 projects can be shared" }
            val attrs = Files.readAttributes(canonical.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory)
            all += Entry(old?.id ?: UUID.randomUUID().toString(), label.take(120), canonical, requireNotNull(attrs.fileKey()).toString())
        }
        val encoded = buildJsonArray { all.forEach { entry -> add(buildJsonObject {
            put("id", entry.id); put("label", entry.label); put("root", entry.root.path); put("identity", entry.identity)
        }) } }.toString()
        check(prefs.edit().putString("entries", encoded).commit()) { "Could not save project sharing" }
        context.contentResolver.notifyChange(Uri.parse("content://${context.packageName}.documents/root"), null)
    }

    companion object { private val lock = Any() }
}
