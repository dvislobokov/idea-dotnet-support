package io.github.dotnetsupport.ml

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch

/** The thread of the network: background work (a prefill, the loading) runs at a low priority, a completion at the normal one. */
class NnThreadTest {
    @Test fun prefillRunsLowAndACompletionNormal() = runBlocking {
        val thread = NnThread("NN test")
        try {
            // the prefill of an opened file, nothing waiting: idle priority
            val prefill = thread.submit(Callable { Thread.currentThread().priority }).get()
            assertFalse(thread.completionWaiting)
            // the completion the user waits for
            val completion = thread.complete { Thread.currentThread().priority }
            // the queue is empty again: back to idle for the next prefill
            val after = thread.submit(Callable { Thread.currentThread().priority }).get()
            assertEquals(NnThread.IDLE_PRIORITY, prefill)
            assertEquals(Thread.NORM_PRIORITY, completion)
            assertEquals(NnThread.IDLE_PRIORITY, after)
            assertTrue("a prefill must run below a completion", prefill < completion)
        } finally { thread.shutdown() }
    }

    @Test fun aCompletionQueuedBehindAPrefillRaisesThePriorityAtOnce() = runBlocking {
        val thread = NnThread("NN test")
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val seen = IntArray(2)
            // a long prefill holds the thread; it is already running at the idle priority when the completion is queued
            thread.execute { seen[0] = Thread.currentThread().priority; started.countDown(); release.await(); seen[1] = Thread.currentThread().priority }
            started.await()
            assertEquals(NnThread.IDLE_PRIORITY, thread.priority)
            val job = async(start = CoroutineStart.UNDISPATCHED) { thread.complete { Thread.currentThread().priority } }
            // the completion is queued: the thread is raised while the prefill still runs, so the prefill finishes sooner
            val deadline = System.currentTimeMillis() + 5_000
            while (!thread.completionWaiting && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertTrue(thread.completionWaiting)
            assertEquals(Thread.NORM_PRIORITY, thread.priority)
            release.countDown()
            assertEquals(Thread.NORM_PRIORITY, job.await())
            assertEquals(NnThread.IDLE_PRIORITY, seen[0])
            assertEquals(Thread.NORM_PRIORITY, seen[1])
            assertEquals(NnThread.IDLE_PRIORITY, thread.submit(Callable { Thread.currentThread().priority }).get())
        } finally { thread.shutdown() }
    }
}
