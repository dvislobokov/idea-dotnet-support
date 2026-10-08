package io.github.dotnetsupport.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one thread of the network (the model is not reentrant; everything that touches it runs here). It runs at a low priority: the
 * loading and warm-up, the prefill of an opened file and the closing of sessions are background work that must not compete with the
 * EDT and the highlighting for the cores. While a completion request is queued or running — the grey text the user is waiting for —
 * the priority is raised to the normal one and lowered back once no completion waits. No extra threads: the caller of [complete]
 * raises it when it queues, the completion itself confirms it when it starts, the last caller lowers it.
 */
class NnThread(name: String) {
    @Volatile private var thread: Thread? = null
    private val waiting = AtomicInteger()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true; priority = if (waiting.get() > 0) Thread.NORM_PRIORITY else IDLE_PRIORITY; thread = this }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    val isShutdown: Boolean get() = executor.isShutdown
    /** True while a completion is queued or running: background work queued before it steps aside. */
    val completionWaiting: Boolean get() = waiting.get() > 0
    /** The priority of the thread now (tests). */
    val priority: Int? get() = thread?.priority

    /** Background work at the idle priority (unless a completion waits behind it). */
    fun execute(task: Runnable) = executor.execute(task)
    fun <T> submit(task: Callable<T>): Future<T> = executor.submit(task)
    fun shutdown() = executor.shutdown()

    /** Runs [block] on the thread at the normal priority; suspends until the thread is free. */
    suspend fun <T> complete(block: () -> T): T {
        if (waiting.incrementAndGet() == 1) thread?.priority = Thread.NORM_PRIORITY
        try {
            return withContext(dispatcher) {
                Thread.currentThread().priority = Thread.NORM_PRIORITY
                block()
            }
        } finally {
            if (waiting.decrementAndGet() == 0) thread?.priority = IDLE_PRIORITY
        }
    }

    companion object {
        /** Below the normal priority, above the lowest (the GC and the indexing threads of the platform sit there). */
        const val IDLE_PRIORITY = Thread.MIN_PRIORITY + 1
    }
}
