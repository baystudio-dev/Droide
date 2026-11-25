package com.baystudio.droide.core

import java.util.UUID
import kotlinx.serialization.json.*

/** Keeps the exact server response available while the agent reads a large list in bounded pieces. */
internal class McpListPages {
    private class Page(val id: String, val server: String, val protocol: String, val operation: String,
        val key: String, val entries: JsonArray, val next: JsonElement?, val modern: Boolean, val isError: Boolean) {
        val encodedRows = entries.map { it.toString() }
    }
    private val pages = LinkedHashMap<String, Page>(4, 0.75f, true)

    suspend fun result(server: String, protocol: String, operation: String, parameters: JsonObject,
        request: suspend (JsonObject) -> JsonObject): String {
        val key = when (operation) {
            "list_resources" -> "resources"
            "list_resource_templates" -> "resourceTemplates"
            "list_prompts" -> "prompts"
            else -> return McpAgentResult.render(server, protocol, operation, request(parameters))
        }
        var wire = parameters
        if ("continuation" in parameters) {
            val token = parameters["continuation"] as? JsonPrimitive
            require(parameters.size == 1 && token?.isString == true && token.content.length <= 64) { "Invalid MCP continuation" }
            val fields = token.content.split(':')
            require(fields.size == 3) { "Invalid MCP continuation" }
            val page = synchronized(pages) { pages[fields[0]] }
            require(page != null && page.server == server && page.protocol == protocol && page.operation == operation) {
                "MCP list continuation expired or belongs to another operation. Restart the list."
            }
            val index = fields[1].toIntOrNull()
            val offset = fields[2].toIntOrNull()
            require(index != null && index in 0..page.entries.size && offset != null && offset >= 0) { "Invalid MCP list position" }
            if (index < page.entries.size) return slice(page, index, offset)
            require(offset == 0 && page.next != null) { "MCP list has no further page" }
            wire = buildJsonObject { put("cursor", page.next) }
        }
        val raw = request(wire)
        val full = McpAgentResult.envelope(server, protocol, operation, raw).toString()
        if (full.length <= McpAgentResult.MAX_CHARS) return full
        val entries = raw.getValue(key).jsonArray
        val page = Page(UUID.randomUUID().toString(), server, protocol, operation, key, entries, raw["nextCursor"],
            raw["resultType"] == JsonPrimitive("complete"), raw["isError"] == JsonPrimitive(true))
        synchronized(pages) {
            if (pages.size == 4) pages.remove(pages.keys.first())
            pages[page.id] = page
        }
        return slice(page, 0, 0)
    }

    private fun slice(page: Page, start: Int, offset: Int): String {
        val rows = mutableListOf<JsonElement>()
        var index = start
        var nextOffset = 0
        var fragment: String? = null
        var used = 0
        while (index < page.entries.size) {
            val row = page.entries[index]
            val encoded = page.encodedRows[index]
            if (offset > 0 || encoded.length > 7_500) {
                if (rows.isNotEmpty()) break
                require(offset in encoded.indices && !(offset > 0 && encoded[offset - 1].isHighSurrogate() && encoded[offset].isLowSurrogate())) { "Invalid MCP entry offset" }
                // Even the worst JSON escaping of 1,000 UTF-16 units fits the envelope.
                var end = minOf(offset + 1_000, encoded.length)
                if (end < encoded.length && encoded[end - 1].isHighSurrogate()) end--
                fragment = encoded.substring(offset, end)
                if (end == encoded.length) index++ else nextOffset = end
                break
            }
            if (used + encoded.length + 1 > 7_600) break
            rows += row
            used += encoded.length + 1
            index++
        }

        fun output(includeCursor: Boolean): String = buildJsonObject {
            put("server", page.server); put("protocolVersion", page.protocol); put("operation", page.operation)
            put("isError", page.isError)
            put("result", buildJsonObject {
                if (page.modern) put("resultType", "complete")
                put(page.key, JsonArray(rows))
                if (includeCursor) page.next?.let { put("nextCursor", it) }
            })
            fragment?.let { part ->
                put("entry_fragment", buildJsonObject { put("index", start); put("offset", offset); put("json", part) })
            }
            if (index < page.entries.size || !includeCursor && page.next != null) {
                put("continuation", "${page.id}:$index:$nextOffset")
            }
        }.toString()

        val complete = index == page.entries.size
        val text = output(complete)
        return if (text.length <= McpAgentResult.MAX_CHARS) text else output(false)
    }
}
