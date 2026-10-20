package com.aster.files.transfer

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

enum class TransferStatus(val label: String) {
    WAITING("等待"),
    RUNNING("进行中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败"),
    INTERRUPTED("已中断，可重试"),
    CANCELLED("已取消")
}

data class TransferTask(
    val id: String,
    val name: String,
    val direction: String,
    val status: TransferStatus,
    val progress: Float = 0f,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

class TransferGate {
    @Volatile var paused = false
    @Volatile var cancelled = false

    fun check() {
        while (paused && !cancelled) {
            Thread.sleep(80)
        }
        if (cancelled) throw CancellationException("传输已取消")
    }

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun cancel() { cancelled = true; paused = false }
}

object TransferCenter {
    private val sequence = AtomicLong(1)
    private val _tasks = mutableStateListOf<TransferTask>()
    private val gates = mutableMapOf<String, TransferGate>()
    private val speedSamples = mutableMapOf<String, ArrayDeque<Pair<Long, Long>>>()
    private val retryActions = mutableMapOf<String, () -> Unit>()
    private val slots = Semaphore(3)
    private var queueFile: File? = null
    private var lastPersistAt = 0L

    val tasks: List<TransferTask> get() = _tasks

    fun init(context: Context) {
        if (queueFile != null) return
        queueFile = File(context.filesDir, "transfer_queue.json")
        restore()
    }

    private fun restore() {
        val file = queueFile ?: return
        if (!file.isFile) return
        runCatching {
            val array = JSONArray(file.readText())
            val restored = mutableListOf<TransferTask>()
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val status = runCatching {
                    TransferStatus.valueOf(json.optString("status"))
                }.getOrDefault(TransferStatus.INTERRUPTED)
                val interrupted = status == TransferStatus.RUNNING ||
                    status == TransferStatus.WAITING ||
                    status == TransferStatus.PAUSED
                restored.add(
                    TransferTask(
                        id = json.optString("id"),
                        name = json.optString("name"),
                        direction = json.optString("direction"),
                        status = if (interrupted) TransferStatus.INTERRUPTED else status,
                        progress = json.optDouble("progress", 0.0).toFloat(),
                        copiedBytes = json.optLong("copiedBytes"),
                        totalBytes = json.optLong("totalBytes"),
                        error = if (interrupted) "已中断，可重试" else json.optString("error").ifBlank { null },
                        createdAt = json.optLong("createdAt")
                    )
                )
            }
            _tasks.clear()
            _tasks.addAll(restored)
            val maxId = restored.mapNotNull { it.id.removePrefix("T").toLongOrNull() }.maxOrNull() ?: 0L
            sequence.set(maxId + 1)
        }
    }

    private fun persist(force: Boolean = false) {
        val file = queueFile ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 500) return
        lastPersistAt = now
        runCatching {
            val array = JSONArray()
            _tasks.forEach { task ->
                array.put(JSONObject().apply {
                    put("id", task.id)
                    put("name", task.name)
                    put("direction", task.direction)
                    put("status", task.status.name)
                    put("progress", task.progress)
                    put("copiedBytes", task.copiedBytes)
                    put("totalBytes", task.totalBytes)
                    put("error", task.error ?: "")
                    put("createdAt", task.createdAt)
                })
            }
            file.writeText(array.toString())
        }
    }

    fun create(name: String, direction: String): String {
        val id = "T" + sequence.getAndIncrement()
        _tasks.add(
            0,
            TransferTask(
                id = id,
                name = name,
                direction = direction,
                status = TransferStatus.WAITING
            )
        )
        gates[id] = TransferGate()
        persist(force = true)
        return id
    }

    fun gate(id: String): TransferGate? = gates[id]

    fun get(id: String): TransferTask? = task(id)

    fun registerRetry(id: String, action: () -> Unit) {
        retryActions[id] = action
    }

    fun retry(id: String) {
        val action = retryActions.remove(id) ?: return
        _tasks.removeAll { it.id == id }
        gates.remove(id)
        speedSamples.remove(id)
        persist(force = true)
        action()
    }

    suspend fun acquire(id: String) {
        val current = task(id) ?: return
        if (current.status == TransferStatus.CANCELLED) {
            throw CancellationException("传输已取消")
        }
        slots.acquire()
        if (isCancelled(id)) {
            slots.release()
            throw CancellationException("传输已取消")
        }
        updateStatus(id, TransferStatus.RUNNING, current.progress)
    }

    fun release() {
        slots.release()
    }

    fun runningCount(): Int = _tasks.count { it.status == TransferStatus.RUNNING }

    fun update(id: String, copied: Long, total: Long) {
        val index = _tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        val now = System.currentTimeMillis()
        val samples = speedSamples.getOrPut(id) { ArrayDeque() }
        samples.addLast(now to copied)
        while (samples.size > 2 && now - samples.first.first > 3000L) samples.removeFirst()
        val speed = if (samples.size > 1) {
            val elapsed = (samples.last.first - samples.first.first).coerceAtLeast(1L)
            ((samples.last.second - samples.first.second).coerceAtLeast(0L) * 1000L) / elapsed
        } else 0L
        val progress = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else 0f
        val task = _tasks[index]
        if (task.status == TransferStatus.PAUSED || task.status == TransferStatus.CANCELLED) return
        _tasks[index] = task.copy(
            status = TransferStatus.RUNNING,
            progress = progress,
            copiedBytes = copied,
            totalBytes = total,
            speedBytesPerSecond = speed
        )
        persist()
    }

    fun complete(id: String) {
        updateStatus(id, TransferStatus.COMPLETED, 1f)
        gates.remove(id)
        speedSamples.remove(id)
    }

    fun fail(id: String, message: String) {
        val task = task(id) ?: return
        if (task.status == TransferStatus.CANCELLED) return
        updateStatus(id, TransferStatus.FAILED, task.progress, message)
        gates.remove(id)
        speedSamples.remove(id)
    }

    fun pause(id: String) {
        val task = task(id) ?: return
        if (task.status != TransferStatus.RUNNING && task.status != TransferStatus.WAITING) return
        gates[id]?.pause()
        updateStatus(id, TransferStatus.PAUSED, task.progress)
    }

    fun resume(id: String) {
        val task = task(id) ?: return
        if (task.status != TransferStatus.PAUSED) return
        gates[id]?.resume()
        updateStatus(id, TransferStatus.RUNNING, task.progress)
    }

    fun cancel(id: String) {
        val task = task(id) ?: return
        if (task.status == TransferStatus.COMPLETED) return
        gates[id]?.cancel()
        updateStatus(id, TransferStatus.CANCELLED, task.progress)
        speedSamples.remove(id)
    }

    fun isCancelled(id: String): Boolean = task(id)?.status == TransferStatus.CANCELLED

    fun clearFinished() {
        _tasks.removeAll {
            it.status == TransferStatus.COMPLETED ||
                it.status == TransferStatus.FAILED ||
                it.status == TransferStatus.INTERRUPTED ||
                it.status == TransferStatus.CANCELLED
        }
        persist(force = true)
    }

    private fun task(id: String): TransferTask? = _tasks.firstOrNull { it.id == id }

    private fun updateStatus(
        id: String,
        status: TransferStatus,
        progress: Float,
        error: String? = null
    ) {
        val index = _tasks.indexOfFirst { it.id == id }
        if (index < 0) return
        _tasks[index] = _tasks[index].copy(
            status = status,
            progress = progress,
            error = error,
            speedBytesPerSecond = if (status == TransferStatus.RUNNING) {
                _tasks[index].speedBytesPerSecond
            } else {
                0L
            }
        )
        persist(force = true)
    }
}
