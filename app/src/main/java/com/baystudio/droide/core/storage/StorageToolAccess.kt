package com.baystudio.droide.core.storage

import com.baystudio.droide.core.*
import kotlinx.serialization.json.*

internal object StorageToolAccess {
    val definition: JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "storage")
            put("description", "Access Android shared files separately from the project. Start with status/locations. Absolute device paths only, except project-relative import destination/export source. read is paged UTF-8; write creates a new file (never overwrites); copy/export create new files; move stays within one volume; delete retains a recovery copy. stat returns version required for move/delete. Use authorized terminal commands for editing existing files, directories or larger operations. Changes are direct, outside project review/Undo; Android access and Agent permissions apply.")
            put("parameters", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("operation", buildJsonObject { put("type", "string"); put("enum", JsonArray(StorageOperationService.OPERATIONS.map(::JsonPrimitive))) })
                    for (name in listOf("path", "destination", "content", "version")) put(name, buildJsonObject {
                        put("type", "string"); put("maxLength", if (name == "content") 65536 else 4096)
                    })
                    put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                })
                put("required", buildJsonArray { add("operation") })
            })
        })
    }

    suspend fun allow(permissions: PermissionEngine, approvals: ApprovalManager, action: String,
                      resource: String, grantResource: String, permissionScope: String,
                      provenance: AgentRequestProvenance, current: () -> Unit) {
        current()
        val decision = when (permissions.decide(action, resource, permissionScope)) {
            PermEffect.ALLOW -> true
            PermEffect.DENY -> false
            PermEffect.ASK -> approvals.requestApproval("device_storage", "Device storage · $action",
                "$resource\nAllow for session applies to $action on this storage volume. Shared-file changes are direct, outside project review and Undo.",
                action = action, resource = resource, permissionScope = permissionScope, provenance = provenance,
                sessionGrantResource = grantResource, isStillCurrent = { runCatching(current).isSuccess }) == ApprovalDecision.Approved
        }
        check(decision) { "Permission denied: $action $resource" }
        current()
        check(permissions.decide(action, resource, permissionScope) != PermEffect.DENY) { "Storage permission was revoked" }
    }
}
