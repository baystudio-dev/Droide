package com.baystudio.droide.ui

import com.baystudio.droide.core.ExitDraftRecord
import com.baystudio.droide.core.ExitDraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-dispatcher snapshots. The encrypted store performs its reads, syncs and writes on IO. */
internal class AgentComposerDrafts(
    private val store: ExitDraftStore?,
    private val scope: CoroutineScope,
    private val reportError: (String) -> Unit,
) {
    private val saved = mutableMapOf<AgentChatConversationKey, ExitDraftRecord>()
    private val pending = mutableMapOf<AgentChatConversationKey, ExitDraftRecord?>()
    private val writes = Mutex()
    private val edits = Channel<Unit>(Channel.CONFLATED)
    private var scheduled: Job? = null
    private val initialization = Mutex()
    private var loaded = store == null

    operator fun get(key: AgentChatConversationKey): ExitDraftRecord? = saved[key]

    suspend fun load(archive: ExitDraftStore): List<ExitDraftRecord> = initialization.withLock {
        if (loaded) return@withLock emptyList()
        val disk = store?.slots().orEmpty().values
        val conflicts = mutableListOf<ExitDraftRecord>()
        for (entry in disk) {
            val key = AgentChatConversationKey(entry.backend, entry.session)
            if (key !in pending) saved[key] = entry
            else if (saved[key] != entry) conflicts += entry.copy(mediaOwner = null)
        }
        // Someone may have typed into the same session while private storage was being read.
        // Keep their new text visible and offer the original through existing recovery controls.
        val archived = if (conflicts.isEmpty()) emptyList() else archive.preserve(conflicts)
        loaded = true
        schedule()
        archived
    }

    fun update(key: AgentChatConversationKey, record: ExitDraftRecord?) {
        if (record == saved[key] && (loaded || key in pending)) return
        if (record == null) saved.remove(key) else saved[key] = record
        if (store != null) {
            pending[key] = record
            schedule()
        }
    }

    private fun schedule() {
        if (store == null || !loaded || pending.isEmpty()) return
        edits.trySend(Unit)
        if (scheduled?.isActive == true) return
        scheduled = scope.launch {
            do {
                awaitComposerDraftCheckpoint(edits)
                try { flush() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    reportError("The current chat draft could not be saved. Keep the workspace open and retry.")
                    break
                }
            } while (pending.isNotEmpty())
        }
    }

    fun hasOwner(id: String): Boolean = saved.values.any { it.mediaOwner == id }

    suspend fun flush() = writes.withLock {
        if (store == null || !loaded || pending.isEmpty()) return@withLock
        val batch = pending.toMap()
        val keyed = batch.mapKeys { (key, _) ->
            val session = key.sessionId?.let { "s$it" } ?: "-"
            "${key.backendId.length}:${key.backendId}:$session"
        }
        store.putSlots(keyed)
        for ((key, record) in batch) if (pending[key] == record) pending.remove(key)
    }
}


/** A burst ends after 650 ms of quiet. Continuous input gets a checkpoint every five seconds. */
internal suspend fun awaitComposerDraftCheckpoint(edits: Channel<Unit>) {
    edits.tryReceive()
    withTimeoutOrNull(5_000L) {
        while (withTimeoutOrNull(650L) { edits.receive(); true } == true) {
            // A new edit restarts only the quiet interval, never the outer checkpoint deadline.
        }
    }
}
