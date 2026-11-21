package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentContextContinuationTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private val largeAnswer = "EVIDENCE_START " + "item 42 ok.\n".repeat(10_000) + " EVIDENCE_END"

    private fun fixture(block: suspend (AgentService, ProviderStreamFixture) -> Unit) {
        val root = Files.createTempDirectory("agent-context-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                try { runBlocking { block(agent, provider) } }
                finally { agent.shutdown(); ModelContextRegistry.forget(provider.provider.id) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun ProviderStreamFixture.reply(content: String, stream: Boolean, input: Int = 1_200): String {
        val result = buildJsonObject {
            put("model", model)
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0)
                put(if (stream) "delta" else "message", buildJsonObject { put("role", "assistant"); put("content", content) })
                put("finish_reason", "stop")
            }) })
            put("usage", buildJsonObject { put("prompt_tokens", input); put("completion_tokens", 12); put("total_tokens", input + 12) })
        }.toString()
        return if (stream) event(result) + "data: [DONE]\n\n" else result
    }

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }
    private fun JsonObject.isSummary() = this["tool_choice"] == JsonPrimitive("none")

    @Test fun pagedConversationPromotesAtomicallyAndResumesFromAColdFork() = fixture { agent, provider ->
        ModelContextRegistry.remember(provider.provider.id, provider.model, 256_000)
        var reply = "Short control confirmation."
        provider.response = { _, _, stream -> provider.reply(reply, stream) }
        agent.chat("Inspect the project.", provider.config, maxTurns = 2)
        val id = checkNotNull(agent.currentSessionId)
        val disk = SessionManager(agent.workDir)
        assertTrue(disk.get(id)!!.forkHistoryPages.isNotEmpty())
        val earlier = checkNotNull(disk.fork(id))
        reply = largeAnswer
        agent.chat("Return the full report.", provider.config, maxTurns = 2)
        assertNull(agent.persistenceError.value)
        val restored = checkNotNull(disk.get(id))
        val typed = checkNotNull(restored.typedChat)
        val changedPrefix = listOf(TypedChatEntry.Legacy(buildJsonObject { put("role", "user"); put("content", "different history") }))
        val earlierManifest = agent.workDir.resolve(".droide/sessions/${earlier.id}.json")
        val earlierBytes = earlierManifest.readBytes()
        assertTrue(runCatching { disk.attachTypedChat(earlier.id, typed.copy(live = changedPrefix, exact = changedPrefix)) }.isFailure)
        assertArrayEquals(earlierBytes, earlierManifest.readBytes())
        assertTrue(restored.forkHistoryPages.isEmpty())
        assertTrue(restored.messages.any { it.content == "Short control confirmation." })
        val head = restored.messages.indexOfLast { it.publicContent != null && it.forkHistoryEnd != null }
        assertTrue(head >= 0)
        val child = checkNotNull(disk.fork(id, head))
        assertNull(child.lastRequestUsage)
        assertTrue(typed.exact.any { it is TypedChatEntry.OwnedTurn && it.toolCallCount == 0 })
        val manifest = agent.workDir.resolve(".droide/sessions/$id.json")
        val before = manifest.readBytes()
        val broken = typed.copy(exact = emptyList())
        assertTrue(runCatching { disk.save(restored.copy(typedChat = broken)) }.isFailure)
        assertArrayEquals(before, manifest.readBytes())
        reply = "Verified."
        for (session in listOf(restored, child)) {
            agent.loadSession(checkNotNull(disk.get(session.id)))
            provider.requests.clear()
            agent.chat("Continue from the report.", provider.config, maxTurns = 2)
            val messages = provider.requests.single().messages()
            assertTrue(messages.any { it["content"] == JsonPrimitive(largeAnswer) })
            assertTrue(messages.any { it["content"] == JsonPrimitive("Short control confirmation.") })
            assertNull(agent.persistenceError.value)
        }
    }

    @Test fun archivedConversationCompactsBeforeSamplingAndRestoresReportedUsage() = fixture { agent, provider ->
        ModelContextRegistry.remember(provider.provider.id, provider.model, 61_500)
        var reply = largeAnswer
        provider.response = { _, request, stream ->
            if (request.isSummary()) provider.reply("The original inspection is unfinished.", stream, 900)
            else provider.reply(reply, stream, if (reply == largeAnswer) 1_200 else 34_100)
        }
        agent.chat("Return the full test transcript.", provider.config, maxTurns = 2)
        val id = checkNotNull(agent.currentSessionId)
        val disk = SessionManager(agent.workDir)
        agent.loadSession(checkNotNull(disk.get(id)))
        assertEquals(1_200, agent.contextUsage.value?.reportedInputTokens)
        provider.requests.clear()
        reply = "Follow-up received."
        agent.chat("Continue the inspection with the evidence above.", provider.config, maxTurns = 2)
        assertTrue(provider.requests.dropLast(1).any { it.isSummary() && it.messages().any { m -> m["content"] == JsonPrimitive(largeAnswer) } })
        val follow = provider.requests.last()
        assertFalse(follow.isSummary())
        assertFalse(follow.messages().any { it["content"] == JsonPrimitive(largeAnswer) })
        assertTrue(follow.toString().contains("original inspection is unfinished"))
        assertEquals(34_100, agent.contextUsage.value?.reportedInputTokens)
        val restored = checkNotNull(disk.get(id))
        assertTrue(restored.typedChat!!.exact.any { it is TypedChatEntry.OwnedTurn && it.toolCallCount == 0 })
        agent.loadSession(restored)
        assertEquals(34_100, agent.contextUsage.value?.reportedInputTokens)
        assertNull(agent.persistenceError.value)
    }

    @Test fun unknownAndSmallWindowsCanSummarizeAllPublicEvidence() = fixture { agent, provider ->
        val evidence = "START_🧪." + "q".repeat(108_000) + ".END_🌳"
        provider.response = { index, _, stream -> provider.reply(if (index == 1) evidence else "Continue reviewing.", stream) }
        agent.chat("Inspect the old record.", provider.config, maxTurns = 2)
        agent.chat("Check the record.", provider.config, maxTurns = 2)
        val disk = SessionManager(agent.workDir)
        val id = checkNotNull(agent.currentSessionId)
        val separate = checkNotNull(disk.fork(id))
        for ((selected, window) in listOf(id to null, separate.id to 16_000)) {
            if (window != null) ModelContextRegistry.remember(provider.provider.id, provider.model, window)
            agent.loadSession(checkNotNull(disk.get(selected)))
            val exact = disk.get(selected)!!.typedChat!!.exact
            provider.requests.clear()
            provider.response = { _, _, stream -> provider.reply("The record was inspected; further checks remain.", stream) }
            assertEquals("Session compacted.", agent.compactCurrent(provider.config))
            val fragments = provider.requests.flatMap { it.messages() }.mapNotNull { message ->
                val text = (message["content"] as? JsonPrimitive)?.content.orEmpty()
                text.takeIf { it.startsWith("[Historical transcript · group ") }?.substringAfter('\n')
            }
            assertTrue("All public evidence survives splitting", fragments.joinToString("").contains(evidence))
            assertTrue(provider.requests.all { it.isSummary() && "tools" !in it })
            assertEquals(exact, disk.get(selected)!!.typedChat!!.exact)
            assertNull(disk.get(selected)!!.lastRequestUsage)
        }
    }

    @Test fun sixIdenticalEditorSnapshotsSendOneSelectionAndReopenWithFullEvidence() = fixture { agent, provider ->
        ModelContextRegistry.remember(provider.provider.id, provider.model, 256_000)
        val text = "uniqueSelection" + "x".repeat(3_985)
        val active = AgentIdeDocumentContext("src/One.kt", "Kotlin", 2, 1, 0, text.length, text, false, false, 1, 1)
        val snapshot = AgentIdeContextSnapshot(activeDocument = active, openFiles = listOf(active.path), capturedAtMs = 1)
        provider.response = { _, _, stream -> provider.reply("Acknowledged.", stream) }
        for (i in 1..6) agent.chat("Check file $i.", provider.config, maxTurns = 2, ideContext = snapshot.copy(capturedAtMs = i.toLong()))
        val sent = provider.requests.last().messages()
        assertEquals(1, sent.count { it["content"].toString().contains(text) })
        assertEquals(6, sent.count { it["role"] == JsonPrimitive("user") })
        assertFalse(sent.any { "_droide_ide_context_offset" in it })
        val disk = SessionManager(agent.workDir)
        val original = checkNotNull(disk.get(checkNotNull(agent.currentSessionId)))
        assertEquals(6, original.forkHistory.count { it["content"].toString().contains(text) })
        val fresh = AgentIdeContextProjection.Window()
        assertTrue(fresh.project(original.forkHistory.last { it["role"] == JsonPrimitive("user") }).toString().contains(text))
        agent.loadSession(original)
        agent.chat("Check new revision.", provider.config, maxTurns = 2, ideContext = snapshot.copy(activeDocument = active.copy(revision = 2)))
        assertEquals(2, provider.requests.last().messages().count { it["content"].toString().contains(text) })
        val compacted = disk.compact(checkNotNull(disk.get(original.id)), provider.config, keepRecentTokens = 10)
        assertTrue(compacted.changed)
        val changed = snapshot.copy(activeDocument = active.copy(revision = 2))
        agent.loadSession(compacted.session)
        provider.response = { _, _, stream -> provider.reply(largeAnswer, stream) }
        agent.chat("Check again after compaction.", provider.config, maxTurns = 2, ideContext = changed)
        assertEquals(1, provider.requests.last().messages().count { it["content"].toString().contains(text) })
        agent.loadSession(checkNotNull(disk.get(original.id)))
        provider.response = { _, _, stream -> provider.reply("Done.", stream) }
        agent.chat("Continue after reopening.", provider.config, maxTurns = 2, ideContext = changed)
        val archived = provider.requests.last().messages()
        assertTrue(archived.any { it["content"] == JsonPrimitive(largeAnswer) })
        assertEquals(1, archived.count { it["content"].toString().contains(text) })
        assertFalse(archived.any { "_droide_ide_context_offset" in it })
    }

    @Test fun separateSummaryRequestsPreserveAnAstralFileNameAtTheBoundary() = fixture { agent, provider ->
        val sessions = agent.sessions
        val marker = "🧪"
        fun entry(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
        val prefix = sessions.renderGroupForCompaction(listOf(entry("assistant", "")), 1)
            .substringBefore("assistant: ").length + "assistant: ".length
        val chunk = sessions.compactionChunkChars(provider.config)
        val body = "x".repeat(chunk - prefix - 1) + marker + ".kt holds the original constraints."
        val api = listOf(entry("assistant", body), entry("user", "Keep the original evidence."))
        val original = sessions.create().copy(apiHistory = api, forkHistory = api, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        sessions.save(original)
        provider.response = { _, _, stream -> provider.reply("File evidence retained.", stream) }
        assertTrue(sessions.compact(original, provider.config, keepRecentTokens = 1).changed)
        assertTrue(provider.requests.size >= 2)
        val chunks = provider.requests.map { it.messages().last()["content"]!!.jsonPrimitive.content }
        assertTrue(chunks.any { it.contains("$marker.kt") })
        assertTrue(sessions.get(original.id)!!.forkHistory.any { it.toString().contains("$marker.kt") })
    }
}
