package io.github.nuke.oss.app.shared.media

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal enum class TaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

// Where a task gave up: resolving the post, fetching the bytes, or writing them to the gallery.
internal enum class TaskStage { RESOLVE, DOWNLOAD, SAVE }

// One download, as the settings screen shows it. Names come from the post, so they are already the ones the file will
// carry; no address or identifier is kept here.
internal data class DownloadTask(
    val id: Long,
    val name: String,
    val state: TaskState,
    val bytes: Long = 0,
    val total: Long = 0,
    val stage: TaskStage? = null,
    val reason: String? = null,
) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

// What the settings screen reads. The queue below is the only implementation, and it lives as long as the host process.
internal interface DownloadConsole {
    fun snapshot(): List<DownloadTask>

    // True when the task was queued or running and will not carry on.
    fun cancel(id: Long): Boolean

    // Files kept from earlier runs of this process, newest last; empty while the queue is empty.
    fun recent(): List<DownloadTask>

    // Drops the finished tasks from the screen; running ones stay.
    fun clearFinished()
}

// What a running download reports into. Both calls come from the download thread and never block.
internal class DownloadProgress internal constructor(
    private val onBytes: (bytes: Long, total: Long) -> Unit,
    private val cancelled: () -> Boolean,
) {
    fun update(bytes: Long, total: Long) = onBytes(bytes, total)

    fun isCancelled(): Boolean = cancelled()

    // Called by the downloader between chunks; a cancelled task stops by throwing this.
    fun check() {
        if (cancelled()) throw DownloadCancelled()
    }
}

internal class DownloadCancelled : RuntimeException("cancelled")

// A small queue: at most `threads` downloads at a time, the rest waiting in submission order. Progress, cancellation and
// the failure stage are kept per task so the settings screen can show what happened without reading the log.
internal class DownloadQueue(threads: Int = 2) : DownloadConsole, AutoCloseable {
    private val ids = AtomicLong()
    private val lock = Any()
    private val tasks = LinkedHashMap<Long, DownloadTask>()
    private val cancels = HashSet<Long>()
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { task ->
        Thread(task, "nuke-download").apply { isDaemon = true }
    }

    fun submit(name: String, stage: TaskStage = TaskStage.DOWNLOAD, work: (DownloadProgress) -> Unit): Long {
        val id = ids.incrementAndGet()
        synchronized(lock) {
            tasks[id] = DownloadTask(id, name, TaskState.QUEUED)
            trim()
        }
        try {
            pool.execute { run(id, stage, work) }
        } catch (_: RuntimeException) {
            // The pool is shut down: this task never runs.
            finish(id, TaskState.CANCELLED, null, null)
        }
        return id
    }

    override fun snapshot(): List<DownloadTask> = synchronized(lock) { tasks.values.toList() }

    override fun recent(): List<DownloadTask> = snapshot().filter { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }

    override fun clearFinished() = synchronized(lock) {
        tasks.values.filter { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }.forEach { tasks.remove(it.id) }
    }

    override fun cancel(id: Long): Boolean = synchronized(lock) {
        val task = tasks[id] ?: return false
        if (task.state != TaskState.QUEUED && task.state != TaskState.RUNNING) return false
        cancels += id
        if (task.state == TaskState.QUEUED) tasks[id] = task.copy(state = TaskState.CANCELLED)
        true
    }

    override fun close() {
        pool.shutdownNow()
    }

    // Tests wait on this instead of sleeping for a guessed time.
    internal fun awaitIdle(timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val running = snapshot().any { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }
            if (!running) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun run(id: Long, stage: TaskStage, work: (DownloadProgress) -> Unit) {
        synchronized(lock) {
            val queued = tasks[id] ?: return
            if (queued.state == TaskState.CANCELLED) return
            if (cancels.remove(id)) {
                tasks[id] = queued.copy(state = TaskState.CANCELLED)
                return
            }
            tasks[id] = queued.copy(state = TaskState.RUNNING)
        }
        val progress = DownloadProgress(
            onBytes = { bytes, total -> update(id) { it.copy(bytes = bytes, total = total) } },
            cancelled = { synchronized(lock) { cancels.contains(id) } },
        )
        try {
            work(progress)
            finish(id, TaskState.SUCCEEDED, null, null)
        } catch (_: DownloadCancelled) {
            finish(id, TaskState.CANCELLED, null, null)
        } catch (error: Exception) {
            finish(id, TaskState.FAILED, stage, error.message?.take(160) ?: error.javaClass.simpleName)
        }
    }

    private fun update(id: Long, change: (DownloadTask) -> DownloadTask) = synchronized(lock) {
        tasks[id]?.let { tasks[id] = change(it) }
    }

    private fun finish(id: Long, state: TaskState, stage: TaskStage?, reason: String?) = synchronized(lock) {
        cancels -= id
        tasks[id]?.let { tasks[id] = it.copy(state = state, stage = stage, reason = reason) }
        trim()
    }

    // Finished tasks are kept for the screen that opens later; the oldest go first, and the running ones never leave.
    private fun trim() {
        val finished = tasks.values.filter { it.state != TaskState.QUEUED && it.state != TaskState.RUNNING }
        if (finished.size <= KEEP_FINISHED) return
        for (task in finished.take(finished.size - KEEP_FINISHED)) tasks.remove(task.id)
    }

    private companion object {
        const val KEEP_FINISHED = 20
    }
}

// The settings screen opens in the host process but outside the feature that owns the queue, so the queue is published
// here; the same process is the only place these objects exist.
internal object DownloadConsoles {
    @Volatile private var console: DownloadConsole? = null

    fun register(value: DownloadConsole) {
        console = value
    }

    // Clears only the queue that registered, so a replaced feature cannot erase the newer one.
    fun clear(value: DownloadConsole) {
        if (console === value) console = null
    }

    fun current(): DownloadConsole? = console
}
