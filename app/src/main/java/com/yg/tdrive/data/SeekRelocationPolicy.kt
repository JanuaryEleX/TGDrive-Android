package com.yg.tgdrive.data

/**
 * 跳跃 seek 是否必须重定位 TDLib 下载游标（纯策略，可单测）。
 *
 * 背景（真机诊断 play-diag.json 得出）：
 * TDLib 对同一个 file 只有一条顺序下载游标，`downloadFile(offset, limit=0)` 会从
 * offset 一路顺序下到文件尾。位置跳到游标还没下到的地方，必须 cancel + 重新
 * downloadFile，否则读取方只能干等。
 *
 * 旧判据用「已确认落盘的最高偏移 watermark」（含 moov-at-tail 探针读到的文件尾）
 * 做前视窗口：
 * - 播放 2.4GB 文件时 moov 在尾，prepare 阶段读了 [尾部, EOF)，watermark 被抬到 EOF；
 * - 之后任何**向前**拖动都算 `target - watermark < 0`，落进「窗口内」→ 不重定位；
 * - 于是 TDLib 继续从 base（可能是 0）顺序下，2.4GB 文件意味着永远等不到 →
 *   读数线程 60s 超时 → 重试 → 用户看到的就是「拖完一直加载」。
 *
 * 正确判据是**顺序前沿**＝当前请求起点 + TDLib 自报前缀，它才是「下载正在朝哪里推进」。
 */
object SeekRelocationPolicy {

    /** 前视窗口：目标超出「顺序前沿 + 该值」才算跳跃。要大于正常播放的缓冲需求。 */
    const val LOOKAHEAD_BYTES = 16L * 1024 * 1024

    /**
     * @param base 当前在飞 downloadFile 的起点（512KB 对齐绝对偏移），-1 = 还没发过
     * @param prefix 相对 [base] 的连续已下载前缀（TDLib `downloaded_prefix_size`）
     * @param target 本次要读的绝对偏移
     */
    fun needsRelocation(base: Long, prefix: Long, target: Long): Boolean {
        if (base < 0L) return true            // 还没发过请求
        if (target < base) return true        // 往回拖：顺序游标不会回头
        val frontier = base + prefix.coerceAtLeast(0L)
        return target - frontier > LOOKAHEAD_BYTES
    }
}
