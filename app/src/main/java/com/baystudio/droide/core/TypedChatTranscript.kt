package com.baystudio.droide.core

import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*

/** Public transcript used only when a complete historical group cannot fit a summary request. */
internal object TypedChatTranscript {
    private fun field(label: String) = StreamedJsonValidator.StringFields(label = label)
    private val function = StreamedJsonValidator.StringFields(mapOf("name" to field("tool"), "arguments" to field("arguments")))
    private val call = StreamedJsonValidator.StringFields(mapOf("id" to field("call id"), "function" to function))
    private val fields = StreamedJsonValidator.StringFields(mapOf(
        "role" to field("role"), "content" to field("content"), "tool_call_id" to field("result for"),
        "tool_calls" to StreamedJsonValidator.StringFields(element = call)
    ))

    fun fragments(items: List<SourceAwareChatHistoryWriter.Item>, chunkSize: Int, checkCurrent: () -> Unit): Flow<String> =
        channelFlow {
            require(chunkSize >= 2)
            val pending = StringBuilder(chunkSize + 1)
            fun current() { coroutineContext.ensureActive(); checkCurrent() }
            fun flush() {
                current()
                if (pending.isNotEmpty()) {
                    trySendBlocking(pending.toString()).getOrThrow()
                    pending.setLength(0)
                }
            }
            fun append(ch: Char) {
                pending.append(ch)
                if (pending.length >= chunkSize && !ch.isHighSurrogate()) flush()
            }
            fun text(value: String) { value.forEach(::append) }
            fun labeled(label: String, value: String) { text("\n$label: "); text(value) }
            fun read(input: InputStream) = input.use { source ->
                StreamedJsonValidator.visitStrings(source, fields, ::current) { label ->
                    text("\n$label: ")
                    ::append
                }
            }

            for (item in items) {
                current()
                when (item) {
                    is SourceAwareChatHistoryWriter.Item.OwnedAssistant ->
                        read(item.bound.responseSession.openBytes(item.bound.assistant.reference.handle))
                    is SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup -> {
                        read(item.responseSession.openBytes(item.assistant.reference.handle))
                        for (locator in item.calls) read(item.journal.openVerifiedResult(locator))
                    }
                    is SourceAwareChatHistoryWriter.Item.Legacy -> {
                        val message = item.message
                        labeled("role", message["role"]?.jsonPrimitive?.content.orEmpty())
                        message["tool_call_id"]?.jsonPrimitive?.contentOrNull?.let { labeled("result for", it) }
                        when (val content = message["content"]) {
                            is JsonPrimitive -> content.contentOrNull?.let { labeled("content", it) }
                            is JsonArray -> for (part in content.filterIsInstance<JsonObject>()) {
                                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                                    "text", "input_text" -> part["text"]?.jsonPrimitive?.contentOrNull?.let { labeled("content", it) }
                                    "image_url", "input_image", "droide_image_ref" -> text("\n[Image attachment in original message]")
                                }
                            }
                            else -> Unit
                        }
                        for (raw in (message["tool_calls"] as? JsonArray).orEmpty()) {
                            val tool = raw as? JsonObject ?: continue
                            tool["id"]?.jsonPrimitive?.contentOrNull?.let { labeled("call id", it) }
                            val fn = tool["function"] as? JsonObject ?: continue
                            fn["name"]?.jsonPrimitive?.contentOrNull?.let { labeled("tool", it) }
                            fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { labeled("arguments", it) }
                        }
                    }
                }
            }
            flush()
        }.buffer(1).flowOn(Dispatchers.IO)
}
