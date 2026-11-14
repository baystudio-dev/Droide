package com.baystudio.droide.core.storage

import com.baystudio.droide.core.SensitivePathPolicy
import java.util.Base64
import java.util.UUID

internal object SharedDocumentIds {
    data class Document(val workspaceId: String, val relative: String)

    fun visible(relative: String): Boolean = relative.isEmpty() ||
        (!relative.startsWith('/') && relative.length <= 4096 && relative.none { it == '\\' || it.code < 32 } &&
            relative.split('/').all { it.isNotBlank() && it !in setOf(".", "..", ".droide", ".git", ".ssh") && !it.startsWith(".droide-") } &&
            !SensitivePathPolicy.isSensitive(relative))

    fun encode(workspace: String, relative: String): String {
        UUID.fromString(workspace)
        require(visible(relative)) { "This project path is not shared" }
        return workspace + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toByteArray(Charsets.UTF_8))
    }

    fun decode(value: String): Document {
        require(value.length <= 6000 && value.count { it == ':' } == 1) { "Invalid document ID" }
        val workspace = value.substringBefore(':')
        val relative = Base64.getUrlDecoder().decode(value.substringAfter(':')).toString(Charsets.UTF_8)
        require(encode(workspace, relative) == value) { "Noncanonical document ID" }
        return Document(workspace, relative)
    }

    fun isChild(parent: String, child: String): Boolean {
        val p = decode(parent); val c = decode(child)
        return p.workspaceId == c.workspaceId && p.relative != c.relative &&
            (p.relative.isEmpty() || c.relative.startsWith(p.relative + "/"))
    }
}
