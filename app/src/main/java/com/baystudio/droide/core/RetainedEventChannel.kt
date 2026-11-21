package com.baystudio.droide.core

import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel

internal interface RetainedEventBatcher<T> {
    fun compatible(first: T, next: T): Boolean
    fun combine(events: List<T>): T
}

/** One-owner FIFO. Graceful close and overflow drain accepted events; neither evicts their data. Unlike
 * ProtocolEventMailbox this admits events before the consumer starts. */
internal class RetainedEventChannel<T>(
    private val capacity: Int,
    private val maxRetainedChars: Long,
    private val batcher: RetainedEventBatcher<T>? = null,
) {
    enum class Admission { ACCEPTED, OVERFLOW, CLOSED }

    private inner class Entry(val value: T, var chars: Long) {
        private var batch: MutableList<T>? = null
        var released = false
        fun appendIfCompatible(next: T, cost: Long): Boolean {
            if (batcher == null || cost > 32_768L - chars || (batch?.size ?: 1) >= 128 || !batcher.compatible(value, next)) return false
            val items = batch ?: mutableListOf(value).also { batch = it }
            items.add(next)
            chars += cost
            return true
        }
        fun delivery(): T = batch?.let { checkNotNull(batcher).combine(it) } ?: value
        fun releaseLocked() {
            if (!released) {
                released = true
                retained -= chars
                count--
            }
        }
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var retained = 0L
    private var count = 0
    private var closed = false
    private var consuming = false

    init { require(capacity > 0 && maxRetainedChars > 0) }

    internal val retainedChars: Long get() = synchronized(lock) { retained }
    internal val retainedCount: Int get() = synchronized(lock) { count }

    fun tryOffer(value: T, chars: Long): Admission {
        require(chars >= 0L)
        val result = synchronized(lock) {
            when {
                closed -> Admission.CLOSED
                chars <= maxRetainedChars - retained && entries.peekLast()?.appendIfCompatible(value, chars) == true -> {
                    retained += chars
                    Admission.ACCEPTED
                }
                count >= capacity || chars > maxRetainedChars - retained -> {
                    // Latch the first gap: no later event can be admitted past it.
                    closed = true
                    Admission.OVERFLOW
                }
                else -> {
                    entries.addLast(Entry(value, chars))
                    retained += chars
                    count++
                    Admission.ACCEPTED
                }
            }
        }
        when (result) {
            Admission.ACCEPTED -> wake.trySend(Unit)
            Admission.OVERFLOW -> wake.close()
            Admission.CLOSED -> Unit
        }
        return result
    }

    suspend fun consume(action: suspend (T) -> Unit) {
        synchronized(lock) {
            check(!consuming) { "Event channel already has a consumer" }
            consuming = true
        }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = synchronized(lock) {
                    if (entries.isEmpty() && closed) return
                    entries.pollFirst()
                }
                if (entry == null) { wake.receiveCatching(); continue }
                try { action(entry.delivery()) }
                finally { synchronized(lock) { entry.releaseLocked() } }
            }
        } finally {
            synchronized(lock) {
                consuming = false
                discardPendingLocked()
            }
            wake.close()
        }
    }

    /** Stop producers; accepted values remain available to the owner. */
    fun close() {
        synchronized(lock) { closed = true }
        wake.close()
    }

    /** Owner teardown: discard pending work, while in-flight work stays charged until it returns. */
    fun cancel() {
        synchronized(lock) { discardPendingLocked() }
        wake.close()
    }

    private fun discardPendingLocked() {
        closed = true
        while (entries.isNotEmpty()) entries.removeFirst().releaseLocked()
    }
}
