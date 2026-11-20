package com.yg.tgdrive.download

/**
 * 「这一段字节真的落盘了」的磁盘判据。
 *
 * TDLib 的 partial 是**预分配**的稀疏文件：文件长度一上来就是完整大小，未落盘区域
 * 读出来是 0x00（真机实测部分版本/代理会填 0xFF）。把这些填充字节当码流交给
 * Mp4Extractor 或解码器，表现就是 box 尺寸乱码、`Loading finished before preparation`、
 * 以及**画面花屏**。
 *
 * 旧判据（`StreamingDownloadManager.nonZeroIn`）只要求 64KB 窗口里存在**一个**非零
 * 字节，就把整个 64KB 登记为「连续已落盘」。真实数据只落了前 1KB 时，剩下 63KB 的
 * 填充照样被放行 —— 这是花屏反复出现的直接成因。
 *
 * 现判据两条，取更靠前的边界：
 *
 * 1. **尾部填充**：窗口末尾若是全 0x00 / 全 0xFF 的连续段，不论多短都截断到它的起点。
 *    TDLib 顺序落盘，前沿之后必是填充，所以尾部填充一定是未写区。哪怕只剩 1 字节，
 *    放行也会污染最后一帧。
 * 2. **内部长填充**：窗口内部出现 ≥ [MIN_FILL_RUN] 的全 0x00 / 全 0xFF 连续段也截断。
 *    这一条覆盖「`downloadFile` 指定的高优先区间 + 后台顺序下载」并存时的形态：
 *    窗口开头可能是空洞、后面却有数据，只看尾部会把开头的空洞放行。
 *
 * 阈值而非「任意同值段」是为了避开合法数据：mp4 的 box size 高字节本来就是 0x00
 * （`ftyp`/`moov` 都以 `00 00 00 xx` 开头），样本对齐也会产生零散零字节。4 字节的
 * 零高字节远小于 1KB，不会误判。
 *
 * 代价是 mdat 内部合法的长全零段（`free`/`skip` box）会被当成边界，导致少确认一段。
 * 退路是 TDLib 自报的 `downloaded_prefix_size`
 * （`StreamingDownloadManager.awaitDataLocked` 第 2 条判据，零 IO）—— 下载推进到
 * 那里后自然放行。宁可多等一次，不可错放一帧。
 */
object ReadableScan {

    /**
     * 认定为「窗口内部未落盘边界」所需的最小填充 run 长度。
     *
     * 取 1KB：视频/音频样本是熵编码产物，出现 ≥1KB 完全同值（0x00/0xFF）的连续段
     * 基本不可能；而 mp4 结构里的合法零字节只有 box size 高字节（≤4 字节）。
     */
    const val MIN_FILL_RUN = 1024

    /**
     * 从 [from] 起连续可读的字节数。
     *
     * @param bytes 缓冲区
     * @param from 起始下标
     * @param limit 最多考察的字节数（自 [from] 起，超出缓冲区按缓冲区尾算）
     * @param minFillRun 内部填充 run 的判定阈值，测试可调小
     * @return 连续可读字节数；[from] 处就是边界 → 0
     */
    fun runLength(bytes: ByteArray, from: Int, limit: Int, minFillRun: Int = MIN_FILL_RUN): Int {
        require(minFillRun > 0) { "minFillRun 必须 > 0" }
        if (from < 0 || limit <= 0 || from >= bytes.size) return 0
        val end = minOf(bytes.size.toLong(), from.toLong() + limit.toLong()).toInt()
        if (end <= from) return 0

        var runStart = from
        for (i in from + 1..end) {
            val prev = bytes[i - 1]
            // 同值 run 在 i 处闭合；i == end 时强制闭合，避免越界读。
            if (i == end || bytes[i] != prev) {
                if (isFillByte(prev)) {
                    val isTail = i == end
                    if (isTail || i - runStart >= minFillRun) return runStart - from
                }
                runStart = i
            }
        }
        return end - from
    }

    /** TDLib 预分配填充的两个取值。 */
    private fun isFillByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0x00 || v == 0xFF
    }
}
