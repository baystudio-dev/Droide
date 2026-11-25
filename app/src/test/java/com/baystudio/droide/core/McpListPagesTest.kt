package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpListPagesTest {
    @Test fun longListsKeepEveryEntryAndTheOpaqueServerCursor() = runBlocking {
        val pager = McpListPages()
        val rows = (0 until 128).map { buildJsonObject { put("uri", "demo://$it"); put("description", "note ".repeat(40)) } }
        val request = buildJsonObject { put("resources", JsonArray(rows)); put("nextCursor", "page:two") }
        var calls = 0
        suspend fun read(args: JsonObject) = Json.parseToJsonElement(
            pager.result("s", "v", "list_resources", args) { calls++; request }.also { assertTrue(it.length <= 10_000) }
        ).jsonObject
        var output = read(buildJsonObject {})
        val seen = mutableListOf<JsonElement>()
        while (true) {
            val result = output.getValue("result").jsonObject
            seen.addAll(result.getValue("resources").jsonArray)
            val more = output["continuation"] ?: break
            assertNull(result["nextCursor"])
            val args = buildJsonObject { put("continuation", more) }
            output = read(args)
            assertEquals(output, read(args))
        }
        assertEquals(rows, seen)
        assertEquals(JsonPrimitive("page:two"), output.getValue("result").jsonObject["nextCursor"])
        assertEquals(1, calls)
    }

    @Test fun largeEntryAndEscapedCursorRemainCompleteAndBounded() = runBlocking {
        val pager = McpListPages()
        val cursor = "\u0001".repeat(4_096)
        val row = buildJsonObject { put("name", "example"); put("description", "🙂\n".repeat(6_000)) }
        val page = buildJsonObject { put("prompts", JsonArray(listOf(row))); put("nextCursor", cursor) }
        var args = buildJsonObject {}
        val replay = StringBuilder()
        var calls = 0
        do {
            val text = pager.result("s", "v", "list_prompts", args) { input ->
                calls++
                if (calls == 1) page else {
                    assertEquals(JsonPrimitive(cursor), input["cursor"])
                    buildJsonObject { put("prompts", JsonArray(emptyList())) }
                }
            }
            assertTrue(text.length <= 10_000)
            val output = Json.parseToJsonElement(text).jsonObject
            output["entry_fragment"]?.jsonObject?.let {
                assertEquals(replay.length, it.getValue("offset").jsonPrimitive.int)
                replay.append(it.getValue("json").jsonPrimitive.content)
            }
            val more = output["continuation"] ?: break
            args = buildJsonObject { put("continuation", more) }
        } while (true)
        assertEquals(row, Json.parseToJsonElement(replay.toString()))
        assertEquals(2, calls)
    }

    @Test fun expiredOrMismatchedContinuationDoesNotRequestAnotherServerPage() = runBlocking {
        val pager = McpListPages()
        val rows = JsonArray(listOf(buildJsonObject { put("description", "a".repeat(15_000)) }))
        suspend fun first() = Json.parseToJsonElement(pager.result("s", "v", "list_resources", buildJsonObject {}) {
            buildJsonObject { put("resources", rows) }
        }).jsonObject.getValue("continuation")
        val token = first()
        suspend fun resume(server: String, op: String) = runCatching {
            pager.result(server, "v", op, buildJsonObject { put("continuation", token) }) {
                throw AssertionError("Unexpected server request")
            }
        }.exceptionOrNull()
        assertTrue(resume("other", "list_resources") is IllegalArgumentException)
        assertTrue(resume("s", "list_prompts") is IllegalArgumentException)
        repeat(4) { first() }
        assertTrue(resume("s", "list_resources") is IllegalArgumentException)
    }
}
