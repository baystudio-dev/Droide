package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderWholePipelineCapacityTest {
    @Test fun everyAccumulatorRejectsIndividuallyValidTextDeltasWhenRetainedOutputGrowsTooLarge() {
        val fragment = "x".repeat(500_000)
        val consumers = listOf<Pair<(String) -> List<LlmStreamEvent>, String>>(
            LlmClient.StreamAccumulator(Json)::accept to
                """{"choices":[{"delta":{"content":"$fragment"}}]}""",
            AnthropicMessagesAdapter.StreamAccumulator(Json)::accept to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$fragment"}}""",
            GoogleGenerateContentAdapter.StreamAccumulator(Json)::accept to
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"$fragment"}]}}]}""",
            OpenAiResponsesAdapter.StreamAccumulator(Json)::accept to
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"$fragment"}""",
        )
        for ((accept, payload) in consumers) {
            assertEquals(fragment, accept(payload).filterIsInstance<LlmStreamEvent.TextDelta>().single().text)
            // Each event fits the 2M wire limit. Their combined text exceeds the 8M retained limit.
            val failure = assertThrows(IllegalStateException::class.java) { repeat(16) { accept(payload) } }
            assertTrue(failure.message.orEmpty().contains("retained response"))
        }
    }

    @Test fun directAdapterAndRequestEntryPointsRejectDeepJsonBeforeRecursiveParsing() {
        val deep = "[".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(ProviderPayloadLimits.MAX_JSON_DEPTH + 1)
        val decoders = listOf<(String) -> Any>(
            { ProviderModelRequestIdentity.canonicalize(it, Json) },
            { AnthropicMessagesAdapter.normalizeBuffered(it, Json) },
            { GoogleGenerateContentAdapter.normalizeBuffered(it, Json) },
            { OpenAiResponsesAdapter.normalizeBuffered(it, Json) },
            { LlmClient.StreamAccumulator(Json).accept(it) },
            { AnthropicMessagesAdapter.StreamAccumulator(Json).accept(it) },
            { GoogleGenerateContentAdapter.StreamAccumulator(Json).accept(it) },
            { OpenAiResponsesAdapter.StreamAccumulator(Json).accept(it) },
        )
        for (decode in decoders) {
            val failure = assertThrows(IllegalStateException::class.java) { decode(deep) }
            assertTrue(failure.message.orEmpty().contains("nesting"))
        }
    }

    @Test fun requestAndResponseCapacityAreIndependentAndCountersUseLongArithmetic() {
        val requestSized = " ".repeat(LlmClient.MAX_RESPONSE_CHARS + 1)
        assertSame(requestSized, ProviderPayloadLimits.checkedRequest(requestSized))
        assertThrows(IllegalStateException::class.java) { ProviderPayloadLimits.checkedJson(requestSized) }
        val capacity = ProviderPayloadLimits.RetainedBudget(8)
        capacity.reserve(3)
        capacity.reserve(5)
        assertThrows(IllegalStateException::class.java) { capacity.reserve(1) }
        assertThrows(IllegalStateException::class.java) { capacity.reserve(Long.MAX_VALUE) }
    }
}
