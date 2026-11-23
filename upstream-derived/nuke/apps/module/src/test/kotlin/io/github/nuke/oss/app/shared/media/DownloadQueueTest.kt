package io.github.nuke.oss.app.shared.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {
    private val queues = ArrayList<DownloadQueue>()

    @After fun stopQueues() {
        for (queue in queues) queue.close()
    }

    private fun queue(threads: Int = 2): DownloadQueue = DownloadQueue(threads).also { queues += it }

    private fun DownloadQueue.await(millis: Long = 5_000, task: (DownloadTask) -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (snapshot().any(task)) return
            Thread.sleep(5)
        }
        throw AssertionError("task did not reach the expected state: ${snapshot()}")
    }

    @Test fun `a finished task keeps the bytes it reported`() {
        val queue = queue()
        queue.submit("clip") { progress ->
            progress.update(512, 1024)
            progress.update(1024, 1024)
        }
        queue.await { it.state == TaskState.SUCCEEDED }
        val task = queue.recent().single()
        assertEquals(1024, task.bytes)
        assertEquals(1024, task.total)
        assertEquals(1f, task.fraction)
        assertEquals("clip", task.name)
    }

    @Test fun `a failure keeps its stage and reason`() {
        val queue = queue()
        queue.submit("clip", TaskStage.SAVE) { throw IllegalStateException("no gallery") }
        queue.await { it.state == TaskState.FAILED }
        val task = queue.snapshot().single()
        assertEquals(TaskStage.SAVE, task.stage)
        assertEquals("no gallery", task.reason)
    }

    @Test fun `a queued task is cancelled without ever running`() {
        val queue = queue(threads = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        queue.submit("first") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        var ran = false
        val second = queue.submit("second") { ran = true }
        assertEquals(true, queue.cancel(second))
        release.countDown()
        queue.await { it.id == second && it.state == TaskState.CANCELLED }
        assertTrue(!ran)
    }

    @Test fun `a running task stops when the downloader checks for cancellation`() {
        val queue = queue()
        val running = CountDownLatch(1)
        val id = queue.submit("clip") { progress ->
            running.countDown()
            while (true) {
                progress.check()
                Thread.sleep(5)
            }
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertEquals(true, queue.cancel(id))
        queue.await { it.state == TaskState.CANCELLED }
    }

    @Test fun `cancelling a finished task changes nothing`() {
        val queue = queue()
        val id = queue.submit("clip") { }
        queue.await { it.state == TaskState.SUCCEEDED }
        assertEquals(false, queue.cancel(id))
        assertEquals(TaskState.SUCCEEDED, queue.snapshot().single().state)
    }

    @Test fun `one thread runs the tasks one after another`() {
        val queue = queue(threads = 1)
        val order = ArrayList<String>()
        queue.submit("first") { order += "first"; Thread.sleep(30) }
        queue.submit("second") { order += "second" }
        queue.await { it.name == "second" && it.state == TaskState.SUCCEEDED }
        assertEquals(listOf("first", "second"), order)
    }

    @Test fun `finished tasks are dropped when asked and never count as running`() {
        val queue = queue()
        queue.submit("clip") { }
        queue.await { it.state == TaskState.SUCCEEDED }
        assertEquals(1, queue.recent().size)
        queue.clearFinished()
        assertEquals(emptyList<DownloadTask>(), queue.snapshot())
    }

    @Test fun `the queue keeps only the newest finished tasks`() {
        val queue = queue()
        for (index in 1..30) queue.submit("clip $index") { }
        assertTrue(queue.awaitIdle())
        assertEquals(20, queue.snapshot().size)
        assertEquals("clip 11", queue.snapshot().first().name)
        assertEquals("clip 30", queue.snapshot().last().name)
    }
}
