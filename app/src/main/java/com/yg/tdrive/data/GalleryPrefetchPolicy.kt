package com.yg.tgdrive.data

/**
 * 看图页（[com.yg.tgdrive.ui.gallery.GalleryViewer]）的原图预取窗口策略。
 *
 * 为什么需要它：查看器打开后要「立刻把当前页换成原图」，同时不能把整个相册的原图都拉一遍
 * —— 相册动辄上千张，每张 1~10MB，全量预取会打爆内存、流量和 TDLib 下载队列。
 * 折中做法是只取**当前页 + 相邻各 1 页**：用户停在某页时当前页最快出原图，左右滑一步
 * 也大概率命中共存；再远就走「停稳后重新计算」。
 *
 * 这里只做纯计算（页码 → 待下载 mediaId 列表），不碰 Android/Compose，便于单测覆盖
 * 边界（首末页、越界、重复 id、已加载过滤）。
 */
object GalleryPrefetchPolicy {

    /** 预取半径：当前页前后各 1 页。 */
    const val NEIGHBOR_RADIUS = 1

    /**
     * 算出本次需要下载原图的 mediaId，按优先级排序。
     *
     * 顺序规则：当前页排第一，随后从近到远向两侧扩散（左、右、左二、右二…）。
     * 调用方按返回顺序串行/并发发起请求时，最先完成的总是用户正在看的那一页。
     *
     * 过滤规则：
     * - 页码夹紧到 `0 until min(total, idsByPage.size)`，越界不崩；
     * - mediaId <= 0 视为非法（未落库/占位数据），不请求；
     * - [loadedIds] 里已有的（原图已就绪）跳过，切回已加载的页不再重复下载；
     * - 结果去重，同一个 mediaId 只出现一次。
     *
     * @param current 当前停稳的页码（0 基）
     * @param total 相册总页数
     * @param idsByPage 页码 → mediaId（即相册图片序列的 id）
     * @param loadedIds 已加载原图的 mediaId 集合
     * @param radius 预取半径，默认 [NEIGHBOR_RADIUS]；0 表示只取当前页，负数按 0 处理
     */
    fun prefetchIds(
        current: Int,
        total: Int,
        idsByPage: List<Long>,
        loadedIds: Set<Long>,
        radius: Int = NEIGHBOR_RADIUS,
    ): List<Long> {
        if (total <= 0 || idsByPage.isEmpty()) return emptyList()
        // total 与数据长度不一致时以数据长度为准，避免下标越界
        val last = minOf(total, idsByPage.size) - 1
        if (last < 0) return emptyList()
        val center = current.coerceIn(0, last)
        val r = radius.coerceAtLeast(0)

        val out = LinkedHashSet<Long>()
        fun addAt(index: Int) {
            if (index < 0 || index > last) return
            val id = idsByPage[index]
            if (id <= 0L) return
            if (id in loadedIds) return
            out.add(id)
        }

        addAt(center)
        for (d in 1..r) {
            addAt(center - d)
            addAt(center + d)
        }
        return out.toList()
    }

    /**
     * 是否应该就「这张图原图没拉到」弹提示。
     *
     * 同一次查看里同一张图只提示一次：[alreadyNotified] 由调用方持有并回写，
     * 否则预取失败（尤其飞行模式/弱网）会在每次滑回该页时反复弹 Toast。
     */
    fun shouldNotifyFailure(id: Long, alreadyNotified: Set<Long>): Boolean =
        id > 0L && id !in alreadyNotified
}
