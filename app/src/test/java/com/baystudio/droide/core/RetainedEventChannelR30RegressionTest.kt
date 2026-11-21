package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Candidate contracts, not observed production results. Tiny injected limits only. */
class RetainedEventChannelR30RegressionTest {
    @Test fun burstOfDeltasPreservesToolAndThoughtBoundaries() = runBlocking {
        val queue = RetainedEventChannel(128, 8_000_000, WorkspaceAgentTextBatcher)
        val tool = WorkspaceAgentEvent.Tool("Read", "file.txt", null)
        fun offer(event: WorkspaceAgentEvent) {
            assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer(event, event.retainedChars()))
        }
        repeat(500) { offer(WorkspaceAgentEvent.Text("a")) }
        offer(tool)
        offer(WorkspaceAgentEvent.Thought("thinking"))
        repeat(500) { offer(WorkspaceAgentEvent.Text("b")) }
        queue.close()
        val received = mutableListOf<WorkspaceAgentEvent>()
        queue.consume { received.add(it) }
        val split = received.indexOf(tool)
        assertTrue(split > 0)
        assertEquals("a".repeat(500), received.take(split).joinToString("") { (it as WorkspaceAgentEvent.Text).text })
        assertEquals(WorkspaceAgentEvent.Thought("thinking"), received[split + 1])
        assertEquals("b".repeat(500), received.drop(split + 2).joinToString("") { (it as WorkspaceAgentEvent.Text).text })
        assertEquals(0L, queue.retainedChars)
    }

    @Test(timeout = 10_000) fun stoppingWhileABatchIsDeliveredReleasesTheQueuedText() = runBlocking {
        val queue = RetainedEventChannel(128, 8_000_000, WorkspaceAgentTextBatcher)
        val delta = WorkspaceAgentEvent.Text("xy")
        repeat(1_000) {
            assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer(delta, delta.retainedChars()))
        }
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) { queue.consume { awaitCancellation() } }
        assertTrue(queue.retainedCount > 1)
        consumer.cancelAndJoin()
        assertEquals(0L, queue.retainedChars)
        assertEquals(0, queue.retainedCount)
        assertEquals(RetainedEventChannel.Admission.CLOSED, queue.tryOffer(delta, delta.retainedChars()))
    }

    @Test(timeout = 10_000) fun preConsumerEventsSurviveCloseAndDrainExactlyOnce() = runBlocking {
        val queue = RetainedEventChannel<String>(4, 12)
        assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("one", 3))
        assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("two", 3))
        queue.close()
        assertEquals(6L, queue.retainedChars)
        val seen = mutableListOf<String>()
        withTimeout(1_000) { queue.consume { seen += it } }
        assertEquals(listOf("one", "two"), seen)
        assertEquals(0L, queue.retainedChars)
        assertEquals(0, queue.retainedCount)
    }

    @Test(timeout = 10_000) fun weightIncludesInFlightAndOverflowLatchesWithoutDiscardingAccepted() = runBlocking {
        val queue = RetainedEventChannel<String>(4, 8)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.consume {
                seen += it
                if (it == "a") { entered.complete(Unit); release.await() }
            }
        }
        try {
            assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("a", 5))
            withTimeout(1_000) { entered.await() }
            assertEquals(5L, queue.retainedChars)
            assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("b", 3))
            assertEquals(RetainedEventChannel.Admission.OVERFLOW, queue.tryOffer("gap", 1))
            assertEquals(RetainedEventChannel.Admission.CLOSED, queue.tryOffer("after", 0))
            assertEquals(8L, queue.retainedChars)
            release.complete(Unit)
            withTimeout(1_000) { consumer.join() }
            assertEquals(listOf("a", "b"), seen)
            assertEquals(0L, queue.retainedChars)
        } finally { release.complete(Unit); consumer.cancelAndJoin(); queue.cancel() }
    }

    @Test(timeout = 10_000) fun countStillBoundsEmptyPayloads() = runBlocking {
        val queue = RetainedEventChannel<String>(2, 8)
        assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("", 0))
        assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("", 0))
        assertEquals(RetainedEventChannel.Admission.OVERFLOW, queue.tryOffer("", 0))
        var delivered = 0
        queue.consume { delivered++ }
        assertEquals(2, delivered)
        assertEquals(0, queue.retainedCount)
    }

    @Test(timeout = 10_000) fun oversizedWeightUsesSubtractArithmeticAndKeepsExistingPayload() = runBlocking {
        val queue = RetainedEventChannel<String>(4, 8)
        queue.tryOffer("kept", 4)
        assertEquals(RetainedEventChannel.Admission.OVERFLOW, queue.tryOffer("huge-weight-only", Long.MAX_VALUE))
        val seen = mutableListOf<String>()
        queue.consume { seen += it }
        assertEquals(listOf("kept"), seen)
        assertEquals(0L, queue.retainedChars)
    }

    @Test(timeout = 10_000) fun failedConsumerReleasesInFlightAndUndeliveredBacklog() = runBlocking {
        val queue = RetainedEventChannel<String>(4, 8)
        queue.tryOffer("first", 4)
        queue.tryOffer("pending", 4)
        try { queue.consume { error("consumer failed") }; fail("Expected failure") }
        catch (expected: IllegalStateException) { assertEquals("consumer failed", expected.message) }
        assertEquals(0L, queue.retainedChars)
        assertEquals(0, queue.retainedCount)
        assertEquals(RetainedEventChannel.Admission.CLOSED, queue.tryOffer("late", 1))
    }

    @Test(timeout = 10_000) fun cancelledConsumerReleasesBothKindsOfOwnership() = runBlocking {
        val queue = RetainedEventChannel<String>(4, 8)
        val entered = CompletableDeferred<Unit>()
        queue.tryOffer("flight", 4)
        queue.tryOffer("pending", 4)
        val consumer = launch { queue.consume { entered.complete(Unit); awaitCancellation() } }
        try { withTimeout(1_000) { entered.await() } }
        finally { consumer.cancelAndJoin(); queue.cancel() }
        assertEquals(0L, queue.retainedChars)
        assertEquals(0, queue.retainedCount)
    }

    @Test(timeout = 10_000) fun inlineConsumerCanReenterAndDrainWithInFlightBudget() = runBlocking {
        val queue = RetainedEventChannel<String>(4, 8)
        val seen = mutableListOf<String>()
        val consumer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            queue.consume {
                seen += it
                if (it == "a") assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("b", 3))
                else queue.close()
            }
        }
        try {
            assertEquals(RetainedEventChannel.Admission.ACCEPTED, queue.tryOffer("a", 5))
            withTimeout(1_000) { consumer.join() }
            assertEquals(listOf("a", "b"), seen)
            assertEquals(0L, queue.retainedChars)
        } finally { consumer.cancelAndJoin(); queue.cancel() }
    }

    @Test(timeout = 10_000) fun previewCapacityDoesNotGrowToWholeIncomingChunk() {
        val target = StringBuilder(4)
        appendBoundedTextTail(target, "abcdefghijklmnopqrstuvwxyz", 4)
        assertEquals("wxyz", target.toString())
        assertTrue(target.capacity() <= 10) // Ordinary builder growth bound, far below this tiny 26-char input.
    }

    @Test(timeout = 10_000) fun boundedTailMatchesOriginalWithCrossChunkSurrogates() {
        val cases = listOf(
            listOf("ab", "cdefghij"), listOf("1234", "\uD83D\uDE00z"),
            listOf("abc\uD83D", "\uDE00z"), listOf("abc", "\uD83D\uDE00xyz"),
            listOf("", "\uD83D", "\uDE00"), listOf("abcd", "e", "f")
        )
        for (limit in 1..6) for (chunks in cases) {
            val expected = StringBuilder()
            val actual = StringBuilder()
            for (chunk in chunks) {
                expected.append(chunk)
                if (expected.length > limit) {
                    var cut = expected.length - limit
                    if (cut > 0 && cut < expected.length && expected[cut].isLowSurrogate() && expected[cut - 1].isHighSurrogate()) cut++
                    expected.delete(0, cut)
                }
                appendBoundedTextTail(actual, chunk, limit)
                assertEquals("limit=$limit chunks=$chunks", expected.toString(), actual.toString())
                assertTrue(actual.length <= limit)
            }
        }
    }
}
