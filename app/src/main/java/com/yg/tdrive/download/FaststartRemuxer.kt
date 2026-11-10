package com.yg.tgdrive.download

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 faststart 虚拟重排。
 *
 * Telegram 里大量 MP4 的 moov 位于文件尾（mdt 在前）。ExoPlayer 的 Mp4Extractor 会
 * seek 到文件尾去读 moov，而它把"本次 load 的起点"当作文件原点，导致 moov 里的
 * 绝对采样偏移被二次叠加（实测 3966111032 + 3943661055 = 7909772095 > 文件长度），
 * 最终 load 越界 EOF，报 "Loading finished before preparation is complete"。
 *
 * 这里不改写整个文件（3.7GB 不可能整体下载），而是构造一个"虚拟文件"：
 *
 *   [ftyp] [moov(已按 +moovSize 重写 stco/co64)] [mdat ... 原始数据顺移 moovSize]
 *
 * 于是 moov 出现在文件前部，extractor 不再 seek 到尾部，所有偏移都是文件绝对坐标。
 * 虚拟长度 = 原长度 + moovSize，读取时按映射回落到原始偏移。
 */
object FaststartRemuxer {

    private const val TAG = "Faststart"

    /** 一次拉取用于定位 moov 的尾部窗口上限。 */
    private const val TAIL_SCAN_BYTES = 8L * 1024 * 1024

    data class Plan(
        val fileId: Int,
        /** 原始文件大小。 */
        val size: Long,
        /** 前置区长度（ftyp 等，虚拟位置与原位置一致）。 */
        val prefixLen: Long,
        /** 已重写偏移的 moov 原始字节。 */
        val moov: ByteArray,
        /** moov 在原始文件中的起点。 */
        val moovStart: Long,
    ) {
        val moovLen: Long get() = moov.size.toLong()

        /** 虚拟文件总长。 */
        val virtualSize: Long get() = size + moovLen

        /** 虚拟布局： [0,prefixLen) 原始 | [prefixLen, prefixLen+moovLen) moov | 之后为 mdat 顺移。 */
        val moovVirtualStart: Long get() = prefixLen
        val moovVirtualEnd: Long get() = prefixLen + moovLen

        /** 虚拟偏移 → 原始偏移（仅对非 moov 区有效）。 */
        fun toReal(v: Long): Long =
            if (v < prefixLen) v else v - moovLen
    }

    private class Box(val start: Long, val size: Long, val type: String)

    /**
     * 构建重排计划。失败返回 null（调用方退回直连播放）。
     */
    suspend fun build(mgr: StreamingDownloadManager, fileId: Int, size: Long): Plan? {
        if (size <= 0L) { Log.w(TAG, "size<=0，跳过"); return null }
        return runCatching { buildInner(mgr, fileId, size) }
            .onFailure { Log.w(TAG, "构建失败 file=$fileId: ${it.message}") }
            .getOrNull()
    }

    private suspend fun buildInner(mgr: StreamingDownloadManager, fileId: Int, size: Long): Plan? {
        val prefix = readReal(mgr, fileId, 0L, 64L)
        if (prefix.size < 16) { Log.w(TAG, "prefix 太短"); return null }
        val prefixLen = topLevelEnd(prefix, 0L, size) ?: run { Log.w(TAG, "topLevelEnd 解析失败"); return null }
        if (prefixLen <= 0L || prefixLen >= size) { Log.w(TAG, "prefixLen 非法"); return null }

        val tailLen = minOf(size, TAIL_SCAN_BYTES)
        val tailStart = size - tailLen
        val tail = readReal(mgr, fileId, tailStart, tailLen)
        if (tail.size < 16) { Log.w(TAG, "tail 太短"); return null }

        val moovBox = findTrailingBox(tail, tailStart, size)
        if (moovBox == null) { Log.w(TAG, "未找到以文件尾结束的顶层 box"); return null }
        if (moovBox.type != "moov") { Log.w(TAG, "尾部 box 不是 moov"); return null }

        val moovBytes = readReal(mgr, fileId, moovBox.start, moovBox.size)
        if (moovBytes.size.toLong() != moovBox.size) return null

        // moov 从文件尾挪到 prefixLen 处，mdat 整体后移 moovLen：所有 chunk 偏移 +moovLen
        patchChunkOffsets(moovBytes, 0, moovBytes.size, moovBox.size)

        return Plan(fileId, size, prefixLen, moovBytes, moovBox.start)
    }

    /** 从缓冲的尾部数据里找出"正好以文件尾结束"的顶层 box。 */
    private fun findTrailingBox(buf: ByteArray, bufStart: Long, fileSize: Long): Box? {
        // 直接搜索 "moov"/"moof" 四字节签名（比逐字节扫描快几个数量级），
        // 再校验其 size 字段恰好以文件尾结束。
        val want = arrayOf("moov", "moof")
        for (k in buf.size - 8 downTo 0) {
            if (buf[k] != 'm'.code.toByte()) continue
            val t = String(buf, k, 4, Charsets.ISO_8859_1)
            if (t != want[0] && t != want[1]) continue
            val headerPos = k - 4
            if (headerPos < 0) continue
            var s = beInt(buf, headerPos).toLong()
            if (s == 1L) {
                if (headerPos + 16 > buf.size) continue
                s = beLong(buf, headerPos + 8)
            }
            if (s < 8) continue
            val absStart = bufStart + headerPos
            if (absStart + s == fileSize) return Box(absStart, s, t)
        }
        return null
    }

    private fun readBoxAt(buf: ByteArray, i: Int, absoluteStart: Long, fileSize: Long): Box? {
        if (i + 8 > buf.size) return null
        var s = beInt(buf, i).toLong()
        val type = String(buf, i + 4, 4, Charsets.ISO_8859_1)
        if (type.any { it.code < 0x20 || it.code > 0x7e }) return null
        var header = 8L
        if (s == 1L) {
            if (i + 16 > buf.size) return null
            s = beLong(buf, i + 8)
            header = 16L
        }
        if (s < header) return null
        return Box(absoluteStart, s, type)
    }

    /** 第一个顶层 box 的结束位置。 */
    private fun topLevelEnd(buf: ByteArray, pos: Long, fileSize: Long): Long? {
        var s = beInt(buf, 0).toLong()
        val type = String(buf, 4, 4, Charsets.ISO_8859_1)
        var header = 8L
        if (s == 1L) {
            if (buf.size < 16) return null
            s = beLong(buf, 8)
            header = 16L
        }
        if (s < header || (type != "ftyp" && type != "free" && type != "skip")) return null
        return s
    }

    // ── stco / co64 偏移重写 ─────────────────────────────────────────────

    private fun patchChunkOffsets(buf: ByteArray, start: Int, end: Int, delta: Long) {
        var p = start
        while (p + 8 <= end) {
            var size = beInt(buf, p).toLong()
            val type = String(buf, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (p + 16 > end) return
                size = beLong(buf, p + 8)
                header = 16
            }
            if (size < header || p + size > end) return
            when (type) {
                "stco" -> patchStco(buf, p + header, p + size.toInt(), 0, delta)
                "co64" -> patchStco(buf, p + header, p + size.toInt(), 0, delta)
                else -> patchChunkOffsets(buf, p + header, p + size.toInt(), delta)
            }
            p += size.toInt()
        }
    }

    /** stco: version/flags(4) + entryCount(4) + 32bit 偏移；co64: 64bit 偏移。 */
    private fun patchStco(buf: ByteArray, body: Int, end: Int, unused: Int, delta: Long) {
        if (body + 8 > end) return
        val count = beInt(buf, body + 4)
        var p = body + 8
        // 32/64 位由父级类型决定：靠条目数×宽度校验来推断
        val wide = (end - p) / 8 == count && (end - p) % 8 == 0
        repeat(count) {
            if (wide) {
                if (p + 8 > end) return
                val v = beLong(buf, p) + delta
                putLong(buf, p, v)
                p += 8
            } else {
                if (p + 4 > end) return
                val v = beInt(buf, p).toLong() + delta
                putInt(buf, p, v.toInt())
                p += 4
            }
        }
    }

    private fun beInt(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)

    private fun beLong(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 56) or ((b[i + 1].toLong() and 0xff) shl 48) or
            ((b[i + 2].toLong() and 0xff) shl 40) or ((b[i + 3].toLong() and 0xff) shl 32) or
            ((b[i + 4].toLong() and 0xff) shl 24) or ((b[i + 5].toLong() and 0xff) shl 16) or
            ((b[i + 6].toLong() and 0xff) shl 8) or (b[i + 7].toLong() and 0xff)

    private fun putInt(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte()
        b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte()
        b[i + 3] = v.toByte()
    }

    private fun putLong(b: ByteArray, i: Int, v: Long) {
        b[i] = (v ushr 56).toByte()
        b[i + 1] = (v ushr 48).toByte()
        b[i + 2] = (v ushr 40).toByte()
        b[i + 3] = (v ushr 32).toByte()
        b[i + 4] = (v ushr 24).toByte()
        b[i + 5] = (v ushr 16).toByte()
        b[i + 6] = (v ushr 8).toByte()
        b[i + 7] = v.toByte()
    }

    /** 从真实文件读一段（经由 StreamingDownloadManager 落盘窗口）。 */
    private suspend fun readReal(
        mgr: StreamingDownloadManager,
        fileId: Int,
        offset: Long,
        length: Long,
    ): ByteArray {
        val ref = mgr.ensureRange(fileId, offset, length)
        val out = ByteArray(length.toInt())
        RandomAccessFile(ref.partialPath, "r").use { raf ->
            val refLen = File(ref.partialPath).length()
            raf.seek(if (refLen >= offset + length) offset else offset - ref.globalOffset)
            var got = 0
            while (got < out.size) {
                val n = raf.read(out, got, out.size - got)
                if (n <= 0) break
                got += n
            }
            if (got != out.size) return out.copyOf(got)
        }
        return out
    }
}
