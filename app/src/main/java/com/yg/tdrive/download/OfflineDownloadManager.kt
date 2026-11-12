package com.yg.tgdrive.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yg.tgdrive.data.DownloadTask
import com.yg.tgdrive.data.DownloadTaskDao
import com.yg.tgdrive.data.MediaEntity
import com.yg.tgdrive.data.TdRequestException
import com.yg.tgdrive.data.tdCall
import com.yg.tgdrive.settings.SettingsStore
import com.yg.tgdrive.td.AuthState
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.tdObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 离线下载管理器（Wave1-B 离线下载子系统核心）。
 *
 * 与 [StreamingDownloadManager]（边下边播）的区别：本类做「整文件永久保存」。
 * TDLib 事实（从 TdClient.kt / StreamingDownloadManager.kt 实际代码确认）：
 *  - 请求统一走 `td.tdCall(tdObj("@type" to ...))`（带 @extra 配对 + error→异常）。
 *  - `downloadFile(file_id, offset, limit, priority, synchronous=false)`：offset=0、
 *    limit=文件大小（0=到文件末尾）即整文件下载；TDLib 复用 .part 实现断点续传。
 *  - `updateFile` 从 `td.fileUpdates`（SharedFlow<JsonObject>）推送，file 对象里
 *    `local.downloaded_size` 为累计已下字节、`local.path` 为本地路径、
 *    `local.is_downloading_completed` 为完成标志、`size`/`expected_size` 为总大小。
 *  - `toggleDownloadIsPaused(file_id, is_paused)`：暂停/恢复；恢复后重新 downloadFile 续传。
 *  - `getFile(file_id)`：同步拉一次文件快照。
 *
 * 调度：并发上限实时取 `settings.concurrency` 最新值；每个任务一个协程跑完整文件下载，
 * 进度节流写库（>=500ms/次），完成拷到目标目录（私有 / 公共，见 [persistFile]）。
 *
 * 公开 API：enqueue / reload / pause / resume / cancel / retry / pauseAll / resumeAll /
 * deleteTask / tasks(Flow)。
 */
class OfflineDownloadManager(
    private val context: Context,
    private val td: TdClient,
    private val dao: DownloadTaskDao,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val badge: MutableStateFlow<Int>,
) {

    companion object {
        private const val TAG = "OfflineDL"
        private const val PRIORITY = 16   // 低于流播(32)，保证「边下边看」时观看优先

        /** 进度写库节流。 */
        private const val PROGRESS_DB_MS = 500L

        /** 等待完成轮询间隔。 */
        private const val POLL_MS = 300L

        /** getFile 快照兜底间隔（防 updateFile 丢事件）。 */
        private const val SNAPSHOT_MS = 3_000L

        /** 无进展停滞判死（15 分钟）。 */
        private const val STALL_MS = 15 * 60 * 1000L

        /** FLOOD_WAIT 重试阈值（秒）与最多重试次数。 */
        private const val FLOOD_RETRY_MAX_S = 60
        private const val FLOOD_MAX_ATTEMPTS = 3
    }

    /** 任务列表观察（UI 用）。 */
    val tasks: Flow<List<DownloadTask>> = dao.observeAll()

    private val notifier = DownloadNotifications(context)

    /** taskId → 运行中 Job。 */
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** taskId → 运行中会话状态（updateFile 分发的目标）。 */
    private val active = ConcurrentHashMap<Long, ActiveDownload>()

    @Volatile
    private var maxConcurrent = 4

    private val pumpMutex = Mutex()

    init {
        // 并发上限热更
        scope.launch {
            settings.concurrency.collect { n ->
                maxConcurrent = n.coerceIn(1, 16)
                pump()
            }
        }
        // 全局 updateFile 分发：按 file_id 找到运行中会话
        scope.launch {
            td.fileUpdates.collect { update ->
                val file = update["file"] as? JsonObject ?: return@collect
                val id = (file["id"] as? JsonPrimitive)?.intOrNull ?: return@collect
                active.values.forEach { a -> if (a.fileId == id) applyFile(a, file) }
            }
        }
    }

    // ═══════════════════════ 公开 API ═══════════════════════

    /** 加入下载：同 media 已在队列/下载/暂停/完成则幂等；失败/取消则重置重排队。 */
    fun enqueue(media: MediaEntity) {
        scope.launch {
            val existing = dao.byMediaId(media.id)
            if (existing != null) {
                when (existing.state) {
                    DownloadTask.STATE_QUEUED,
                    DownloadTask.STATE_DOWNLOADING,
                    DownloadTask.STATE_PAUSED,
                    DownloadTask.STATE_COMPLETED -> return@launch
                }
                // 失败/取消 → 重置重来
                dao.updateState(existing.id, DownloadTask.STATE_QUEUED, null)
                dao.updateProgress(existing.id, 0, media.size)
            } else {
                dao.insert(
                    DownloadTask(
                        mediaId = media.id,
                        fileId = media.fileId,
                        kind = media.kind,
                        displayName = media.displayName,
                        bytesTotal = media.size,
                        priority = 0,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            refreshBadge()
            pump()
        }
    }

    /**
     * 启动时恢复：把「下载中」回落为「排队」，等 TDLib 就绪后重新调度（断点续传）。
     * 由 ServiceLocator.init 末尾调用。
     */
    fun reload() {
        scope.launch {
            runCatching { dao.resetDownloadingToQueued() }
            refreshBadge()
            // 等 TDLib 就绪再调度，避免未登录时把所有任务打成失败
            withTimeoutOrNull(5 * 60_000L) {
                td.authState.first { it is AuthState.Ready }
            }
            pump()
        }
    }

    fun pause(id: Long) {
        scope.launch {
            val t = dao.byId(id) ?: return@launch
            doPause(t)
        }
    }

    fun resume(id: Long) {
        scope.launch {
            val t = dao.byId(id) ?: return@launch
            doResume(t)
        }
    }

    fun cancel(id: Long) {
        scope.launch {
            val t = dao.byId(id) ?: return@launch
            runCatching {
                td.tdCall(
                    tdObj(
                        "@type" to "cancelDownloadFile",
                        "file_id" to t.fileId,
                        "only_pending" to false,
                    )
                )
            }
            jobs.remove(id)?.cancel()
            active.remove(id)
            dao.updateState(id, DownloadTask.STATE_CANCELLED, "已取消")
            refreshBadge()
            pump()
        }
    }

    /** 失败/取消任务重试：回到排队再调度。 */
    fun retry(id: Long) {
        scope.launch {
            dao.updateState(id, DownloadTask.STATE_QUEUED, null)
            refreshBadge()
            pump()
        }
    }

    fun deleteTask(id: Long) {
        scope.launch {
            jobs.remove(id)?.cancel()
            active.remove(id)
            dao.delete(id)
            refreshBadge()
            pump()
        }
    }

    fun pauseAll() {
        scope.launch {
            val list = dao.byStateIn(
                listOf(DownloadTask.STATE_QUEUED, DownloadTask.STATE_DOWNLOADING)
            )
            list.forEach { doPause(it) }
        }
    }

    fun resumeAll() {
        scope.launch {
            val list = dao.byStateIn(listOf(DownloadTask.STATE_PAUSED))
            list.forEach { doResume(it) }
        }
    }

    // ═══════════════════════ 调度 ═══════════════════════

    private fun pump() {
        scope.launch {
            pumpMutex.withLock {
                // TDLib 未就绪前不启动（否则冷启动竞态会把任务打成失败）
                if (td.authState.value !is AuthState.Ready) return@withLock
                pruneDead()
                var capacity = maxConcurrent - jobs.values.count { it.isActive }
                if (capacity <= 0) return@withLock
                val queued = runCatching { dao.byState(DownloadTask.STATE_QUEUED) }
                    .getOrDefault(emptyList())
                for (t in queued) {
                    if (capacity <= 0) break
                    if (jobs.containsKey(t.id)) continue
                    startDownload(t)
                    capacity--
                }
            }
            refreshBadge()
        }
    }

    private fun pruneDead() {
        val it = jobs.entries.iterator()
        while (it.hasNext()) {
            if (!it.next().value.isActive) it.remove()
        }
    }

    private fun startDownload(task: DownloadTask) {
        val job = scope.launch(Dispatchers.IO) {
            val act = ActiveDownload(task.id, task.fileId)
            active[task.id] = act
            try {
                dao.updateState(task.id, DownloadTask.STATE_DOWNLOADING, null)
                runOne(task, act)
            } catch (ce: CancellationException) {
                // 暂停/取消由对应方法写状态，这里不覆盖
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "下载失败 task=${task.id}", t)
                runCatching {
                    dao.updateState(
                        task.id,
                        DownloadTask.STATE_FAILED,
                        t.message ?: "下载失败",
                    )
                }
                runCatching { notifier.notifyFailed(task.id, task.displayName, t.message) }
            } finally {
                active.remove(task.id)
                jobs.remove(task.id)
                refreshBadge()
                pump()
            }
        }
        jobs[task.id] = job
    }

    // ═══════════════════════ 单任务下载 ═══════════════════════

    private suspend fun runOne(task: DownloadTask, a: ActiveDownload) {
        // TDLib 未就绪时等一下（冷启动竞态），仍不行则失败
        if (td.authState.value !is AuthState.Ready) {
            val ready = withTimeoutOrNull(60_000L) {
                td.authState.first { it is AuthState.Ready }
            }
            if (ready == null) throw IllegalStateException("TDLib 尚未就绪")
        }

        // 1) 快照
        val snapshot = tdLong(tdObj("@type" to "getFile", "file_id" to task.fileId))
        applyFile(a, snapshot)
        if (a.total <= 0L) a.total = task.bytesTotal
        if (a.completed && a.path != null) {
            finish(task, a)
            return
        }

        // 2) 发起整文件下载（offset=0，limit=总大小，0=到末尾）
        val limit = if (a.total > 0L) a.total else 0L
        callWithFloodRetry {
            tdLong(
                tdObj(
                    "@type" to "downloadFile",
                    "file_id" to task.fileId,
                    "offset" to 0,
                    "limit" to limit,
                    "priority" to PRIORITY,
                    "synchronous" to false,
                )
            )
        }

        // 3) 等待完成（updateFile 驱动 + getFile 兜底 + 停滞判死）
        var lastSnapshotAt = System.currentTimeMillis()
        a.lastProgressAt = System.currentTimeMillis()
        while (true) {
            coroutineContext.ensureActive()
            a.error?.let { throw it }
            if (a.completed && a.path != null) break

            val now = System.currentTimeMillis()
            if (now - lastSnapshotAt >= SNAPSHOT_MS) {
                lastSnapshotAt = now
                runCatching {
                    applyFile(a, tdLong(tdObj("@type" to "getFile", "file_id" to task.fileId)))
                }
            }
            if (now - a.lastProgressAt > STALL_MS) {
                throw IllegalStateException("下载停滞超过 ${STALL_MS / 60_000} 分钟")
            }
            delay(POLL_MS)
        }

        finish(task, a)
    }

    private suspend fun finish(task: DownloadTask, a: ActiveDownload) {
        val src = a.path ?: throw IllegalStateException("下载完成但本地路径为空")
        val total = if (a.total > 0L) a.total else a.downloaded
        val saved = withContext(Dispatchers.IO) { persistFile(task, src) }
        dao.markCompleted(task.id, saved, total)
        runCatching { notifier.notifyCompleted(task.id, task.displayName) }
        Log.i(TAG, "下载完成 task=${task.id} -> $saved")
        refreshBadge()
    }

    /** updateFile / getFile 快照 → 会话状态；顺带节流写库。 */
    private fun applyFile(a: ActiveDownload, file: JsonObject) {
        val local = file["local"] as? JsonObject
        (file["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
            ?.let { if (a.total == 0L) a.total = it }
        (file["expected_size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
            ?.let { if (a.total == 0L) a.total = it }
        if (local != null) {
            (local["downloaded_size"] as? JsonPrimitive)?.longOrNull?.let { d ->
                if (d > a.downloaded) {
                    a.downloaded = d
                    a.lastProgressAt = System.currentTimeMillis()
                }
            }
            (local["path"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?.let { a.path = it }
            if ((local["is_downloading_completed"] as? JsonPrimitive)?.booleanOrNull == true) {
                a.completed = true
            }
        }

        val now = System.currentTimeMillis()
        if (now - a.lastDbWrite >= PROGRESS_DB_MS) {
            a.lastDbWrite = now
            val done = if (a.completed) a.total.coerceAtLeast(a.downloaded) else a.downloaded
            val total = a.total
            scope.launch { runCatching { dao.updateProgress(a.taskId, done, total) } }
        }
    }

    // ═══════════════════════ 暂停/恢复内部 ═══════════════════════

    private suspend fun doPause(t: DownloadTask) {
        runCatching {
            td.tdCall(
                tdObj(
                    "@type" to "toggleDownloadIsPaused",
                    "file_id" to t.fileId,
                    "is_paused" to true,
                )
            )
        }
        dao.updateState(t.id, DownloadTask.STATE_PAUSED, null)
        jobs.remove(t.id)?.cancel()
        refreshBadge()
        pump()
    }

    private suspend fun doResume(t: DownloadTask) {
        runCatching {
            td.tdCall(
                tdObj(
                    "@type" to "toggleDownloadIsPaused",
                    "file_id" to t.fileId,
                    "is_paused" to false,
                )
            )
        }
        dao.updateState(t.id, DownloadTask.STATE_QUEUED, null)
        refreshBadge()
        pump()
    }

    // ═══════════════════════ 存储落盘 ═══════════════════════

    private suspend fun persistFile(task: DownloadTask, srcPath: String): String {
        val src = File(srcPath)
        if (!src.isFile) throw IllegalStateException("源文件不存在：$srcPath")
        val mode = settings.downloadStoreMode.first()
        val mime = mimeOf(task)
        return if (mode == "public") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(task, src, mime)
            } else {
                saveToLegacyPublic(task, src)
            }
        } else {
            savePrivate(task, src)
        }
    }

    private fun savePrivate(task: DownloadTask, src: File): String {
        val dir = context.getExternalFilesDir("downloads")
            ?: File(context.filesDir, "downloads")
        dir.mkdirs()
        val dest = uniqueFile(dir, task.displayName)
        src.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun saveToMediaStore(task: DownloadTask, src: File, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, task.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/TG云盘",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore 插入失败")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入 MediaStore")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyPublic(task: DownloadTask, src: File): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val dest = uniqueFile(dir, task.displayName)
        src.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun mimeOf(task: DownloadTask): String =
        if (task.kind == "image") "image/*" else "video/*"

    // ═══════════════════════ 工具 ═══════════════════════

    private suspend fun tdLong(q: JsonObject): JsonObject = td.tdCall(q, 600_000L)

    private suspend fun callWithFloodRetry(block: suspend () -> JsonObject): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TdRequestException) {
                val wait = parseFloodWait(e.message) ?: throw e
                if (wait > FLOOD_RETRY_MAX_S || attempt >= FLOOD_MAX_ATTEMPTS) throw e
                delay(wait * 1000L)
                attempt++
            }
        }
    }

    private fun parseFloodWait(message: String?): Int? {
        if (message == null) return null
        val marker = "FLOOD_WAIT_"
        val idx = message.indexOf(marker)
        if (idx < 0) return null
        return message.substring(idx + marker.length)
            .takeWhile { it.isDigit() }
            .toIntOrNull()
    }

    private fun refreshBadge() {
        scope.launch {
            val n = runCatching { dao.countActive() }.getOrDefault(0)
            badge.value = n
        }
    }

    /** 运行中会话状态。字段跨线程（updateFile 收流线程 / 任务协程），用 @Volatile。 */
    private class ActiveDownload(val taskId: Long, val fileId: Int) {
        @Volatile var downloaded = 0L
        @Volatile var total = 0L
        @Volatile var path: String? = null
        @Volatile var completed = false
        @Volatile var lastDbWrite = 0L
        @Volatile var lastProgressAt = System.currentTimeMillis()
        @Volatile var error: Throwable? = null
    }
}
