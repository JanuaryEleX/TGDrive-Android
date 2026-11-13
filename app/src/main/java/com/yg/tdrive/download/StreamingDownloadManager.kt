package com.yg.tgdrive.download

import android.os.SystemClock
import android.util.Log
import com.yg.tgdrive.data.SeekRelocationPolicy
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
         * 已确认落盘的**连续区间账本**（升序、互不重叠、[start, end) 闭开区间）。
         *
         * 旧实现是一个单调递增的「最高偏移水位」，语义上只对从 0 起的连续前缀成立；
         * 一旦在文件尾探针成功（moov-at-tail 的正常路径），水位被抬到尾部，
         * 于是 0..尾部之间**根本没落盘的空洞**也全部被判为可读，读出去全是 TDLib
         * 预分配的全零字节 —— Mp4Extractor 把零当 box header 解析，最终报
         * "Loading finished before preparation is complete"。这是大视频必挂的根因。
         *
         * 现在只认「真的连续」的区间：落在区间内才算已落盘，空洞一律返回 0。
         */
        private val ranges = ArrayList<LongArray>()
        private val rangesLock = Any()

        /** 标记 [from, to) 已落盘（合并到账本）。 */
        fun markReadable(from: Long, to: Long) {
            if (to <= from) return
            synchronized(rangesLock) {
                var s = from
                var e = to
                val out = ArrayList<LongArray>(ranges.size + 1)
                var inserted = false
                for (r in ranges) {
                    when {
                        r[1] < s -> out.add(r)                      // 完全在前
                        r[0] > e -> {                               // 完全在后
                            if (!inserted) { out.add(longArrayOf(s, e)); inserted = true }
                            out.add(r)
                        }
                        else -> { s = minOf(s, r[0]); e = maxOf(e, r[1]) }  // 相邻/重叠 → 合并
                    }
                }
                if (!inserted) out.add(longArrayOf(s, e))
                ranges.clear()
                ranges.addAll(out)
            }
        }

        /** 清空账本（陈旧状态重置后，磁盘真值待重新确认）。 */
        fun clearReadable() = synchronized(rangesLock) { ranges.clear() }

        /** 从 [pos] 起**连续**已落盘的字节数（落在空洞里 = 0）。 */
        fun readableFrom(pos: Long): Long = synchronized(rangesLock) {
            for (r in ranges) {
                if (pos >= r[0] && pos < r[1]) return r[1] - pos
                if (r[0] > pos) return 0L
            }
            0L
        }

        /** 账本里最高已确认偏移（只用于诊断/前视判断，不代表中间连续）。 */
        val confirmedEnd: Long
            get() = synchronized(rangesLock) { ranges.lastOrNull()?.get(1) ?: 0L }

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

        /** 正在做「TDLib 自报状态 x 磁盘真值」修正，防止递归重入。 */
        @Volatile var resetting = false
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
        // TDLib 自报「已完成/有前缀」而磁盘没数据时，先清掉陈旧记录，避免把零字节交给解析器
        verifyLocalState(s)

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
                val needLen = spanLen(offset, need, expected)
                // 单请求：只有"游标真需要移动"时才发新请求。
                // 顺序播放时位置单调前进且落在已请求区间之后 → 让在飞的那条 limit=0 请求
                // 继续从 base 顺序下到文件尾，**一次都不重发**（重发会让 TDLib 反复重置游标、
                // 掉速，是旧版「几秒后有速度然后归零」的成因之一）。
                // 已落盘区间（账本连续覆盖）不再发起请求：否则往回拖到已缓存位置也会
                // 白取消一次在飞下载，造成无谓的重置与掉速。
                if (s.readableFrom(offset) < needLen && needsNewRequest(s, aligned)) {
                    sendDownload(s, aligned, tag)
                }
                awaitDataLocked(s, offset, needLen, tag)
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
        // partial 文件的坐标原点判定：
        // - 文件长度 > 请求起点 → TDLib 是「按绝对偏移写」的同一个临时文件，字节 0 = 文件起点；
        // - 否则内容从 base 顺序长出来，字节 0 = base。
        // （旧判断只在「文件长度已达完整尺寸」时才用绝对坐标，跳读过的文件会被误判成相对坐标，
        //   进而 seek 到错误位置读到空洞 → 同样表现为全零数据。）
        val globalOffset = if (s.base > 0L && fileLen > s.base) 0L else s.base.coerceAtLeast(0L)
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
     * 设置页「已缓存」：LRU 池当前占用字节。
     * 排除 `.at` 访问标记与 `.tmp` 拷贝临时文件，只统计真正可复用的整文件缓存。
     */
    suspend fun cachedBytes(): Long = withContext(Dispatchers.IO) {
        val entries = lruDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name to it.length() }
            ?: emptyList()
        CacheUsage.totalBytes(entries)
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
        // 自报完成但文件其实没了 → 清掉陈旧记录，交给流播重新下载
        verifyLocalState(s)
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

    /** TDLib 本地文件磁盘真值长度；不存在 / 非普通文件返回 -1。 */
    private fun diskLength(path: String?): Long {
        if (path.isNullOrBlank()) return -1L
        val f = File(path)
        return if (f.isFile) f.length() else -1L
    }

    /**
     * TDLib DB 的本地状态可能与磁盘不符：换机恢复数据、清过缓存、partial 被删之后，
     * `is_downloading_completed=true` / `downloaded_prefix_size>0` 仍然留着。
     * 此时若按自报状态放行，[TdDataSource] 会把**整片零字节**交给 Mp4Extractor →
     * 解析出垃圾 box 尺寸（实测 seek 到 819MB，远超文件大小）→ 永远 prepare 不出来。
     *
     * 因此每次进入 ensureRange 先做一次交叉校验；发现不符就 deleteFile 让 TDLib
     * 清掉本地记录并重新下载（云端只读、无副作用）。
     */
    private suspend fun verifyLocalState(s: FileSession) {
        if (s.resetting) return
        val path = s.partialPath.value
        val disk = diskLength(path)
        val expected = s.expectedSize
        val completed = s.completed.value
        val prefix = s.readable.value
        if (disk < 0L) {
            if (completed || prefix > 0L) {
                resetStaleLocal(s, "文件缺失（completed=$completed prefix=$prefix path=$path）", disk)
            }
            return
        }
        if (disk == 0L && (completed || prefix > 0L)) {
            resetStaleLocal(s, "空文件（completed=$completed prefix=$prefix path=$path）", disk)
            return
        }
        if (completed && expected > 0L && disk < expected) {
            resetStaleLocal(s, "标记完成但落盘不全（disk=$disk expected=$expected）", disk)
            return
        }
        if (PlaybackDiag.isRecording) {
            PlaybackDiag.fileState(
                fileId = s.fileId,
                completed = completed,
                prefix = prefix,
                base = s.base,
                confirmedEnd = s.confirmedEnd,
                expectedSize = expected,
                path = path,
                diskLen = disk,
                note = "",
            )
        }
    }

    /** 陈旧本地状态 → 删掉 TDLib 本地记录并清空会话，让它从云端重新下载。 */
    private suspend fun resetStaleLocal(s: FileSession, why: String, disk: Long) {
        s.resetting = true
        try {
            PlaybackDiag.fileState(
                fileId = s.fileId,
                completed = s.completed.value,
                prefix = s.readable.value,
                base = s.base,
                confirmedEnd = s.confirmedEnd,
                expectedSize = s.expectedSize,
                path = s.partialPath.value,
                diskLen = disk,
                note = "STALE: $why",
            )
            Log.w(TAG, "file ${s.fileId} 本地状态与磁盘不符 → deleteFile 重下：$why")
            runCatching { tdLong(tdObj("@type" to "deleteFile", "file_id" to s.fileId)) }
                .onFailure { Log.w(TAG, "deleteFile ${s.fileId} failed: ${it.message}") }
            s.completed.value = false
            s.readable.value = 0L
            s.clearReadable()
            s.downloadedSize = 0L
            s.base = -1L
            s.partialPath.value = null
            s.expectedSize = 0L
            s.snapshotted = false
            s.awaitingBaseline = true
            s.failure = null
            PlaybackDiag.event("stale-local", "file ${s.fileId} $why")
        } finally {
            s.resetting = false
        }
    }

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
        // 跳跃 seek：必须先把在飞的 limit=0 顺序下载硬取消掉，否则两条请求会互相
        // 取消（TDLib 同一 file 只允许一条），表现为 td error 200 + 永远等不到数据。
        // 注意只取消请求、不置 abandoned——本会话还要继续用。
        if (s.base >= 0L && offset != s.base) {
            runCatching {
                tdLong(tdObj(
                    "@type" to "cancelDownloadFile",
                    "file_id" to s.fileId,
                    "only_pending" to false,
                ))
            }.onFailure { Log.w(TAG, "cancelDownloadFile before seek ${s.fileId} failed: ${it.message}") }
            PlaybackDiag.relocate(s.base, offset)
        }
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
    /**
     * 是否需要为 [aligned] 发一条新的 downloadFile。
     *
     * 旧实现是 `aligned < s.base`——只有往回拖才重发。这混淆了两种完全不同的场景：
     * - **连续播放**：位置单调前进，落在 TDLib 顺序下载的推进方向内 → 不该重发
     *   （重发会让 TDLib 反复重置游标、掉速，是"几秒后归零"的成因之一）。
     * - **跳跃 seek**：位置跳到 TDLib 还没下到的地方 → **必须重定位**，
     *   否则 awaitDataLocked 会一直轮询到超时（真机实测：拖到 336MB 等 20s 后取消）。
     *
     * 判据用"落盘水位"而不是"请求起点"：水位 = 已确认可读的最高偏移。
     * 只要目标在 [水位, 水位+前视窗口] 内，说明顺序下载正朝它推进，等一会儿就有；
     * 超出这个窗口（无论向前向后）都算跳跃，需要重定位。
     */
    private fun needsNewRequest(s: FileSession, aligned: Long): Boolean {
        if (s.failure != null) return true        // 上一条失败（被取消/网络错）→ 重发
        return SeekRelocationPolicy.needsRelocation(
            base = s.base,
            prefix = s.readable.value,
            target = aligned,
        )
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
                // 需求整段落在某个「连续已确认区间」内 → 快路径放行。
                // 注意必须用 readableFrom（连续语义），不能用最高水位：尾部探针成功
                // 不代表中间已落盘，否则会把全零空洞当数据交出去。
                if (s.readableFrom(offset) >= need) {
                    PlaybackDiag.probe(offset, polls, lastNonZero, SystemClock.elapsedRealtime() - t0, "confirmed")
                    return
                }
                val path = s.partialPath.value
                if (path != null) {
                    val n = nonZeroIn(path, offset)
                    if (n > 0) {
                        lastNonZero = n
                        // 只登记"这一段真的可读"，绝不外扩到其他偏移
                        s.markReadable(offset, offset + PROBE_BYTES)
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
        return s.readableFrom(pos)
    }

    /** 读 [pos] 起的 [PROBE_BYTES] 字节，返回非零字节数（0 = 空洞或不可读）。 */
    private fun nonZeroIn(path: String, pos: Long): Int = runCatching {
        RandomAccessFile(path, "r").use { raf ->
            if (raf.length() <= pos) return@use 0
            raf.seek(pos)
            val buf = ByteArray(PROBE_BYTES)
            val n = raf.read(buf)
            if (n <= 0) {
                0
            } else {
                var nonZero = 0
                var allFF = true
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (b != 0) nonZero++
                    if (b != 0xFF) allFF = false
                }
                // 真机实测（temp/2627）：TDLib 某些未落盘区域被预填成 0xFF 而不是 0。
                // 「有非零字节 = 已落盘」的判据会被这种填充骗过，把填充当数据交出去。
                // 整窗全 0xFF 一律按空洞处理。
                if (allFF) 0 else nonZero
            }
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
            s.markReadable(0L, Long.MAX_VALUE / 4)
            s.readable.value = Long.MAX_VALUE / 4   // 整文件可读，全部 waiter 放行
            promoteToLru(s)
            return
        }

        if (establishBaseline) {
            s.awaitingBaseline = false
            val p0 = (local["downloaded_prefix_size"] as? JsonPrimitive)?.longOrNull ?: 0L
            s.readable.value = p0
            if (s.base >= 0L && p0 > 0L) s.markReadable(s.base, s.base + p0)
            return
        }
        if (s.awaitingBaseline) return   // 新请求基线建立前：旧引用 update 一律丢弃

        val prefix = (local["downloaded_prefix_size"] as? JsonPrimitive)?.longOrNull ?: return
        if (prefix <= 0) return
        // 单事件增量钳制：防止基线错位/串台注入虚假大前缀（防呆上限 8MB/事件）
        s.readable.update { cur -> if (prefix > cur) min(prefix, cur + MAX_PREFIX_JUMP) else cur }
        // 单请求模型下前缀不再有多窗口歧义：同步推进磁盘真值水位（零 IO 快路径）
        if (s.base >= 0L) s.markReadable(s.base, s.base + s.readable.value)
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
