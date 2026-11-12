package com.yg.tgdrive.download

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile

/**
 * MP4 faststart 虚拟重排。
 *
 * Telegram 里大量 MP4 的 moov 位于文件尾（mdat 在前）。ExoPlayer 的 Mp4Extractor 会
 * seek 到文件尾去读 moov，而它把"本次 load 的起点"当作文件原点，导致 moov 里的
 * 绝对采样偏移被二次叠加（实测 3966111032 + 3943661055 = 7909772095 > 文件长度），
 * 最终 load 越界 EOF，报 "Loading finished before preparation is complete"。
 *
 * 这里不改写整个文件（3.7GB 不可能整体下载），而是构造一个"虚拟文件"：
 *
 *   [ftyp/free 前缀] [moov(已按 +moovLen 重写 stco/co64)] [mdat ... 原始数据顺移 moovLen]
 *
 * 于是 moov 出现在文件前部，extractor 不再 seek 到尾部，所有偏移都是文件绝对坐标。
 * 虚拟长度 = 原长度 + moovLen，读取时按映射回落到原始偏移（见 [Plan.toReal]）。
 *
 * ═══ 本版定位策略（替代旧的"盲读尾部 8MB"）═══
 * 旧实现为了找 moov 无条件同步拉最后 8MB —— 对 4GB 文件意味着把唯一的 TDLib 下载游标
 * 拨到尾部、与播放请求互踩，实测表现为「点进去有几秒速度，然后彻底不动」。
 *
 * 现在改为**逐跳读 box header**（每跳只读 16 字节），按 size 跳过大块：
 *
 *   典型 moov-at-tail 文件： ftyp(32B) → free(8B) → mdat(3.9GB, 只读 header) → moov
 *     = 4 次 16 字节 header 读 + 1 次 moov 本体读；mdat 内容一个字节都不读。
 *   典型 faststart 文件：    ftyp(32B) → moov(1.2MB)   ← 第 2 跳就发现 moov 在 mdat 之前，
 *     直接判定 plan=null（原文件本来就正确），**完全不碰文件尾**，这是最大的提速点。
 *
 * 头部链断裂（size 字段损坏 / 非 MP4）时回退到旧的 [findTrailingBox]（拉尾部 8MB 搜签名）。
 *
 * ═══ 硬上限 ═══
 * [build] 整体包 [PREPARE_PLAN_TIMEOUT_MS]：宁可先播起来（moov-at-tail 走 TdDataSource 可能
 * 首播失败），也不能让用户对着黑屏等十分钟。
 *
 * ═══ 大文件分流 ═══
 * 超过 [FASTSTART_MAX_BYTES] 的文件不做重排：在唯一的下载游标上抢尾部代价过高，
 * 直接顺序流播；moov-at-tail 的大文件首播失败由播放页明确提示（+ 离线下载入口）。
 */
object FaststartRemuxer {

    private const val TAG = "Faststart"

    /**
     * 「尾部 8MB 盲扫」回退方案的文件大小上限（header 链正常时不受此限）。
     * 大文件盲扫代价过高，宁可直接顺序流播。
     */
    const val FASTSTART_MAX_BYTES = 300L * 1024 * 1024

    /** prepare 阶段规划硬上限：超时即放弃重排，退回 TdDataSource 直连。 */
    private const val PREPARE_PLAN_TIMEOUT_MS = 15_000L

    /** 大文件规划上限：要多下 moov（几 MB）才能重排，给更长的窗口。 */
    private const val LARGE_PREPARE_TIMEOUT_MS = 60_000L

    /** box header 读取长度（size(4) + type(4) + 可选 largesize(8)）。 */
    private const val HEADER_BYTES = 16L

    /** readReal 的单次确认块大小（≥ 探针粒度，避免读到未落盘空洞）。 */
    private const val READ_CHUNK_BYTES = 512L * 1024

    /** header 链最多跳数（防止损坏文件把 size 读成 1 造成死循环/长链）。 */
    private const val MAX_BOX_HOPS = 64

    /** 回退方案：一次拉取用于定位 moov 的尾部窗口上限。 */
    private const val TAIL_SCAN_BYTES = 8L * 1024 * 1024

    /** 允许出现在 moov 之前的顶层"前缀" box（虚拟布局里保持在原位）。 */
    private val PREFIX_TYPES = setOf("ftyp", "free", "skip", "wide", "styp", "pdin")

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

    private class Box(val start: Long, val size: Long, val type: String) {
        val end: Long get() = start + size
    }

    /** buildInner 的结论：plan 非空 = 需要重排；reason 写进诊断。 */
    private class Outcome(val plan: Plan?, val reason: String)

    /**
     * 构建重排计划。返回 null 表示"不需要/不能重排"，调用方退回 [TdDataSource] 直连。
     */
    suspend fun build(mgr: StreamingDownloadManager, fileId: Int, size: Long): Plan? {
        if (size <= 0L) {
            PlaybackDiag.plan("direct", "size_unknown")
            return null
        }
        // 大文件同样走 header 链：链式跳读只需 16 字节/跳，尾部代价只是 moov 本身，
        // 远比"让解析器自己去尾部找 moov"便宜。反之若不做重排，Mp4Extractor 会为了
        // 读 moov 去 seek 文件尾，把唯一下载游标反复拽来拽去（实测 1.7GB 文件 10 次重定位），
        // 播放永远起不来。只有"尾部 8MB 盲扫"这种昂贵回退才按大小设限。
        val allowTailScan = size <= FASTSTART_MAX_BYTES
        val timeoutMs = if (size > FASTSTART_MAX_BYTES) LARGE_PREPARE_TIMEOUT_MS else PREPARE_PLAN_TIMEOUT_MS

        val t0 = SystemClock.elapsedRealtime()
        var outcome: Outcome? = null
        val finished = withTimeoutOrNull(timeoutMs) {
            outcome = try {
                buildInner(mgr, fileId, size, allowTailScan)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "构建异常 file=$fileId: ${t.message}")
                Outcome(null, "error:${t.message}")
            }
        }

        if (finished == null) {
            // 超时：prepare 阶段遗留的尾部请求必须收掉，交棒 TdDataSource 前保持游标干净。
            val ms = SystemClock.elapsedRealtime() - t0
            PlaybackDiag.plan("timeout", "prepare>${timeoutMs}ms (${ms}ms)")
            Log.w(TAG, "file=$fileId 规划超时（${ms}ms），退回直连")
            runCatching { mgr.abortPending(fileId) }
            return null
        }

        val o = outcome ?: Outcome(null, "no_outcome")
        val ms = SystemClock.elapsedRealtime() - t0
        if (o.plan != null) {
            PlaybackDiag.plan("remux", "${o.reason} (${ms}ms)")
            PlaybackDiag.planDetail(
                moovStart = o.plan.moovStart,
                moovLen = o.plan.moovLen,
                prefixLen = o.plan.prefixLen,
                virtualSize = o.plan.virtualSize,
            )
        } else {
            PlaybackDiag.plan("direct", "${o.reason} (${ms}ms)")
        }
        return o.plan
    }

    private suspend fun buildInner(
        mgr: StreamingDownloadManager,
        fileId: Int,
        size: Long,
        allowTailScan: Boolean,
    ): Outcome {
        val chain = ArrayList<String>(8)
        var pos = 0L
        var prefixLen = 0L
        var moov: Box? = null
        var broken = false
        var hops = 0

        while (hops < MAX_BOX_HOPS) {
            if (pos + 8 > size) {
                // 剩余不足一个 header：链在文件尾正常结束（未找到 moov）
                chain.add("eof@$pos")
                break
            }
            val hdr = readReal(mgr, fileId, pos, HEADER_BYTES)
            val box = parseHeader(hdr, pos)
            if (box == null || box.size < 8L || box.end > size) {
                chain.add("bad@$pos")
                broken = true
                break
            }
            chain.add("${box.type}@${box.start}+${box.size}")
            hops++
            when {
                box.type == "moov" -> {
                    moov = box
                    break
                }
                box.type == "mdat" -> pos = box.end
                else -> {
                    if (box.type in PREFIX_TYPES && prefixLen == box.end) {
                        // 连续的前缀 box：记录虚拟布局的插入点
                    }
                    pos = box.end
                }
            }
            if (box.type in PREFIX_TYPES) prefixLen = box.end
            if (pos >= size) break
        }

        PlaybackDiag.boxChain(chain)

        if (moov == null) {
            if (broken) {
                // header 链断裂 → 回退旧策略（拉尾部 8MB 搜签名）
                if (allowTailScan) {
                    Log.w(TAG, "header 链断裂，回退尾部扫描 file=$fileId")
                    return trailingScan(mgr, fileId, size)
                }
                Log.w(TAG, "header 链断裂且文件过大（$size），不回退尾部盲扫 file=$fileId")
                return Outcome(null, "chain_broken size=$size")
            }
            return Outcome(null, "moov_not_after_mdat")
        }
        if (moov.start == 0L) return Outcome(null, "moov_at_0")
        if (moov.end != size) {
            // moov 不在文件尾：不需要重排（extractor 不会 seek 到尾部）
            return Outcome(null, "moov_not_at_tail start=${moov.start} end=${moov.end}")
        }
        if (prefixLen <= 0L || prefixLen >= moov.start) {
            // 没有可用的前缀区（moov 紧贴文件头/前缀异常）：虚拟布局无从构建
            return Outcome(null, "no_prefix prefixLen=$prefixLen moovStart=${moov.start}")
        }

        val moovBytes = readReal(mgr, fileId, moov.start, moov.size)
        if (moovBytes.size.toLong() != moov.size) {
            return Outcome(null, "moov_short read=${moovBytes.size} want=${moov.size}")
        }

        // moov 从文件尾挪到 prefixLen 处，mdat 整体后移 moovLen：所有 chunk 偏移 +moovLen
        patchChunkOffsets(moovBytes, 0, moovBytes.size, moov.size)

        return Outcome(
            Plan(fileId, size, prefixLen, moovBytes, moov.start),
            "moov_at_tail hops=$hops prefix=$prefixLen",
        )
    }

    /** 回退：拉最后 [TAIL_SCAN_BYTES] 找"正好以文件尾结束"的顶层 box。 */
    private suspend fun trailingScan(
        mgr: StreamingDownloadManager,
        fileId: Int,
        size: Long,
    ): Outcome {
        val prefix = readReal(mgr, fileId, 0L, 64L)
        if (prefix.size < 16) return Outcome(null, "fallback:prefix_short")
        val prefixLen = topLevelEnd(prefix) ?: return Outcome(null, "fallback:no_ftyp")
        if (prefixLen <= 0L || prefixLen >= size) return Outcome(null, "fallback:prefix_bad")

        val tailLen = minOf(size, TAIL_SCAN_BYTES)
        val tailStart = size - tailLen
        val tail = readReal(mgr, fileId, tailStart, tailLen)
        if (tail.size < 16) return Outcome(null, "fallback:tail_short")

        val moovBox = findTrailingBox(tail, tailStart, size)
            ?: return Outcome(null, "fallback:no_trailing_box")
        if (moovBox.type != "moov") return Outcome(null, "fallback:tail_is_${moovBox.type}")

        val moovBytes = readReal(mgr, fileId, moovBox.start, moovBox.size)
        if (moovBytes.size.toLong() != moovBox.size) {
            return Outcome(null, "fallback:moov_short")
        }
        patchChunkOffsets(moovBytes, 0, moovBytes.size, moovBox.size)
        return Outcome(
            Plan(fileId, size, prefixLen, moovBytes, moovBox.start),
            "fallback:trailing_scan",
        )
    }

    /** 解析 16 字节 header（含 64 位 largesize 情况）。 */
    private fun parseHeader(hdr: ByteArray, absoluteStart: Long): Box? {
        if (hdr.size < 8) return null
        var s = beInt(hdr, 0).toLong()
        val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
        if (type.any { it.code < 0x20 || it.code > 0x7e }) return null
        if (s == 1L) {
            if (hdr.size < 16) return null
            s = beLong(hdr, 8)
        } else if (s == 0L) {
            // size=0 表示"一直到文件尾"，由调用方按 end>fileSize 判断裂
            return null
        }
        return Box(absoluteStart, s, type)
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

    /** 第一个顶层 box 的结束位置（回退路径用）。 */
    private fun topLevelEnd(buf: ByteArray): Long? {
        var s = beInt(buf, 0).toLong()
        val type = String(buf, 4, 4, Charsets.ISO_8859_1)
        var header = 8L
        if (s == 1L) {
            if (buf.size < 16) return null
            s = beLong(buf, 8)
            header = 16L
        }
        if (s < header || type !in PREFIX_TYPES) return null
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
                "stco" -> patchStco(buf, p + header, p + size.toInt(), delta)
                "co64" -> patchStco(buf, p + header, p + size.toInt(), delta)
                else -> patchChunkOffsets(buf, p + header, p + size.toInt(), delta)
            }
            p += size.toInt()
        }
    }

    /** stco: version/flags(4) + entryCount(4) + 32bit 偏移；co64: 64bit 偏移。 */
    private fun patchStco(buf: ByteArray, body: Int, end: Int, delta: Long) {
        if (body + 8 > end) return
        val count = beInt(buf, body + 4)
        if (count < 0) return
        var p = body + 8
        // 32/64 位由条目数×宽度校验来推断
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

    /**
     * 从真实文件读一段（经由 StreamingDownloadManager 落盘窗口）。
     *
     * 必须**逐块** ensureRange(requireEnd=true)：探针确认粒度是 64KB，一次要整段
     * （moov 常见几 MB）会把中间未落盘、TDLib 预分配的全零空洞一起读出来，
     * 进而把零当 chunk offset 表解析 → 花屏或 "Loading finished before preparation"。
     */
    private suspend fun readReal(
        mgr: StreamingDownloadManager,
        fileId: Int,
        offset: Long,
        length: Long,
    ): ByteArray {
        val out = ByteArray(length.toInt())
        var got = 0
        var cur = offset
        while (got < out.size) {
            val chunk = minOf((out.size - got).toLong(), READ_CHUNK_BYTES)
            val ref = mgr.ensureRange(fileId, cur, chunk, tag = "prepare", requireEnd = true)
            RandomAccessFile(ref.partialPath, "r").use { raf ->
                raf.seek(cur - ref.globalOffset)
                var n = 0
                while (n < chunk) {
                    val r = raf.read(out, got + n, (chunk - n).toInt())
                    if (r <= 0) break
                    n += r
                }
                got += n
                if (n.toLong() < chunk) return out.copyOf(got)
            }
            cur += chunk
        }
        return out
    }
}
