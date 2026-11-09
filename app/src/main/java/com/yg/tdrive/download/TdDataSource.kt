package com.yg.tgdrive.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking

/**
 * Media3 DataSource（SPEC §4.4）：把 ExoPlayer 的 open/read/close 翻译到
 * [StreamingDownloadManager] 的并发窗口下载层。
 *
 * 标记 URI（任务书 C.1）：`tdfile://td/<fileId>/<size>?uid=<fileUniqueId>`
 * - path 段 0 = fileId（Int），段 1 = size（文件总字节；DataSpec.length 未给出时作读取终点）
 * - query `uid` = media.fileUniqueId（LRU 缓存 key 通道，可空）
 *
 * open  → cancelPending（旧窗口在飞分片止血）+ ensureRange 预热窗口（内部完成平移）+ transferStarted
 * read  → ensureRange 挂起等落盘 → RandomAccessFile(partialPath).seek(pos - globalOffset) 读
 *         → bytesTransferred；EOF 返回 C.RESULT_END_OF_INPUT
 * close → 释放句柄 + transferEnded（窗口释放由播放页 onDestroy 的 cancelAll 收口）
 */
class TdDataSource(
    private val mgr: StreamingDownloadManager,
) : BaseDataSource(false) {

    companion object {
        const val SCHEME = "tdfile"
        private const val TAG = "TdDataSource"

        /** read 缓冲上限（Media3 单次 read 请求通常 ≤ 64KB）。 */
        private const val MAX_READ = 256 * 1024

        /** ensureRange 预热长度（open 起播 / read 跨窗口时先保证 2MB 数据）。 */
        private const val PREHEAT = 2L * 1024 * 1024

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

    /** 最近一次 ensureRange 的引用（read 复用窗口判断用）。 */
    private var ref: PartialRef? = null

    /** 当前句柄与其对应 ref。 */
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null
    private var rafGlobalOffset: Long = -1L

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

        // seek 入口（任务书 B / SPEC §4.4）：先取消旧窗口排队分片（已落盘不丢），
        // 窗口随后由 ensureRange 内部平移到新 aligned。
        mgr.cancelPending(fileId)
        transferStarted(dataSpec)

        // 越界 open（position ≥ 文件大小）：立即 EOF，不发起下载
        if (totalSize > 0 && position >= totalSize) {
            opened = true
            return C.LENGTH_UNSET.toLong()
        }

        // open 预热：挂起等待起播头部落盘（失败即抛 → ExoPlayer 错误覆盖层）
        ensureRangeBlocking(position, PREHEAT)
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
        if (position >= endPosition) return C.RESULT_END_OF_INPUT

        var bufOffset = offset
        var remaining = minOf(length.toLong(), endPosition - position).toInt()
        val want = remaining
        var totalRead = 0

        while (remaining > 0) {
            // 当前位置不在已覆盖区间 → ensureRange 挂起等落盘（read 的挂起点）
            val r = currentRefFor(position, remaining.toLong())
            val handle = openHandle(r)
            handle.seek(position - r.globalOffset)

            // 单次读上限：句柄覆盖的文件尾 / 本次请求终点 / 请求长度
            val fileLen = File(r.partialPath).length()
            val readableToFileEnd = r.globalOffset + fileLen
            val toRead = minOf(
                remaining.toLong(),
                readableToFileEnd - position,
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
                    throw EOFException("partial 提前 EOF file=$fileId pos=$position")
                }
                break
            }
            position += n
            bufOffset += n
            remaining -= n
            totalRead += n
        }

        if (totalRead > 0) bytesTransferred(totalRead)
        return if (totalRead == 0 && want > 0) C.RESULT_END_OF_INPUT else totalRead
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
        rafGlobalOffset = -1L
        ref = null
        uri = null
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /** ensureRange 挂起包装：加载线程被取消（close/释放）→ 转 IOException。 */
    private fun ensureRangeBlocking(pos: Long, len: Long) {
        try {
            runBlocking { mgr.ensureRange(fileId, pos, len) }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("读取被取消 file=$fileId", ie)
        }
    }

    /**
     * 取当前位置可读的 PartialRef：
     * 1. LRU 命中 / 整文件完成 → ensureRange 返回 globalOffset=0 的全覆盖引用；
     * 2. partial 窗口 → 若当前位置已在本窗口覆盖内直接复用，否则 ensureRange
     *    （挂起等落盘，内部完成窗口平移）。
     */
    private fun currentRefFor(pos: Long, len: Long): PartialRef {
        val cur = ref
        if (cur != null && covers(cur, pos)) return cur
        return try {
            runBlocking { mgr.ensureRange(fileId, pos, maxOf(len, PREHEAT)) }.also { ref = it }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("读取被取消 file=$fileId", ie)
        }
    }

    /** ref 是否已覆盖 pos（覆盖 = pos 落在 [globalOffset, globalOffset+文件实际长度)）。 */
    private fun covers(r: PartialRef, pos: Long): Boolean {
        val fileLen = if (rafPath == r.partialPath) {
            runCatching { File(r.partialPath).length() }.getOrDefault(0L)
        } else {
            0L
        }
        if (fileLen <= 0) return false
        return pos >= r.globalOffset && pos < r.globalOffset + fileLen
    }

    private fun openHandle(r: PartialRef): RandomAccessFile {
        val existing = raf
        if (existing != null && rafPath == r.partialPath) return existing
        runCatching { existing?.close() }
        val f = RandomAccessFile(r.partialPath, "r")
        raf = f
        rafPath = r.partialPath
        rafGlobalOffset = r.globalOffset
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
