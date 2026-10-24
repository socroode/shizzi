package dev.shizzi.admin

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/** A watch request revokes its predecessor on the router: never create two concurrently. */
internal class ViewerSessions {
    private class Entry(create: () -> String) {
        val result = FutureTask(create)
        val cursor = AtomicLong()
    }
    private val entries = ConcurrentHashMap<String, Entry>()

    fun ensure(account: String, create: () -> String): String {
        val candidate = Entry(create)
        val entry = entries.putIfAbsent(account, candidate) ?: candidate
        entry.result.run()
        val session = try { entry.result.get() } catch (e: ExecutionException) {
            entries.remove(account, entry)
            throw e.cause ?: e
        }
        if (entries[account] !== entry) throw CancellationException("Association remplacée")
        return session
    }
    private fun matching(account: String, session: String): Entry? = entries[account]?.takeIf {
        it.result.isDone && runCatching { it.result.get() }.getOrNull() == session
    }
    fun current(account: String): String? = entries[account]?.let {
        if (it.result.isDone) runCatching { it.result.get() }.getOrNull() else null
    }
    fun after(account: String, session: String): Long = matching(account, session)?.cursor?.get() ?: 0L
    fun advance(account: String, session: String, id: Long) {
        matching(account, session)?.cursor?.accumulateAndGet(id, ::maxOf)
    }
    fun invalidate(account: String, session: String): Boolean =
        matching(account, session)?.let { entries.remove(account, it) } ?: false
    fun onPollFailure(account: String, session: String, status: Int?): Boolean =
        (status == 403 || status == 404) && invalidate(account, session)
    fun clear() { entries.clear() }
}
