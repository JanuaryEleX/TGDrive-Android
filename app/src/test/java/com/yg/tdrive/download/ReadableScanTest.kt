package com.yg.tgdrive.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReadableScan.runLength] 的边界语义。
 *
 * 两组参数：`atEof = true` 表示缓冲区末尾就是文件真实末尾（文件末尾的填充必是未写区），
 * `atEof = false` 表示缓冲区只是文件中间的一个探测窗口（短尾部填充是合法数据）。
 */
class ReadableScanTest {

    /** 非填充字节：取值落在 0x01..0x7F，永不等于 0x00/0xFF。 */
    private fun data(n: Int, seed: Int = 0x41): ByteArray =
        ByteArray(n) { (((it + seed) and 0x7F) or 0x01).toByte() }

    // ═══════════════════════ 整窗填充 ═══════════════════════

    /** 整窗全零 = TDLib 预分配未落盘 → 一个字节都不能放行。 */
    @Test fun allZeroWindowIsHole() {
        assertEquals(0, ReadableScan.runLength(ByteArray(4096), 0, 4096, atEof = true))
    }

    /** 部分代理把未落盘区预填成 0xFF → 同样按空洞处理（旧 nonZeroIn 的已知坑）。 */
    @Test fun allFFWindowIsHole() {
        assertEquals(0, ReadableScan.runLength(ByteArray(4096) { 0xFF.toByte() }, 0, 4096, atEof = true))
    }

    /** 窗口长度刚好等于阈值且全是填充 → 判空洞。 */
    @Test fun fillWindowAtThresholdIsHole() {
        assertEquals(0, ReadableScan.runLength(ByteArray(ReadableScan.MIN_FILL_RUN), 0, ReadableScan.MIN_FILL_RUN, atEof = true))
    }

    // ═══════════════════════ 真机形态 ═══════════════════════

    /** 只落了前 1KB，余 63KB 是填充 → 只能确认 1KB，不能整窗放行（这就是花屏的直接成因）。 */
    @Test fun onlyFrontBytesConfirmedWhenTailIsFill() {
        val buf = ByteArray(64 * 1024)
        data(1024).copyInto(buf, 0)
        assertEquals(1024, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 全窗都是真实数据 → 全长确认。 */
    @Test fun fullDataWindowIsFullyReadable() {
        val buf = data(4096)
        assertEquals(4096, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    // ═══════════════════════ 尾部填充：关键分歧点 ═══════════════════════

    /**
     * 窗口末字节是合法 0x00、且窗口不在文件末尾 → **整窗放行**。
     *
     * 无条件截断尾部会让探针在下一轮只读到这一个同值字节 → 又判边界 → 长度 0 →
     * 永不推进 → 60s 超时抛错。孤立的同值字节是数据，不是填充。
     */
    @Test fun singleTrailingFillByteInWindowIsData() {
        val buf = data(4096)
        buf[4095] = 0
        assertEquals(4096, ReadableScan.runLength(buf, 0, buf.size, atEof = false))
    }

    /** 同一缓冲区若确实是文件末尾 → 尾部 1 字节填充就是未写区。 */
    @Test fun singleTrailingFillByteAtEofIsABoundary() {
        val buf = data(4096)
        buf[4095] = 0
        assertEquals(4095, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 窗口尾部短填充（< 阈值）后紧跟窗口边界 → 按数据放行，探针不会卡住。 */
    @Test fun tailFillShorterThanThresholdInWindowIsData() {
        val buf = data(4096)
        ByteArray(1023).copyInto(buf, 3073)
        assertEquals(4096, ReadableScan.runLength(buf, 0, buf.size, atEof = false))
    }

    /** 同一形态在文件末尾 → 截断到 3073。 */
    @Test fun tailFillShorterThanThresholdAtEofIsABoundary() {
        val buf = data(4096)
        ByteArray(1023).copyInto(buf, 3073)
        assertEquals(3073, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 尾部填充达到阈值 → 不论是否文件末尾都截断（真正的空洞动辄几百 KB）。 */
    @Test fun tailFillAtThresholdIsABoundary() {
        val buf = data(4096)
        ByteArray(1024).copyInto(buf, 3072)
        assertEquals(3072, ReadableScan.runLength(buf, 0, buf.size, atEof = false))
        assertEquals(3072, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    // ═══════════════════════ 内部填充 ═══════════════════════

    /**
     * 窗口**开头**是空洞、后面却有数据（TDLib 高优先区间 + 顺序下载并存）→ 在开头截断。
     *
     * 只看尾部的实现会在这里整窗放行，把空洞交给解码器 —— 正是要修的花屏。
     */
    @Test fun leadingHoleFollowedByDataIsTruncated() {
        val buf = ByteArray(8192)
        data(4096, 0x33).copyInto(buf, 4096)
        assertEquals(0, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 窗口中间的长空洞 → 在其起点截断。 */
    @Test fun interiorFillRunTruncates() {
        val buf = ByteArray(8192)
        data(1024).copyInto(buf, 0)
        data(1024, 0x33).copyInto(buf, 7168)
        assertEquals(1024, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 内部填充段短于阈值 → 不截断（真码流里的零散 0x00 不该被当成边界）。 */
    @Test fun shortInteriorFillRunIsNotABoundary() {
        val buf = ByteArray(4096)
        data(1024).copyInto(buf, 0)
        ByteArray(512).copyInto(buf, 1024)
        data(2560, 0x21).copyInto(buf, 1536)
        assertEquals(4096, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 内部填充段长度刚好等于阈值 → 截断。 */
    @Test fun interiorFillRunExactlyAtThresholdTruncates() {
        val buf = ByteArray(4096)
        data(1024).copyInto(buf, 0)
        ByteArray(1024).copyInto(buf, 1024)
        data(2048, 0x21).copyInto(buf, 2048)
        assertEquals(1024, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** 内部填充段 = 阈值 - 1 → 不截断。 */
    @Test fun interiorFillRunOneBelowThresholdIsAccepted() {
        val buf = ByteArray(4096)
        data(1024).copyInto(buf, 0)
        ByteArray(1023).copyInto(buf, 1024)
        data(2049, 0x21).copyInto(buf, 2047)
        assertEquals(4096, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    /** mp4 box size 的高字节本来就是 0x00，不能被当成填充边界。 */
    @Test fun mp4BoxSizeLeadingZerosAreNotAFillBoundary() {
        val buf = ByteArray(256)
        byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
            .copyInto(buf, 0)
        data(224, 0x21).copyInto(buf, 32)
        assertEquals(256, ReadableScan.runLength(buf, 0, buf.size, atEof = true))
    }

    // ═══════════════════════ 参数边界 ═══════════════════════

    /** limit 小于缓冲区 → 只考察 limit 范围，不能把 limit 之后的内容当成窗口。 */
    @Test fun limitRestrictsScan() {
        val buf = ByteArray(8192)
        data(4096).copyInto(buf, 0)
        ByteArray(4096).copyInto(buf, 4096)
        assertEquals(4096, ReadableScan.runLength(buf, 0, 4096, atEof = true))
    }

    /** from 非零起点 → 返回值相对 from 计量。 */
    @Test fun nonZeroStartReturnsRelativeLength() {
        val buf = ByteArray(8192)
        data(1024, 0x11).copyInto(buf, 2048)
        assertEquals(1024, ReadableScan.runLength(buf, 2048, 4096, atEof = true))
    }

    /** 单字节窗口不在文件末尾 → 一律当数据（不能判边界，否则探针卡死）。 */
    @Test fun singleByteWindowInFileIsData() {
        assertEquals(1, ReadableScan.runLength(byteArrayOf(0), 0, 1, atEof = false))
        assertEquals(1, ReadableScan.runLength(byteArrayOf(0x41), 0, 1, atEof = false))
    }

    /** 单字节窗口就是文件末尾 → 填充判空洞，数据放行。 */
    @Test fun singleByteWindowAtEof() {
        assertEquals(0, ReadableScan.runLength(byteArrayOf(0), 0, 1, atEof = true))
        assertEquals(1, ReadableScan.runLength(byteArrayOf(0x41), 0, 1, atEof = true))
    }

    /** 越界 / 空区间 → 0，不抛异常。 */
    @Test fun outOfRangeIsZero() {
        val buf = data(64)
        assertEquals(0, ReadableScan.runLength(buf, 64, 10, atEof = true))
        assertEquals(0, ReadableScan.runLength(buf, 0, 0, atEof = true))
        assertEquals(0, ReadableScan.runLength(buf, -1, 10, atEof = true))
    }

    /** minFillRun 非法 → 抛 IllegalArgumentException，先于任何早退。 */
    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveMinFillRunThrows() {
        ReadableScan.runLength(ByteArray(16), 0, 0, minFillRun = 0, atEof = true)
    }
}
