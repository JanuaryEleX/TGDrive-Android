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
 * 判据：窗口内出现 **≥ [MIN_FILL_RUN] 字节的全 0x00 / 全 0xFF 连续段**，其起点即可读
 * 边界（取最靠前的一段）。短于阈值的同值段若落在窗口末尾、且它前面已有可读数据，
 * 也截断（见 [runLength] 的 `atEof` 与尾部规则）—— 前沿之后的填充完全可能短于 1KB。
 *
 * ## 为什么尾部不能无条件截断
 *
 * 「尾部填充不论多短都截断」听起来更安全，实际会造成**永久卡死**：真实码流的最后
 * 一字节完全可能是合法的 0x00 或 0xFF（概率 2/256）。若下一轮探针只读到那一个字节，
 * 它又满足「尾部同值」→ 长度 0 → 永不推进 → 60s 超时抛 `DownloadException`。
 * 花屏变成整段播放失败，更糟。
 *
 * 所以「整窗就是一段短同值段」时绝不返回 0：只在 `atEof`（缓冲区末尾即文件真实
 * 末尾）时认定它是未写区，否则当作数据放行。而 `runStart > from`（前面已有可读
 * 数据）时截断是安全的 —— 返回值仍 > 0，探针继续推进，下一轮回读上下文即可区分
 * 「填充继续」与「数据里恰好一串 0x00/0xFF」。
 *
 * 真正的空洞动辄几百 KB（TDLib 按块预分配），不可能只有 1 字节。阈值判定天然
 * 区分两者：孤立的同值字节是数据，连续 1KB 同值才是未落盘。
 *
 * 阈值而非「任意同值段」还为了避开合法结构：mp4 的 box size 高字节本来就是 0x00
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
     * @param minFillRun 同值填充 run 的判定阈值，测试可调小
     * @param atEof 缓冲区末尾是否就是**文件的真实末尾**。为 true 时，窗口末尾的
     *   同值段不论多短、哪怕整窗都是它，都算空洞（文件已预分配到完整长度，末尾
     *   仍是填充就说明没写完）。为 false 时，末尾的短同值段只在前面已有可读数据
     *   时截断；整窗就是一段短同值段则放行 —— 否则探针只读到 1 字节时会被判成
     *   边界，永不推进，超时抛错。
     *   无默认值：这个判断必须由调用方基于文件长度显式给出。
     * @return 连续可读字节数；[from] 处就是边界 → 0
     */
    fun runLength(
        bytes: ByteArray,
        from: Int,
        limit: Int,
        minFillRun: Int = MIN_FILL_RUN,
        atEof: Boolean,
    ): Int {
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
                    val runLen = i - runStart
                    // ① 达到阈值 → 明确的未落盘区。
                    if (runLen >= minFillRun) return runStart - from
                    // ② 短 run 落在窗口末尾 → 可疑：TDLib 顺序落盘，前沿之后的填充
                    //    完全可能短于 1KB。只要它前面有已确认数据，就截断它（返回值
                    //    仍 > 0，探针继续推进）；下一轮从该处重读会拿到完整窗口，
                    //    从而区分「填充继续」与「只是数据里恰好有一串 0x00/0xFF」。
                    // ③ 整窗就是这一段短 run 时**不能**返回 0：那会让探针永不推进
                    //    （曾实测：窗口缩到 1 字节时 60s 超时抛 DownloadException）。
                    //    只有在 atEof（缓冲区末尾即文件真实末尾）时才认定它是未写区。
                    if (i == end && (atEof || runStart > from)) return runStart - from
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
