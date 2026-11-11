package com.yg.tgdrive.download

import android.os.SystemClock
import android.util.Log
import com.yg.tgdrive.data.TdRequestException
import com.yg.tgdrive.data.tdCall
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.tdObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/** TDLib downloadFile 的 offset 对齐粒度（SPEC §4.3：512KB PART_SIZE）。 */
const val PART_ALIGN_BYTES = 512L * 1024

/**
 * 下载层核心（SPEC §4.3）：把「播放器要任意字节区间」翻译成「TDLib 单条连续下载 + partial 文件随机读」，
 * 并对完整下载过的文件做 LRU 磁盘缓存。
 *
 * ═══ 单请求不变式（本版核心约束）═══
 * TDLib 对同一个 file **只有一条下载游标**：任何新的 downloadFile 都会取消前一条
 * （`td error 200: Canceled by another downloadFile request`）。上一版在 prepare 阶段
 * 同时铺「窗口异步分片」和「尾部 synchronous 精确拉取」，两者互踩 → 请求被反复取消 →
 * 用户看到「点进去有速度，几秒后速度归零、永远播不出来」。
 *
 * 本版因此强制：**同一 file 任何时刻只有一条 downloadFile 在飞**。
 * - 所有 downloadFile 发送点收敛到 [sendDownload]，且只在 [FileSession.mutex] 内调用；
 * - 「等数据落盘」也持锁进行（[awaitDataLocked]），期间不可能插入第二条请求；
 * - 彻底不再使用 `synchronous=true`（它与游标模型冲突，且失败后静默返回）。
 *
 * ═══ 可读判据 ═══
 * TDLib 自报的 `downloaded_prefix_size` / `downloaded_size` 在多窗口历史里出现过串台，
 * 而 partial 文件是按完整尺寸预分配的稀疏文件（未落地区域读出来是 0x00）。
 * 因此**唯一可信判据是磁盘真实字节**：轮询 `RandomAccessFile` 读目标偏移，
 * 读到非零才算可读。TDLib 状态只用来给 UI 画缓存进度。
 *
 * ═══ 窗口模型 ═══
 * 请求 = `downloadFile(offset=alignDown(pos), limit=0, priority=32)`，TDLib 从该点连续下到文件尾。
 * 位置变化（seek）→ 发新请求（旧请求随之被 TDLib 取消，属预期，由 [PlaybackDiag] 计数）。
 * 顺序播放时位置单调前进 → 全程只有一次请求，速度不会被反复重置。
 *
 * ServiceLocator 契约：StreamingDownloadManager(td: TdClient, concurrency: Flow<Int>, cacheDir: File)。
 * `concurrency` 现在只用于诊断记录：流播侧已改为单请求，并发路数不再由本层控制
 * （TDLib 本身按 file 串行；离线下载器有自己的并发闸门）。
 */
class StreamingDownloadManager(
    private val td: TdClient,
    concurrency: Flow<Int>,
    cacheDir: File,
) {
    companion object {
        private const val TAG = "StreamingDL"

        /** 流播下载优先级：TDLib priority 1=最低、32=最高。正片给最高 32，压过缩略图(2)/离线(16)。 */
        private const val PRIORITY_STREAM = 32

        /** 等落盘整体超时（单个区间）。 */
        private const val WAIT_TIMEOUT_MS = 60_000L

        /**
         * 探针读取窗口 = 单次 read 的落盘确认粒度。任意非零字节即视为该窗口已落盘，
         * 并把 [FileSession.confirmedEnd] 推进到窗口末尾（后续读同一区间不再探测）。
         */
        private const val PROBE_BYTES = 64 * 1024

        /** 探针轮询间隔。 */
        private const val PROBE_POLL_MS = 50L

        /** getFile 快照兜底间隔（防 updateFile 丢事件）。 */
        private const val SNAPSHOT_FALLBACK_MS = 5_000L

        /** 防串台保险：单条 update/file 快照允许推进 readable 的最大增量（8MB）。 */
        private const val MAX_PREFIX_JUMP = 8L * 1024 * 1024

        private const val LRU_DIR = "lru"
        private const val DEFAULT_CACHE_CAP_GB = 4L
        private const val GB = 1024L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lruDir = File(cacheDir, LRU_DIR)

    /** fileId → 会话（请求游标/可读状态/路径）。 */
    private val sessions = ConcurrentHashMap<Int, FileSession>()

    /** LRU 缓存上限（字节），由设置页热更。 */
    @Volatile private var cacheCapBytes = DEFAULT_CACHE_CAP_GB * GB

    /** LRU 整文件搬运去重：uid → promotion job。 */
    private val promoting = ConcurrentHashMap<String, Job>()

    /**
     * 单文件下载会话。字段跨线程访问（fileUpdates 收流线程 / 请求协程 / waiter）；
     * 请求发送与等待统一由 [mutex] 串行化 —— 这是「单请求不变式」的实现手段。
     */
    private class FileSession(val fileId: Int) {
        /** 串行化「发请求 + 等落盘」。持锁期间不会出现第二条 downloadFile。 */
        val mutex = Mutex()

        /** 当前请求的起点（512KB 对齐绝对偏移）；-1 = 尚未发过请求。 */
        @Volatile var base = -1L

        /** 相对 [base] 的连续可读前缀字节数（TDLib 自报，仅用于 UI 进度，不作判据）。 */
        val readable = MutableStateFlow(0L)

        /** TDLib 本地文件路径（下载中 = partial 路径，完成后 = 最终文件路径）。 */
        val partialPath = MutableStateFlow<String?>(null)

        /** 整文件下载完成（local.is_downloading_completed=true）。 */
        val completed = MutableStateFlow(false)

        /** 云端已知文件大小（file.size / expected_size，越界钳制用）。 */
        @Volatile var expectedSize = 0L

        /** TDLib local.downloaded_size：全文件累计已下载字节（任意位置，非前缀）。 */
        @Volatile var downloadedSize = 0L

        /**
         * 已确认落盘的最高偏移（磁盘真值水位，跨请求单调不减 —— 落盘数据不会被删）。
         * 读路径据此钳制单次 read 长度，绝不让 TDLib 预分配的全零区当数据交给解析器。
         */
        @Volatile var confirmedEnd = 0L

        /** 发过新请求后丢弃旧引用 update 的开关，直到该请求的响应建立基线。 */
        @Volatile var awaitingBaseline = true

        /** 请求失败（粘滞；下次发新请求时清除）。 */
        @Volatile var failure: Throwable? = null

        /** 上层 attach 的 LRU key（TdDataSource.open 从 URI uid 参数传入）。 */
        @Volatile var uniqueId: String? = null

        /** getFile 快照是否已做过一次（防重复快照）。 */
        @Volatile var snapshotted = false

        /** 上层已放弃本会话（cancelAll 硬取消）：updateFile/applyFileState 不再复活。 */
        @Volatile var abandoned = false
    }

    // ═══════════════════════ 生命周期 ═══════════════════════

    init {
        lruDir.mkdirs()
        // 并发偏好只做记录：流播已改为单请求，并发路数由 TDLib 按 file 串行决定。
        scope.launch {
            concurrency.collect { PlaybackDiag.concurrency(it) }
        }
        // updateFile 分发：file.id → 会话状态（进度展示用）
        scope.launch {
            td.fileUpdates.collect { upd ->
                try {
                    val file = upd["file"] as? JsonObject ?: return@collect
                    val id = (file["id"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return@collect
                    sessions[id]?.let { applyFileState(it, file, establishBaseline = false) }
                } catch (t: Throwable) {
                }
            }
        }
    }

    // ═══════════════════════ 公开 API（ServiceLocator 契约） ═══════════════════════

    /**
     * 确保从 offset 起至少 len 字节可读后返回（挂起等待落盘）。
     * 返回 [PartialRef]：调用方 RandomAccessFile(ref.partialPath).seek(pos - ref.globalOffset)。
     * 可读来源三选一：LRU 完整缓存（globalOffset=0）、TDLib 已完成文件（globalOffset=0）、
     * 或 TDLib partial 文件（globalOffset=当前请求起点，文件长度已到完整尺寸时按 0 处理）。
     *
     * 失败一律抛 [DownloadException]（绝不静默返回"看起来可读"的引用）。
     *
     * @param tag 调用点标签（`prepare` / `stream`），只用于诊断归因。
     */
    suspend fun ensureRange(
        fileId: Int,
        offset: Long,
        len: Long,
        tag: String = "stream",
        requireEnd: Boolean = false,
    ): PartialRef {
        val need = if (len <= 0) 1L else len
        val s = session(fileId)
        ensureSnapshot(s)

        // ① LRU 命中：完整缓存文件直接读，零 TDLib 调用
        s.uniqueId?.let { uid ->
            cacheDirFor(uid)?.let { return PartialRef(it.absolutePath, 0L) }
        }
        // ② 整文件已完成（TDLib files 目录最终文件；字节 0 = 文件起点）
        if (s.completed.value) {
            val path = s.partialPath.value ?: throw DownloadException("file $fileId 已完成但无本地路径")
            return PartialRef(path, 0L)
        }

        val aligned = alignDown(offset)
        val expected = s.expectedSize
        // 起点越过文件尾：不发起下载，交给上层按 EOF 处理
        if (expected > 0L && offset >= expected) {
            val path = s.partialPath.value
                ?: throw DownloadException("file $fileId 无本地路径且区间越界")
            return PartialRef(path, 0L)
        }

        val t0 = SystemClock.elapsedRealtime()
        var reason = "ok"
        try {
            s.mutex.withLock {
                if (s.abandoned) throw DownloadException("file $fileId 会话已取消")
                // 单请求：只有"游标真需要移动"时才发新请求。
                // 顺序播放时位置单调前进且落在已请求区间之后 → 让在飞的那条 limit=0 请求
                // 继续从 base 顺序下到文件尾，**一次都不重发**（重发会让 TDLib 反复重置游标、
                // 掉速，是旧版「几秒后有速度然后归零」的成因之一）。
                if (needsNewRequest(s, aligned)) sendDownload(s, aligned, tag)
                awaitDataLocked(s, offset, spanLen(offset, need, expected), tag)
                if (requireEnd) {
                    // 需要整段可读（moov 本体）：再确认区间末尾也已落盘，
                    // 否则 TDLib 预分配的全零尾巴会让 moov 解析失败。
                    val spanEnd = if (expected > 0L) min(offset + need, expected) else offset + need
                    val lastPos = (spanEnd - PROBE_BYTES).coerceAtLeast(offset)
                    if (lastPos > offset) awaitDataLocked(s, lastPos, PROBE_BYTES.toLong(), tag)
                }
            }
        } catch (t: Throwable) {
            reason = t.message ?: t::class.java.simpleName
            throw t
        } finally {
            PlaybackDiag.ensure(
                offset = offset,
                len = need,
                expectedSize = expected,
                nearTail = expected > 0L && expected - offset <= need,
                mode = tag,
                ms = SystemClock.elapsedRealtime() - t0,
                reason = reason,
            )
        }

        // 等待期间文件可能整文件完成 → 改用最终文件引用（globalOffset=0）
        if (s.completed.value) {
            val path = s.partialPath.value ?: throw DownloadException("file $fileId 已完成但无本地路径")
            return PartialRef(path, 0L)
        }
        val path = s.partialPath.value ?: throw DownloadException("file $fileId 无 partial 路径")
        val fileLen = File(path).length()
        // partial 已是完整尺寸（TDLib 预分配）→ 绝对偏移；否则该文件从请求起点开始。
        val globalOffset = if (expected > 0L && fileLen >= expected) 0L else s.base.coerceAtLeast(0L)
        return PartialRef(path, globalOffset)
    }

    /**
     * 收掉该 file 的下载（prepare 规划超时/放弃时调用）：停掉在飞请求，会话标记 abandoned，
     * 让随后的 TdDataSource 从干净的游标重新发起。已落盘数据不丢。
     */
    fun abortPending(fileId: Int) {
        val s = sessions.remove(fileId) ?: return
        s.abandoned = true
        scope.launch {
            runCatching {
                tdLong(tdObj(
                    "@type" to "cancelDownloadFile",
                    "file_id" to fileId,
                    "only_pending" to false,
                ))
            }.onFailure { Log.w(TAG, "cancelDownloadFile $fileId failed: ${it.message}") }
        }
    }

    /**
     * 硬取消全部会话（播放页 onStop/onDestroy 调用）。
     *
     * TDLib 端在飞的 downloadFile 可能是 limit=0（连续下到文件尾），only_pending=true 拦不住，
     * 因此用 only_pending=false 硬取消；并把会话标记 abandoned，防止迟到的 updateFile 把会话
     * 救活、再次拉起下载。
     */
    fun cancelAll() {
        sessions.keys.toList().forEach { fileId ->
            val s = sessions[fileId] ?: return@forEach
            s.abandoned = true
            scope.launch {
                runCatching {
                    tdLong(tdObj(
                        "@type" to "cancelDownloadFile",
                        "file_id" to fileId,
                        "only_pending" to false,
                    ))
                }.onFailure { Log.w(TAG, "cancelDownloadFile(hard) $fileId failed: ${it.message}") }
            }
        }
        sessions.clear()
    }

    /** 清空 LRU 缓存目录（设置页「清空缓存」钩子；MediaRepository.clearCacheHook 委托目标）。 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        promoting.values.forEach { it.cancel() }
        promoting.clear()
        lruDir.deleteRecursively()
        lruDir.mkdirs()
        Unit
    }

    /**
     * 若该文件已被 TDLib 完整下载（local.is_downloading_completed=true），返回其本地完整文件，
     * 供播放层直连（零 TDLib 下载、跳过 faststart 重排）。未完成返回 null。
     *
     * 完整文件本地随机读时，moov 即便在文件尾也能按绝对偏移正确 seek，不存在流式
     * DataSource 的"load 起点当作原点"二次叠加问题，因此完整文件无需 FaststartRemuxer。
     */
    suspend fun completedLocalFile(fileId: Int): File? {
        val s = session(fileId)
        ensureSnapshot(s)
        if (!s.completed.value) return null
        val path = s.partialPath.value ?: return null
        val f = File(path)
        return if (f.isFile && f.length() > 0L) f else null
    }

    /** LRU 命中查询：完整缓存文件存在则返回（并刷新访问序），否则 null。 */
    fun cacheDirFor(fileUniqueId: String): File? {
        if (fileUniqueId.isBlank()) return null
        val f = File(lruDir, fileUniqueId)
        if (!f.isFile || f.length() <= 0) return null
        runCatching {
            File(lruDir, "$fileUniqueId.at").writeText(System.currentTimeMillis().toString())
        }
        return f
    }

    /**
     * 供播放页画「缓存进度」：返回当前文件"连续可读到"的绝对偏移（= 请求起点 + 窗口内可读前缀）。
     * 比 downloaded_size（全文件任意位置累计）更贴近真实可播放范围：从头播放即 0..N 连续前缀，
     * 中段/续播即 base..base+readable。整文件完成 = 总大小。
     */
    fun bufferedBytes(fileId: Int): Long {
        val s = sessions[fileId] ?: return 0L
        if (s.completed.value) return s.expectedSize.takeIf { it > 0L } ?: Long.MAX_VALUE
        val originBase = if (s.base > 0L) s.base else 0L
        val frontier = originBase + s.readable.value
        val total = s.expectedSize
        var end = maxOf(s.downloadedSize, frontier)
        if (total > 0L) end = end.coerceAtMost(total)
        return end
    }

    /** 当前文件总字节（未知 = 0）。 */
    fun totalBytes(fileId: Int): Long = sessions[fileId]?.expectedSize ?: 0L

    /** LRU 缓存上限（GB），由设置页滑杆热更。 */
    fun setCacheCapGb(gb: Long) {
        if (gb > 0) cacheCapBytes = gb * GB
    }

    /** TdDataSource.open 时把 media.fileUniqueId 关联到 fileId（LRU key 通道）。 */
    fun attachUniqueId(fileId: Int, fileUniqueId: String) {
        if (fileUniqueId.isBlank()) return
        session(fileId).uniqueId = fileUniqueId
    }

    // ═══════════════════════ 请求调度（单请求不变式） ═══════════════════════

    private fun session(fileId: Int): FileSession =
        sessions.getOrPut(fileId) { FileSession(fileId) }

    /**
     * 下载层 TD 调用统一走长超时：非同步 downloadFile 会立即返回，但代理慢时
     * getFile / cancelDownloadFile 也可能拖，600s 只作兜底。
     */
    private suspend fun tdLong(q: JsonObject): JsonObject = td.tdCall(q, 600_000L)

    private suspend fun ensureSnapshot(s: FileSession) {
        if (s.snapshotted) return
        s.mutex.withLock {
            if (s.snapshotted) return
            try {
                val resp = tdLong(tdObj("@type" to "getFile", "file_id" to s.fileId))
                applyFileState(s, resp, establishBaseline = false)
            } catch (t: Throwable) {
            }
            s.snapshotted = true
        }
    }

    /**
     * 发送唯一一条连续下载请求：`downloadFile(offset, limit=0)`，TDLib 从 offset 顺序下到文件尾。
     *
     * 必须在 [FileSession.mutex] 内调用 —— 这是「同一 file 任何时刻只有一条 downloadFile」的
     * 结构性保证；[PlaybackDiag.requestEnter] 再做一次运行期观测（并发 >1 会记日志）。
     */
    private suspend fun sendDownload(s: FileSession, offset: Long, tag: String) {
        if (s.failure != null) s.failure = null
        val violation = PlaybackDiag.requestEnter(s.fileId)
        if (violation) Log.e(TAG, "单请求不变式被破坏 file=${s.fileId} offset=$offset tag=$tag")
        try {
            val resp = tdLong(tdObj(
                "@type" to "downloadFile",
                "file_id" to s.fileId,
                "offset" to offset,
                "limit" to 0,
                "priority" to PRIORITY_STREAM,
            ))
            // 响应即 file 快照：建立新请求的前缀基线（旧引用的 update 已被 awaitingBaseline 挡掉）
            s.base = offset
            s.readable.value = 0L
            s.awaitingBaseline = true
            applyFileState(s, resp, establishBaseline = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            s.base = offset
            s.failure = t
            val canceled = isCanceled(t)
            if (canceled) PlaybackDiag.cancel(tag)
            Log.w(TAG, "downloadFile file=${s.fileId}@$offset 失败: ${t.message}")
            throw DownloadException("file ${s.fileId}@$offset 发起下载失败: ${t.message}", t)
        } finally {
            PlaybackDiag.requestExit(s.fileId)
        }
    }

    /** 本次需求在文件内的有效长度（钳制到文件尾）。 */
    private fun spanLen(offset: Long, need: Long, expected: Long): Long =
        if (expected > 0L) min(need, (expected - offset).coerceAtLeast(1L)) else need

    /** 当前在飞请求是否已经覆盖 [aligned]（limit=0 = 从请求起点一路下到文件尾）。 */
    private fun needsNewRequest(s: FileSession, aligned: Long): Boolean {
        if (s.failure != null) return true        // 上一条失败（被取消/网络错）→ 重发
        if (s.base < 0L) return true              // 还没发过
        return aligned < s.base                   // 只有向前 seek 才需要移动游标
    }

    /** TDLib 的「被另一个 downloadFile 取消」（code 200 + Canceled）判定。 */
    private fun isCanceled(t: Throwable): Boolean {
        val msg = t.message ?: return false
        return msg.contains("Canceled by another downloadFile", ignoreCase = true) ||
            (msg.contains("td error 200") && msg.contains("Cancel", ignoreCase = true))
    }

    /**
     * 挂起等待 [offset] 处真实字节可读。
     * 必须在 [FileSession.mutex] 内调用，保证等待期间不会有第二条请求插进来把游标拨走。
     *
     * 判据优先级：
     * 1. 整文件完成 → 立刻放行；
     * 2. TDLib 自报的连续前缀覆盖 [offset, offset+need) → 放行。单请求模型下
     *    `downloaded_prefix_size` 不再有"多窗口串台"歧义（同一 file 恒只有一条请求），
     *    这是零 IO 的快路径，也是 mdat 里存在合法全零数据时不会误判的唯一安全路径；
     * 3. 磁盘真值探针（[nonZeroIn]）：前缀未覆盖（seek 到前缀之外）时用它确认落盘。
     *
     * 只在快照兜底间隔刷新一次 getFile（不再像旧版每 200ms 重发 downloadFile ——
     * 那会让 TDLib 反复重置游标，是「速度归零」的直接成因之一）。
     */
    private suspend fun awaitDataLocked(s: FileSession, offset: Long, need: Long, tag: String) {
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + WAIT_TIMEOUT_MS
        var nextSnapshotAt = t0 + SNAPSHOT_FALLBACK_MS
        var polls = 0
        var lastNonZero = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                if (s.completed.value) {
                    PlaybackDiag.probe(offset, polls, lastNonZero, SystemClock.elapsedRealtime() - t0, "completed")
                    return
                }
                // 水位已覆盖需求（含 TDLib 连续前缀推进 + 之前探针确认过的区间）
                if (s.confirmedEnd - offset >= need) {
                    PlaybackDiag.probe(offset, polls, lastNonZero, SystemClock.elapsedRealtime() - t0, "confirmed")
                    return
                }
                val path = s.partialPath.value
                if (path != null) {
                    val n = nonZeroIn(path, offset)
                    if (n > 0) {
                        lastNonZero = n
                        s.confirmedEnd = maxOf(s.confirmedEnd, offset + PROBE_BYTES)
                        PlaybackDiag.probe(offset, polls, n, SystemClock.elapsedRealtime() - t0, "disk")
                        return
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextSnapshotAt) {
                    nextSnapshotAt = now + SNAPSHOT_FALLBACK_MS
                    runCatching {
                        applyFileState(
                            s,
                            tdLong(tdObj("@type" to "getFile", "file_id" to s.fileId)),
                            establishBaseline = false,
                        )
                    }
                    if (s.completed.value) continue
                }
                if (now >= deadline) {
                    PlaybackDiag.probe(offset, polls, 0, now - t0, "timeout")
                    throw DownloadException(
                        "file ${s.fileId} offset=$offset 区域 ${WAIT_TIMEOUT_MS / 1000}s 内未落盘" +
                            "（base=${s.base} prefix=${s.readable.value} tag=$tag）"
                    )
                }
                polls++
                delay(PROBE_POLL_MS)
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (t is DownloadException) throw t
            PlaybackDiag.probe(offset, polls, lastNonZero, SystemClock.elapsedRealtime() - t0, "error:${t.message}")
            throw t
        }
    }

    /**
     * 从 [pos] 起**保证**已落盘的连续字节数（0 = 尚未确认）。
     * 供 TdDataSource 钳制单次 read 长度：读进未落盘区只会拿到 TDLib 预分配的全零。
     */
    fun readableBytesFrom(fileId: Int, pos: Long): Long {
        val s = sessions[fileId] ?: return 0L
        if (s.completed.value) return Long.MAX_VALUE / 4
        return (s.confirmedEnd - pos).coerceAtLeast(0L)
    }

    /** 读 [pos] 起的 [PROBE_BYTES] 字节，返回非零字节数（0 = 空洞或不可读）。 */
    private fun nonZeroIn(path: String, pos: Long): Int = runCatching {
        RandomAccessFile(path, "r").use { raf ->
            if (raf.length() <= pos) return@use 0
            raf.seek(pos)
            val buf = ByteArray(PROBE_BYTES)
            val n = raf.read(buf)
            if (n <= 0) 0 else buf.count { it.toInt() != 0 }
        }
    }.getOrDefault(0)

    // ═══════════════════════ 状态应用（file 对象 → 会话） ═══════════════════════

    /**
     * 把 file 对象（getFile 快照 / downloadFile 响应 / updateFile）应用到会话。
     * establishBaseline=true 仅用于新请求的响应：此后 update 携带的
     * downloaded_prefix_size 相对值才可信。
     */
    private fun applyFileState(s: FileSession, file: JsonObject, establishBaseline: Boolean) {
        if (s.abandoned) return   // 已硬取消，迟到的 file 推送一律忽略，防止复活下载
        val local = file["local"] as? JsonObject ?: return

        (local["downloaded_size"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it > 0L }
            ?.let { if (it > s.downloadedSize) s.downloadedSize = it }

        (local["path"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { if (s.partialPath.value != it) s.partialPath.value = it }

        (file["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ?.let { if (s.expectedSize == 0L) s.expectedSize = it }
        (file["expected_size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ?.let { if (s.expectedSize == 0L) s.expectedSize = it }

        val completed = (local["is_downloading_completed"] as? JsonPrimitive)?.booleanOrNull == true
        if (completed && !s.completed.value) {
            s.completed.value = true
            s.confirmedEnd = Long.MAX_VALUE / 4
            s.readable.value = Long.MAX_VALUE / 4   // 整文件可读，全部 waiter 放行
            promoteToLru(s)
            return
        }

        if (establishBaseline) {
            s.awaitingBaseline = false
            val p0 = (local["downloaded_prefix_size"] as? JsonPrimitive)?.longOrNull ?: 0L
            s.readable.value = p0
            if (s.base >= 0L && p0 > 0L) s.confirmedEnd = maxOf(s.confirmedEnd, s.base + p0)
            return
        }
        if (s.awaitingBaseline) return   // 新请求基线建立前：旧引用 update 一律丢弃

        val prefix = (local["downloaded_prefix_size"] as? JsonPrimitive)?.longOrNull ?: return
        if (prefix <= 0) return
        // 单事件增量钳制：防止基线错位/串台注入虚假大前缀（防呆上限 8MB/事件）
        s.readable.update { cur -> if (prefix > cur) min(prefix, cur + MAX_PREFIX_JUMP) else cur }
        // 单请求模型下前缀不再有多窗口歧义：同步推进磁盘真值水位（零 IO 快路径）
        if (s.base >= 0L) s.confirmedEnd = maxOf(s.confirmedEnd, s.base + s.readable.value)
    }

    // ═══════════════════════ LRU 缓存 ═══════════════════════

    /** 整文件完成 → 后台拷贝进 LRU 池（key=fileUniqueId），再按上限逐出。 */
    private fun promoteToLru(s: FileSession) {
        val uid = s.uniqueId ?: return
        val srcPath = s.partialPath.value ?: return
        val dst = File(lruDir, uid)
        if (dst.isFile && dst.length() > 0) return
        if (promoting[uid]?.isActive == true) return
        promoting[uid] = scope.launch(Dispatchers.IO) {
            try {
                lruDir.mkdirs()
                val tmp = File(lruDir, "$uid.tmp")
                try {
                    File(srcPath).copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(dst)) {
                        tmp.copyTo(dst, overwrite = true)
                        tmp.delete()
                    }
                } catch (e: IOException) {
                    // ENOSPC（SPEC §8）：逐出最旧后重试一次，仍败则放弃（纯流播不受影响）
                    evictLruOldest()
                    runCatching { File(srcPath).copyTo(dst, overwrite = true) }
                    tmp.delete()
                }
                enforceLruCap()
            } catch (t: Throwable) {
            } finally {
                promoting.remove(uid)
            }
        }
    }

    /** 超上限逐出：按访问序（.at 标记时间，缺省 lastModified）从最旧开始删。 */
    private fun enforceLruCap() {
        val files = lruDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= cacheCapBytes) return
        files.sortedBy { accessTimeOf(it) }.forEach { f ->
            if (total <= cacheCapBytes) return
            val len = f.length()
            if (f.delete()) {
                total -= len
                File(lruDir, "${f.name}.at").delete()
            }
        }
    }

    private fun evictLruOldest() {
        lruDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.minByOrNull { accessTimeOf(it) }
            ?.let {
                it.delete()
                File(lruDir, "${it.name}.at").delete()
            }
    }

    private fun accessTimeOf(f: File): Long {
        val marker = File(lruDir, "${f.name}.at")
        if (marker.isFile) {
            val parsed = runCatching { marker.readText().trim().toLongOrNull() }.getOrNull()
            if (parsed != null) return parsed
        }
        return f.lastModified()
    }

    private fun alignDown(offset: Long): Long =
        if (offset >= 0) offset / PART_ALIGN_BYTES * PART_ALIGN_BYTES else 0L
}
