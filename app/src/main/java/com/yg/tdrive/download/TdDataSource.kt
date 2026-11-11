package com.yg.tgdrive.download

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Media3 DataSource（SPEC §4.4）：把 ExoPlayer 的 open/read/close 翻译到
 * [StreamingDownloadManager] 的单请求顺序下载层。
 *
 * 标记 URI（任务书 C.1）：`tdfile://td/<fileId>/<size>?uid=<fileUniqueId>`
 * - path 段 0 = fileId（Int），段 1 = size（文件总字节；DataSpec.length 未给出时作读取终点）
 * - query `uid` = media.fileUniqueId（LRU 缓存 key 通道，可空）
 *
 * open  → ensureRange 预热窗口（位置变化时发唯一一条 downloadFile）+ transferStarted
 * read  → ensureRange 挂起等该偏移真实落盘 → RandomAccessFile 按 (pos - globalOffset) 读
 *         → bytesTransferred；EOF 返回 C.RESULT_END_OF_INPUT
 * close → 释放句柄 + transferEnded（窗口释放由播放页 onDestroy 的 cancelAll 收口）
 *
 * 注意：每次 read 都走 ensureRange —— TDLib partial 是预分配的稀疏文件，文件长度等于总大小，
 * 「句柄覆盖 pos」并不等于「pos 已有真实字节」，跳过 ensureRange 会读到全零。
 */
class TdDataSource(
    private val mgr: StreamingDownloadManager,
) : BaseDataSource(false) {

    companion object {
        const val SCHEME = "tdfile"
        private const val TAG = "TdDataSource"

        /** ensureRange 预热长度（open 起播 / read 跨窗口时先保证数据）。 */
        private const val PREHEAT = 512L * 1024

        /**
         * open() 预热的等待上限。必须显著小于 ExoPlayer 对 load 阻塞的容忍度
         * （否则它会 cancelLoading → 中断线程 → 报错 → 重试丢位置）。
         * 预热只是"把请求发出去"，真正的等待交给 read()。
         */
        private const val OPEN_WARMUP_MS = 3_000L

        /**
         * read() 单次等待上限。要小于 ExoPlayer 判定 load 卡死的阈值，
         * 超时即抛可重试异常，交给播放器自己重试（数据仍在后台下载）。
         */
        private const val READ_WAIT_MS = 8_000L

        /** 解析标记 URI → (fileId, size, uid)。非本 scheme 抛 IOException。 */
        fun parseUri(uri: Uri): Triple<Int, Long, String?> {
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) {
                throw IOException("TdDataSource 仅支持 $SCHEME:// URI: $uri")
            }
            val segments = uri.pathSegments
            val fileId = segments.getOrNull(0)?.toIntOrNull()
                ?: throw IOException("URI 缺少 fileId: $uri")
            val size = segments.getOrNull(1)?.toLongOrNull() ?: 0L
            val uid = uri.getQueryParameter("uid")
            return Triple(fileId, size, uid)
        }

        /** 构造标记 URI（PlayerActivity 组 MediaItem 用）。 */
        fun buildUri(fileId: Int, size: Long, fileUniqueId: String?): Uri =
            Uri.Builder()
                .scheme(SCHEME)
                .authority("td")
                .appendPath(fileId.toString())
                .appendPath(size.toString())
                .apply {
                    fileUniqueId?.takeIf { it.isNotBlank() }
                        ?.let { appendQueryParameter("uid", it) }
                }
                .build()
    }

    private var fileId: Int = 0
    private var totalSize: Long = 0L
    private var uid: String? = null

    /** 当前 open 的 URI（getUri 契约：open 后返回，close 后 null）。 */
    private var uri: Uri? = null

    /** 当前读取绝对位置（文件内偏移）。 */
    private var position: Long = 0L

    /** 本次 open 的读取终点（绝对偏移）。 */
    private var endPosition: Long = 0L

    /** 当前句柄与其对应的 partial 路径。 */
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    private var opened = false

    @Volatile private var closed = false

    // ═══════════════════════ DataSource 契约（SPEC §4.4） ═══════════════════════

    override fun getUri(): Uri? = uri

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        closed = false
        uri = dataSpec.uri
        val (fid, size, u) = parseUri(dataSpec.uri)
        fileId = fid
        totalSize = size
        uid = u
        uid?.let { mgr.attachUniqueId(fileId, it) }

        position = dataSpec.position
        endPosition = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.position + dataSpec.length
        } else if (totalSize > 0) {
            totalSize
        } else {
            Long.MAX_VALUE
        }
        if (totalSize in 1 until endPosition) endPosition = totalSize

        transferStarted(dataSpec)

        // 越界 open（position ≥ 文件大小）：立即 EOF，不发起下载
        if (totalSize > 0 && position >= totalSize) {
            opened = true
            return C.LENGTH_UNSET.toLong()
        }

        // open 预热：只为把下载请求发出去 + 短暂等首包。
        // 这里**不能**长等：ExoPlayer 的 load 线程对 open/read 的阻塞有容忍上限，
        // 一旦超时它会 cancelLoading() → 中断线程 → 抛错 → 自动重试 → 位置丢失。
        // 所以预热只等一小会儿，没等到就让 read() 去逐段等（read 可短等多次）。
        ensureRangeBlocking(position, PREHEAT, OPEN_WARMUP_MS)
        opened = true

        // 返回值 = 本次可读长度（DataSpec 未定长且总长已知时报告剩余，利于 MP4 定长 box 解析）
        return when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalSize > 0 -> totalSize - position
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(opened) { "read before open" }
        if (length == 0) return 0
        if (position >= endPosition) {
            return C.RESULT_END_OF_INPUT
        }

        var bufOffset = offset
        var remaining = minOf(length.toLong(), endPosition - position).toInt()
        val want = remaining
        var totalRead = 0

        while (remaining > 0) {
            // 当前位置 → 等真实落盘（read 的挂起点，位置变化时内部发唯一一条 downloadFile）
            val r = ensureRangeRef(position, maxOf(remaining.toLong(), PREHEAT))
            val handle = openHandle(r)
            val fileLen = File(r.partialPath).length()
            handle.seek(position - r.globalOffset)

            // TDLib partial 是预分配的稀疏文件：文件长度 = 总大小，未落盘区域读出来是全零。
            // 因此单次读取必须钳制到「已确认落盘」的连续区间，否则会把空洞当数据交给解析器。
            val safe = mgr.readableBytesFrom(fileId, position)
            val toRead = minOf(
                remaining.toLong(),
                safe.coerceAtLeast(1L),
                r.globalOffset + fileLen - position,
                endPosition - position,
            ).coerceAtLeast(1L).toInt()

            val n = try {
                handle.read(buffer, bufOffset, toRead)
            } catch (e: IOException) {
                throw IOException("partial 读失败 file=$fileId pos=$position: ${e.message}", e)
            }
            if (n <= 0) {
                if (totalRead == 0) {
                    if (position >= endPosition || (totalSize > 0 && position >= totalSize)) {
                        return C.RESULT_END_OF_INPUT
                    }
                    throw IOException("partial 提前 EOF file=$fileId pos=$position")
                }
                break
            }
            position += n
            bufOffset += n
            remaining -= n
            totalRead += n
        }

        if (totalRead == 0 && want > 0) {
            return C.RESULT_END_OF_INPUT
        }
        if (totalRead > 0) bytesTransferred(totalRead)
        if (position >= endPosition) Log.w(TAG, "read reach-end file=$fileId pos=$position")
        return totalRead
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        if (opened) {
            opened = false
            transferEnded()
        }
        runCatching { raf?.close() }
        raf = null
        rafPath = null
        uri = null
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /** ensureRange 挂起包装：加载线程被取消（close/释放）→ 转 IOException。 */
    private fun ensureRangeBlocking(pos: Long, len: Long, maxWaitMs: Long = Long.MAX_VALUE) {
        try {
            runBlocking {
                if (maxWaitMs == Long.MAX_VALUE) {
                    mgr.ensureRange(fileId, pos, len, tag = "stream")
                } else {
                    withTimeoutOrNull(maxWaitMs) {
                        mgr.ensureRange(fileId, pos, len, tag = "stream")
                    }
                }
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("读取被取消 file=$fileId", ie)
        }
    }

    /**
     * ensureRange 的引用版本（read 内每个位置都要等真实落盘）。
     *
     * 单次等待上限 [READ_WAIT_MS]：ExoPlayer 的 load 线程被阻塞过久会被 cancelLoading()
     * 打断（线程中断 → runBlocking 抛 CancellationException → 播放中断 → 重试丢位置）。
     * 超时后抛 [RetryLaterException]，让 ExoPlayer 走正常的"重试同一 load"路径，
     * 而不是整条播放链路崩掉。数据仍在后台下载，下次 read 就能拿到。
     */
    private fun ensureRangeRef(pos: Long, len: Long): PartialRef = try {
        val ref = runBlocking {
            withTimeoutOrNull(READ_WAIT_MS) {
                mgr.ensureRange(fileId, pos, len, tag = "stream")
            }
        }
        ref ?: throw RetryLaterException("file=$fileId pos=$pos 数据尚未就绪（${READ_WAIT_MS}ms）")
    } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("读取被取消 file=$fileId", ie)
    }

    private fun openHandle(r: PartialRef): RandomAccessFile {
        val existing = raf
        if (existing != null && rafPath == r.partialPath) return existing
        runCatching { existing?.close() }
        val f = RandomAccessFile(r.partialPath, "r")
        raf = f
        rafPath = r.partialPath
        return f
    }
}

/**
 * TdDataSource 工厂（SPEC §4.4 组装：ProgressiveMediaSource.Factory(TdDataSourceFactory)）。
 */
class TdDataSourceFactory(
    private val mgr: StreamingDownloadManager,
) : DataSource.Factory {
    override fun createDataSource(): TdDataSource = TdDataSource(mgr)
}

/**
 * "数据还没到，但这是暂时的" —— 抛给 ExoPlayer，让它重试本次 load 而不是终止播放。
 *
 * 必须继承 [java.io.IOException]：ExoPlayer 把非 IOException 当作致命错误。
 * 配合默认 LoadErrorHandlingPolicy 的重试即可自动恢复，播放位置也不会丢。
 */
class RetryLaterException(message: String) : java.io.IOException(message)
