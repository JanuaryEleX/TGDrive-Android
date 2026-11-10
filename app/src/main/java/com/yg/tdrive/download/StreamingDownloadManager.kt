package com.yg.tgdrive.download

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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** TDLib downloadFile 的 offset/limit 对齐粒度（SPEC §4.3：512KB PART_SIZE）。 */
const val PART_ALIGN_BYTES = 512L * 1024

/** 窗口内单个分片请求长度（1MB = 2 个 512KB part；任务书：limit 传 512KB 的倍数）。 */
const val CHUNK_BYTES = 1024L * 1024

/**
 * 下载层核心（SPEC §4.3，任务书契约版）：把「播放器要任意字节区间」翻译成
 * 「TDLib 分片并发下载 + partial 文件随机读」，并对完整下载过的文件做 LRU 磁盘缓存。
 *
 * TDLib 对本层是纯黑盒 JSON 服务，只依赖任务书给定的四个事实：
 * 1. downloadFile(file_id, offset, limit, priority)：offset/limit 按 512KB 对齐，limit=1MB；
 *    下载内容追加到该 file 的 local.partial_path（partial 字节 0 = 本次请求 offset）。
 * 2. updateFile（td.fileUpdates 推送）：file.local.downloaded_prefix_size = 相对本次请求
 *    offset 的连续已落盘前缀字节数 —— 「可读」的唯一判据。
 * 3. cancelDownloadFile(file_id, only_pending=true)：取消排队分片，已落盘数据不丢。
 * 4. getFile(file_id)：同步拉一次文件状态快照。
 *
 * 并发窗口模型：
 * - 窗口 = [base, requestedEnd)，base 为 512KB 对齐的请求起点；chunk=1MB 顺序铺满窗口。
 * - 同一窗口内只前伸补发缺失 chunk（不 cancel）；aligned 跳出窗口（seek）→ 整窗平移：
 *   cancelPending(only_pending) + 重置基线 + 从新 base 重新铺 chunk。
 * - downloaded_prefix_size 相对「本次请求」（= 窗口首个 chunk 的请求 offset，即 base）；
 *   平移后丢弃旧引用的 update，直到新窗口首个 downloadFile 响应建立新基线。
 * - Semaphore 控制并发路数（默认 4，热更 1..8）；chunk 失败重试 2 次（1s/3s）后置会话失败。
 *
 * ServiceLocator 契约：StreamingDownloadManager(td: TdClient, concurrency: Flow<Int>, cacheDir: File)。
 */
class StreamingDownloadManager(
    private val td: TdClient,
    concurrency: Flow<Int>,
    cacheDir: File,
) {
    companion object {
        private const val TAG = "StreamingDL"

        /** 窗口前伸下限：max(4MB, len*2)（SPEC §4.3）。 */
        private const val WINDOW_MIN_AHEAD = 4L * 1024 * 1024

        /** 流播下载优先级（缩略图 priority=2 后台拉取；正片 priority=1 更优先）。 */
        private const val PRIORITY_STREAM = 1

        /** chunk 尝试次数：1 次首发 + 2 次重试（1s/3s，SPEC §4.3）。 */
        private const val CHUNK_ATTEMPTS = 3
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)

        /** 单次 chunk 尝试内无进展判定（readable 不动视为卡死，抛出触发重试）。 */
        private const val CHUNK_STALL_MS = 30_000L

        /** ensureRange 等待器整体超时。 */
        private const val WAIT_TIMEOUT_MS = 60_000L

        /** 起播最小可读字节：TDLib 逐块落盘，等满 PREHEAT 会白等。 */
        private const val MIN_READY_BYTES = 256L * 1024

        /** 真实字节探针：读多少字节判定非空洞。 */
        private const val PROBE_BYTES = 32

        /** 探针轮询间隔。 */
        private const val PROBE_POLL_MS = 200L

        /** 探针最长等待（超时上抛 → 播放页错误覆盖层）。 */
        private const val PROBE_TIMEOUT_MS = 90_000L

        /** synchronous downloadFile 拉取非顺序区间的最长等待。 */
        private const val SYNC_FETCH_TIMEOUT_MS = 180_000L

        /** 判定为"尾部窗口"的距离阈值：距文件尾 ≤ 32MB 时整段同步拉取。 */
        private const val TAIL_SYNC_WINDOW = 32L * 1024 * 1024

        /** 等待轮询间隔 / getFile 快照兜底间隔。 */
        private const val POLL_MS = 50L
        private const val SNAPSHOT_FALLBACK_MS = 5_000L

        /** 并发上限（SPEC §7：硬上限 8）。 */
        const val MAX_CONCURRENCY = 8
        private const val DEFAULT_CONCURRENCY = 1

        /** 防串台保险：单条 update/file 快照允许推进 readable 的最大增量（8MB）。 */
        private const val MAX_PREFIX_JUMP = 8L * 1024 * 1024

        private const val LRU_DIR = "lru"
        private const val DEFAULT_CACHE_CAP_GB = 4L
        private const val GB = 1024L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lruDir = File(cacheDir, LRU_DIR)

    /** fileId → 会话（窗口/可读状态/chunk 在飞表）。 */
    private val sessions = ConcurrentHashMap<Int, FileSession>()

    /** 可热更并发闸门（构造时 collect(concurrency) 自动同步；设置页滑杆实时生效）。 */
    private val gate = ResizableSemaphore(DEFAULT_CONCURRENCY)

    /** LRU 缓存上限（字节），后续接 settings.cacheCapGb。 */
    @Volatile private var cacheCapBytes = DEFAULT_CACHE_CAP_GB * GB

    /** LRU 整文件搬运去重：uid → promotion job。 */
    private val promoting = ConcurrentHashMap<String, Job>()

    /** 在飞/排队 chunk 描述。 */
    private class ChunkJob {
        @Volatile var canceled = false
        var job: Job? = null
    }

    /**
     * 单文件下载会话。字段跨线程访问（fileUpdates 收流线程 / chunk job / waiter）；
     * 窗口结构变更（shift / fill / 快照）统一由 [mutex] 串行化。
     */
    private class FileSession(val fileId: Int) {
        /** 串行化窗口变更。 */
        val mutex = Mutex()

        /** 当前窗口基址（512KB 对齐绝对偏移）；-1 = 尚未开窗。 */
        @Volatile var base = -1L

        /** 窗口已请求到的终点（绝对偏移）。 */
        @Volatile var requestedEnd = 0L

        /** 相对 [base] 的连续可读前缀字节数（可读唯一判据，窗口内单调不减）。 */
        val readable = MutableStateFlow(0L)

        /** TDLib 本地文件路径（下载中 = partial 路径，完成后 = 最终文件路径）。 */
        val partialPath = MutableStateFlow<String?>(null)

        /** 整文件下载完成（local.is_downloading_completed=true）。 */
        val completed = MutableStateFlow(false)

        /** 云端已知文件大小（file.size / expected_size，seek 越界钳制用）。 */
        @Volatile var expectedSize = 0L

        /** TDLib local.downloaded_size：全文件累计已下载字节（任意位置，非前缀）。 */
        @Volatile var downloadedSize = 0L

        /**
         * 窗口平移瞬间的 [downloadedSize] 基线。尾部窗口（base>0，MP4 moov 在文件尾）
         * 的"可读"判据：downloadedSize - tailBaseline ≥ 区间长度。
         * 前缀判据 [readable] 对尾部永远不成立，必须区分。
         */
        @Volatile var tailBaseline = 0L

        /** 平移后丢弃旧引用 update 的开关，直到新窗口首个 downloadFile 响应建立基线。 */
        @Volatile var awaitingBaseline = true

        /** 首个 chunk 重试耗尽的失败（粘滞；补发新 chunk 时清除）。 */
        @Volatile var failure: Throwable? = null

        /** 窗口内在飞/排队 chunk：chunk 绝对偏移 → job。 */
        val chunks = ConcurrentHashMap<Long, ChunkJob>()

        /** 上层 attach 的 LRU key（TdDataSource.open 从 URI uid 参数传入）。 */
        @Volatile var uniqueId: String? = null

        /** getFile 快照是否已做过一次（防重复快照）。 */
        @Volatile var snapshotted = false
    }

    // ═══════════════════════ 生命周期 ═══════════════════════

    init {
        lruDir.mkdirs()
        // 并发热更：SettingsStore.concurrency（1..8 默认 4）→ manager 内部 collect 自动同步
        scope.launch {
            concurrency.collect { setConcurrency(it) }
        }
        // updateFile 分发：file.id → 会话状态（可读判据的唯一推送入口）
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
     * 可读来源二选一：LRU 完整缓存命中（globalOffset=0）或 TDLib partial 文件（globalOffset=窗口基址）。
     */
    suspend fun ensureRange(fileId: Int, offset: Long, len: Long): PartialRef {
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
        var endAbs = offset + need
        if (s.expectedSize > 0) endAbs = min(endAbs, s.expectedSize)
        // 尾部窗口（moov 常在文件尾）：一次等满 [aligned, 文件末尾)，
        // 否则 moov 被截断，ExoPlayer 准备阶段直接判定"没找到 moov"。
        // 只有"距离文件尾很近"的窗口才算尾部窗口（moov 场景）。早期实现把任何
        // aligned>0 都当尾部，导致每次读中段都去同步拉取 [offset, 文件末尾) 几 GB。
        val nearTail = s.expectedSize > 0L && s.expectedSize - offset <= TAIL_SYNC_WINDOW
        if (nearTail) endAbs = s.expectedSize
        if (endAbs <= aligned) {
            // 起点越过文件尾（对齐后无有效区间）：返回当前引用，由上层按 EOF 处理
            val path = s.partialPath.value
                ?: throw DownloadException("file $fileId 无本地路径且区间越界")
            return PartialRef(path, s.base.coerceAtLeast(0L))
        }

        // ③④ 窗口维护（串行）：aligned 跳出当前窗口 → 平移；再补齐 [aligned, aligned+ahead) chunk
        s.mutex.withLock {
            val keepWindow = s.base >= 0 && aligned >= s.base && aligned <= s.requestedEnd
            if (!keepWindow) shiftWindowLocked(s, aligned)
            // 尾部窗口（moov 在文件尾）只走 synchronous 精确拉取，不再铺异步分片：
            // 两者并存会互相取消 → "td error 200: Canceled by another downloadFile request"，
            // 该异常一路上抛成 ExoPlayer 的 Loader$UnexpectedLoaderException。
            if (aligned <= 0L) {
                scheduleWindowChunksLocked(s, aligned, max(WINDOW_MIN_AHEAD, need * 2))
            }
        }

        // ⑤ 尾部窗口（moov 在文件尾）先同步精确拉取 [offset, 文件末尾)，跳过前缀等待；
        //    头部/中段窗口按 TDLib 逐块落盘等待最小可读窗口。
        if (nearTail && !s.completed.value) {
            fetchRangeSync(s, offset, s.expectedSize - offset)
        } else {
            val readyTarget = if (s.base > 0L) endAbs else min(endAbs, offset + MIN_READY_BYTES)
            awaitReadable(s, s.base, readyTarget, endAbs)
        }

        // ⑥ 真实字节校验：TDLib 预分配稀疏文件，未落地区域读出全 0x00。
        //    TDLib 自报状态在多窗口并发时会串台，这里以磁盘真实内容为准。
        // 尾部窗口（moov 在文件尾）：异步 downloadFile 的 offset 只是"优先级提示"，
        // 实测 TDLib 仍按自己的游标从 0 顺序下（prefix 涨、尾部仍是空洞）。
        // 必须用 synchronous=true 精确拉取该区间，等待其真正落盘。
        if (!s.completed.value) {
            probeNonZero(s, offset)
            // 尾部窗口再校验文件末端，保证 moov 整段都已落盘
            if (nearTail && s.expectedSize > PROBE_BYTES) {
                probeNonZero(s, s.expectedSize - PROBE_BYTES)
            }
        }

        // 等待期间文件可能整文件完成 → 改用最终文件引用（globalOffset=0）
        if (s.completed.value) {
            val path = s.partialPath.value ?: throw DownloadException("file $fileId 已完成但无本地路径")
            return PartialRef(path, 0L)
        }
        val path = s.partialPath.value ?: throw DownloadException("file $fileId 无 partial 路径")
        return PartialRef(path, s.base)
    }

    /**
     * 取消某文件的排队/在飞分片（SPEC §4.3：seek 后用它避免旧窗口继续烧流量）。
     * 只取消排队未启动的 TDLib 分片（only_pending=true），已落盘数据不丢。
     */
    fun cancelPending(fileId: Int) {
        val s = sessions[fileId] ?: return
        val jobs = s.chunks.values.toList()
        s.chunks.clear()
        if (jobs.isEmpty()) return
        jobs.forEach { it.canceled = true; it.job?.cancel() }
        scope.launch {
            runCatching {
                tdLong(tdObj(
                    "@type" to "cancelDownloadFile",
                    "file_id" to fileId,
                    "only_pending" to true,
                ))
            }.onFailure { Log.w(TAG, "cancelDownloadFile $fileId failed: ${it.message}") }
        }
    }

    /** 取消全部会话的在飞分片（播放页 onDestroy 调用）。 */
    fun cancelAll() {
        sessions.keys.toList().forEach { cancelPending(it) }
    }

    /** 清空 LRU 缓存目录（设置页「清空缓存」钩子；MediaRepository.clearCacheHook 委托目标）。 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        promoting.values.forEach { it.cancel() }
        promoting.clear()
        lruDir.deleteRecursively()
        lruDir.mkdirs()
        Unit
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

    /** 并发热更 1..8（进行中的 job 持有旧许可并在归还时释放，新 job 立即按新上限排队）。 */
    fun setConcurrency(n: Int) {
        gate.resize(n.coerceIn(1, MAX_CONCURRENCY))
    }

    /** LRU 缓存上限（GB），可选接口；后续接 settings.cacheCapGb（本轮常量 4GB）。 */
    fun setCacheCapGb(gb: Long) {
        if (gb > 0) cacheCapBytes = gb * GB
    }

    /** TdDataSource.open 时把 media.fileUniqueId 关联到 fileId（LRU key 通道）。 */
    fun attachUniqueId(fileId: Int, fileUniqueId: String) {
        if (fileUniqueId.isBlank()) return
        session(fileId).uniqueId = fileUniqueId
    }

    // ═══════════════════════ 窗口调度 ═══════════════════════

    private fun session(fileId: Int): FileSession =
        sessions.getOrPut(fileId) { FileSession(fileId) }

    /** getFile 快照兜底（每会话一次）：补齐 path/completion/size，避免冷启动竞态。 */

    /**
     * 下载层 TD 调用统一走长超时：downloadFile（尤其 synchronous 模式）会阻塞到
     * 区间真正落盘，默认 30s 会把慢速代理下的正常下载误判成超时并冒泡到 ExoPlayer。
     */
    private suspend fun tdLong(q: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
        td.tdCall(q, 600_000L)

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

    /** 窗口平移（SPEC §4.3 seek 检测）。必须在 session.mutex 内调用。 */
    private fun shiftWindowLocked(s: FileSession, newBase: Long) {
        cancelPending(s.fileId)   // 只取消排队分片，已落盘数据不丢
        s.base = newBase
        s.tailBaseline = s.downloadedSize   // 尾部窗口可读判据的基线
        s.requestedEnd = newBase
        s.readable.value = 0L     // 新窗口从零重新计前缀
        s.awaitingBaseline = true // 丢弃旧引用 update，等新窗口首个响应
        s.failure = null
    }

    /**
     * 补齐窗口 chunk：[aligned, aligned + windowAhead) 中缺失的 1MB 分片逐个发
     * downloadFile(file_id, chunkOffset, 1MB, priority=1)，Semaphore 控并发路数。
     * 必须在 session.mutex 内调用。
     */
    private fun scheduleWindowChunksLocked(s: FileSession, aligned: Long, windowAhead: Long) {
        val targetEnd = aligned + windowAhead
        // limit=0 的请求会从 aligned 连续下到文件末尾，单路即可覆盖整个窗口。
        // 旧实现按 1MB 发 4 路带 limit 的请求，TDLib 每路只下 1MB 且互相抢占，
        // 结果中段长期是空洞（实测播放报 Invalid NAL length 的根因）。
        var issued = false
        if (!s.chunks.containsKey(aligned)) {
            launchChunk(s, aligned)
            issued = true
        }
        s.requestedEnd = max(s.requestedEnd, targetEnd)
        if (issued) s.failure = null   // 有新 chunk 补发 → 清除粘滞失败，重新给机会
    }

    private fun launchChunk(s: FileSession, chunkOffset: Long) {
        val entry = ChunkJob()
        s.chunks[chunkOffset] = entry   // 先占位，防 job 瞬间完成后被重复补发
        val g = gate
        entry.job = scope.launch {
            g.acquire()
            try {
                runChunk(s, chunkOffset)
            } finally {
                g.release()
            }
        }
    }

    /**
     * 单 chunk：downloadFile + 等落盘。失败重试 2 次（1s/3s），FLOOD_WAIT_N ≤ 30s 严格等待；
     * 耗尽 → 置会话失败（所有 waiter 收到 DownloadException，SPEC §4.3 容错）。
     */
    private suspend fun runChunk(s: FileSession, chunkOffset: Long) {
        try {
            var attempt = 0
            while (true) {
                val entry = s.chunks[chunkOffset]
                if (s.failure != null || entry == null || entry.canceled) return
                try {
                    val resp = tdLong(tdObj(
                        "@type" to "downloadFile",
                        "file_id" to s.fileId,
                        "offset" to chunkOffset,
                        // 0 = 不限量：TDLib 会从 offset 连续下到文件末尾。
                        // 传 CHUNK_BYTES 只会下这一小段，后续区间永远是空洞。
                        "limit" to 0,
                        "priority" to PRIORITY_STREAM,
                    ))
                    // 响应即 file 快照；新窗口首个（base chunk）响应建立 prefix 基线
                    applyFileState(s, resp, establishBaseline = (chunkOffset == s.base))
                    waitChunkReadable(s, chunkOffset)
                    return
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (s.failure != null) return
                    if (attempt >= CHUNK_ATTEMPTS - 1) {
                        Log.e(TAG, "chunk ${s.fileId}@$chunkOffset 失败（重试耗尽）", t)
                        s.failure = t
                        return
                    }
                    delay(retryDelayMs(t, attempt))
                    attempt++
                }
            }
        } finally {
            s.chunks.remove(chunkOffset)
        }
    }

    private fun retryDelayMs(t: Throwable, attempt: Int): Long {
        val flood = (t as? TdRequestException)?.message
            ?.substringAfter("FLOOD_WAIT_", "")
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull()
        return if (flood != null && flood in 1..30) flood * 1000L
        else RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.size - 1)]
    }

    /** 等 [chunkOffset, chunkOffset+1MB) 落盘；无进展 CHUNK_STALL_MS 抛出（触发重试）。 */
    private suspend fun waitChunkReadable(s: FileSession, chunkOffset: Long) {
        val chunkEnd = chunkOffset + CHUNK_BYTES
        var lastProgress = -1L
        var lastChangeAt = System.currentTimeMillis()
        while (true) {
            coroutineContext.ensureActive()
            s.failure?.let { throw it }
            if (readyNow(s, s.base, chunkEnd, chunkEnd)) return
            val entry = s.chunks[chunkOffset]
            if (entry == null || entry.canceled) return   // 已被前缀覆盖完成 / 窗口平移
            val cur = s.readable.value
            val now = System.currentTimeMillis()
            if (cur != lastProgress) {
                lastProgress = cur
                lastChangeAt = now
            } else if (now - lastChangeAt > CHUNK_STALL_MS) {
                throw DownloadException("chunk ${s.fileId}@$chunkOffset 无进展超过 ${CHUNK_STALL_MS / 1000}s")
            }
            delay(POLL_MS)
        }
    }

    /**
     * 挂起等待绝对终点 endAbs 可读（updateFile 驱动；每 SNAPSHOT_FALLBACK_MS getFile 快照
     * 兜底防 update 丢失；WAIT_TIMEOUT_MS 超时上抛 DownloadException）。
     */
    /**
     * 同步精确拉取 [offset, offset+length)：TDLib 的 synchronous 模式会真正等待
     * 该区间下载完成再返回，是唯一能可靠拿到"文件尾"这种非顺序区间的途径。
     */
    private suspend fun fetchRangeSync(s: FileSession, offset: Long, length: Long) {
        val limit = length.coerceAtLeast(1L)
        repeat(3) { attempt ->
            val resp = try {
                withTimeoutOrNull(SYNC_FETCH_TIMEOUT_MS) {
                    tdLong(
                        tdObj(
                            "@type" to "downloadFile",
                            "file_id" to s.fileId,
                            "offset" to offset,
                            "limit" to limit,
                            "priority" to PRIORITY_STREAM,
                            "synchronous" to true,
                        )
                    )
                }
            } catch (e: TdRequestException) {
                // 200 = 被另一个 downloadFile 请求取消（并发分片抢占），退避后重试即可
                delay(500L)
                null
            }
            if (resp != null) {
                applyFileState(s, resp, establishBaseline = false)
                return
            }
        }
    }

    /**
     * 校验 [offset] 处磁盘真实字节已落盘（非全 0x00）。
     *
     * TDLib 下载文件时先按完整尺寸预分配（稀疏文件），未下载区域读出来是 0。
     * TDLib 自报的 downloaded_prefix_size / downloaded_size 在并发多窗口下会串台，
     * 因此这里直接读磁盘，只有拿到真实非零字节才算可读 —— MP4 的 ftyp/moov
     * 都是非零数据，用全 0 判据可以准确区分"已落盘"与"空洞"。
     */
    private suspend fun probeNonZero(s: FileSession, offset: Long) {
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        var logged = false
        while (true) {
            val path = s.partialPath.value
            if (path != null) {
                val ok = runCatching {
                    java.io.RandomAccessFile(path, "r").use { raf ->
                        if (raf.length() <= offset) return@use false
                        raf.seek(offset)
                        val buf = ByteArray(PROBE_BYTES)
                        val n = raf.read(buf)
                        n > 0 && buf.any { it.toInt() != 0 }
                    }
                }.getOrDefault(false)
                if (ok) return
            }
            if (!logged) {
                logged = true
            }
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                throw DownloadException("file ${s.fileId} offset=$offset 区域未落盘（${PROBE_TIMEOUT_MS / 1000}s）")
            }
            // 刷新快照并重发该点下载请求，避免空洞永久存在
            runCatching {
                applyFileState(s, tdLong(tdObj("@type" to "getFile", "file_id" to s.fileId)), false)
            }
            runCatching {
                tdLong(
                    tdObj(
                        "@type" to "downloadFile",
                        "file_id" to s.fileId,
                        "offset" to offset,
                        "limit" to 0,
                        "priority" to PRIORITY_STREAM,
                    )
                )
            }
            delay(PROBE_POLL_MS)
        }
    }

    /** 双判据：头部窗口看连续前缀；尾部窗口看相对窗口基线的累计下载量。 */
    private fun readyNow(s: FileSession, base: Long, prefixTarget: Long, endAbs: Long): Boolean {
        if (s.completed.value) return true
        if (s.readable.value >= prefixTarget) return true
        if (base > 0L) {
            val need = endAbs - base
            if (need > 0L && s.downloadedSize - s.tailBaseline >= need) return true
        }
        return false
    }

    private suspend fun awaitReadable(
        s: FileSession,
        base: Long,
        prefixTarget: Long,
        endAbs: Long,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        var nextSnapshotAt = System.currentTimeMillis() + SNAPSHOT_FALLBACK_MS
        while (true) {
            coroutineContext.ensureActive()
            s.failure?.let {
                throw DownloadException("file ${s.fileId} 下载失败: ${it.message}", it)
            }
            if (readyNow(s, base, prefixTarget, endAbs)) return
            val now = System.currentTimeMillis()
            if (now >= nextSnapshotAt) {
                nextSnapshotAt = now + SNAPSHOT_FALLBACK_MS
                try {
                    val resp = tdLong(tdObj("@type" to "getFile", "file_id" to s.fileId))
                    applyFileState(s, resp, establishBaseline = false)
                } catch (t: Throwable) {
                }
                if (readyNow(s, base, prefixTarget, endAbs)) return
            }
            if (now >= deadline) {
                throw DownloadException("file ${s.fileId} 等待分片超时（${WAIT_TIMEOUT_MS / 1000}s）")
            }
            delay(POLL_MS)
        }
    }

    // ═══════════════════════ 状态应用（file 对象 → 会话） ═══════════════════════

    /**
     * 把 file 对象（getFile 快照 / downloadFile 响应 / updateFile）应用到会话。
     * establishBaseline=true 仅用于新窗口首个 downloadFile 响应：此后 update 携带的
     * downloaded_prefix_size 相对值才可信（任务书事实 2）。
     */
    private fun applyFileState(s: FileSession, file: JsonObject, establishBaseline: Boolean) {
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
            s.readable.value = Long.MAX_VALUE / 4   // 整文件可读，全部 waiter 放行
            promoteToLru(s)
            return
        }

        if (establishBaseline && s.awaitingBaseline) {
            s.awaitingBaseline = false
            s.readable.value = (local["downloaded_prefix_size"] as? JsonPrimitive)?.longOrNull ?: 0L
            return
        }
        if (s.awaitingBaseline) return   // 平移后、基线建立前：旧引用 update 一律丢弃

        val prefix = (local["downloaded_prefix_size"] as? JsonPrimitive)?.longOrNull ?: return
        if (prefix <= 0) return
        // 单事件增量钳制：防止基线错位/串台注入虚假大前缀（防呆上限 8MB/事件）
        s.readable.update { cur -> if (prefix > cur) min(prefix, cur + MAX_PREFIX_JUMP) else cur }
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

/**
 * 可热更计数信号量：resize 增发许可立即生效；缩减时对进行中许可不动，
 * 只限制后续获取（设置页滑杆实时生效，SPEC §4.3 Q28）。
 */
internal class ResizableSemaphore(initialPermits: Int) {

    private val sem = object : java.util.concurrent.Semaphore(initialPermits, true) {
        fun shrink(k: Int) = reducePermits(k)
    }

    @Volatile private var target = initialPermits

    @Synchronized
    fun resize(n: Int) {
        when {
            n > target -> sem.release(n - target)
            n < target -> sem.shrink(target - n)
        }
        target = n
    }

    /** 协程友好的阻塞获取：轮询 tryAcquire，取消即时响应。 */
    suspend fun acquire() {
        while (true) {
            coroutineContext.ensureActive()
            val ok = try {
                sem.tryAcquire(25L, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (ok) return
        }
    }

    fun release() = sem.release()
}
