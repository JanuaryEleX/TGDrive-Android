package com.yg.tgdrive.data

import androidx.room.ColumnInfo

/**
 * 收藏状态的纯逻辑层（**不依赖 Android / Room / 协程**，可直接 JUnit 跑）。
 *
 * 为什么单独抽出来：收藏有两处「同一语义」的判定 ——
 * 1. DAO 里的 `ORDER BY favorited_at DESC, id DESC`（SQL 侧排序）
 * 2. 播放队列在内存里重排收藏行（[orderByFavoriteTime]）
 * 两边不一致就会出现「收藏页第 3 个点开却在播放器里显示第 5 集」这类鬼现象。
 * 把规则集中在这里，SQL 注释与单测共同锁定契约。
 */
object FavoritesPolicy {

    /**
     * 合法收藏时间下界（epoch ms）。
     *
     * <= 0 的值按「未收藏」处理：历史数据里可能残留 0（早期某些写库路径用 0 表示
     * 「已收藏但没时间」），若当成收藏，收藏页会出现一堆 1970 年的条目排在最后。
     */
    const val MIN_TIMESTAMP = 1L

    /** 播放队列上限：与海报墙一致（最多 400 集，避免超大 Intent extra）。 */
    const val DEFAULT_QUEUE_LIMIT = 400

    /** 是否处于已收藏状态。null / 非正数一律视为未收藏。 */
    fun isFavorited(favoritedAt: Long?): Boolean =
        favoritedAt != null && favoritedAt >= MIN_TIMESTAMP

    /**
     * 切换收藏状态，返回**切换后**应写入库的值：null = 取消收藏，正数 = 收藏时间。
     *
     * [now] 由调用方传入（默认 System.currentTimeMillis()），便于单测与「同一 ms 内连点」
     * 的去重；时钟回拨（now <= 0）时夹到 [MIN_TIMESTAMP]，绝不写出「不算收藏」的脏值。
     */
    fun toggleValue(current: Long?, now: Long): Long? =
        if (isFavorited(current)) null else now.coerceAtLeast(MIN_TIMESTAMP)

    /**
     * 收藏页顺序：收藏时间倒序；同一毫秒内按 id 倒序（新入库的在前），保证顺序稳定。
     * 顺带过滤掉 favoritedAt 为脏值（null / <= 0）的行 —— 与 DAO 的 IS NOT NULL 语义对齐。
     */
    fun orderByFavoriteTime(rows: List<FavoriteRow>): List<FavoriteRow> =
        rows.filter { isFavorited(it.favoritedAt) }
            .sortedWith(
                compareByDescending<FavoriteRow> { it.favoritedAt ?: 0L }
                    .thenByDescending { it.id }
            )

    /**
     * 生成播放队列：[limit] 条以内的收藏 id（收藏时间倒序）+ [anchorId] 在队列中的下标。
     *
     * 下标为 -1 表示锚点不在队列里（例如它刚好被取消收藏，或排在 limit 之外）——
     * 调用方照旧传 -1 给 openPlayer，播放器只是不显示上下集，不会崩。
     */
    fun queue(
        rows: List<FavoriteRow>,
        anchorId: Long,
        limit: Int = DEFAULT_QUEUE_LIMIT,
    ): FavoriteQueue {
        val ordered = orderByFavoriteTime(rows)
        val capped = if (limit > 0) ordered.take(limit) else ordered
        val ids = capped.map { it.id }
        return FavoriteQueue(ids = ids, index = ids.indexOf(anchorId))
    }
}

/**
 * 收藏行最小投影（只取建队列需要的两列，避免把整行媒体元数据搬进内存）。
 * 字段名与列名对齐由 @ColumnInfo 保证 —— Room 也用它做 DAO 返回类型。
 */
data class FavoriteRow(
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "favorited_at")
    val favoritedAt: Long?,
)

/** 收藏播放队列：id 列表（收藏时间倒序）+ 锚点下标（-1 = 不在队列中）。 */
data class FavoriteQueue(
    val ids: List<Long>,
    val index: Int,
) {
    val isEmpty: Boolean get() = ids.isEmpty()
}
