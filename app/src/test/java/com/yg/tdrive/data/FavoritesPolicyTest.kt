package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏纯逻辑单测（不依赖 Android / Room，`./gradlew test` 直接跑）。
 *
 * 覆盖三件事：
 * 1. 切换语义（收藏 ⇄ 取消收藏、脏时间戳归一化）
 * 2. 排序契约（收藏时间倒序 + 同毫秒按 id 倒序）—— 必须与 MediaDao.observeFavoritesPaged
 *    的 `ORDER BY favorited_at DESC, id DESC` 一致
 * 3. 播放队列下标（锚点定位 / 上限截断 / 锚点不在收藏里时 -1）
 */
class FavoritesPolicyTest {

    @Test
    fun `null 与非法时间戳都算未收藏`() {
        assertFalse(FavoritesPolicy.isFavorited(null))
        assertFalse(FavoritesPolicy.isFavorited(0L))
        assertFalse(FavoritesPolicy.isFavorited(-1L))
        assertTrue(FavoritesPolicy.isFavorited(1L))
        assertTrue(FavoritesPolicy.isFavorited(1_700_000_000_000L))
    }

    @Test
    fun `未收藏时切换得到当前时间戳`() {
        assertEquals(1_700_000_000_000L, FavoritesPolicy.toggleValue(null, 1_700_000_000_000L))
    }

    @Test
    fun `已收藏时切换得到 null 即取消收藏`() {
        assertNull(FavoritesPolicy.toggleValue(1_700_000_000_000L, 1_700_000_001_000L))
    }

    @Test
    fun `脏时间戳 0 再点一次仍算收藏而不是取消`() {
        // 0 被当成「未收藏」→ 切换应写入合法时间戳，而不是因「非 null」被判为已收藏后清空
        assertEquals(123L, FavoritesPolicy.toggleValue(0L, 123L))
    }

    @Test
    fun `时钟回拨不会写出非法时间戳`() {
        assertEquals(FavoritesPolicy.MIN_TIMESTAMP, FavoritesPolicy.toggleValue(null, 0L))
        assertEquals(FavoritesPolicy.MIN_TIMESTAMP, FavoritesPolicy.toggleValue(null, -5L))
        // 写进去的值必须自身也算「已收藏」，否则刚收藏完就掉出收藏页
        assertTrue(FavoritesPolicy.isFavorited(FavoritesPolicy.toggleValue(null, -5L)))
    }

    @Test
    fun `收藏行按收藏时间倒序`() {
        val rows = listOf(
            FavoriteRow(id = 1, favoritedAt = 100L),
            FavoriteRow(id = 2, favoritedAt = 300L),
            FavoriteRow(id = 3, favoritedAt = 200L),
        )
        assertEquals(listOf(2L, 3L, 1L), FavoritesPolicy.orderByFavoriteTime(rows).map { it.id })
    }

    @Test
    fun `同一毫秒内按 id 倒序保证顺序稳定`() {
        val rows = listOf(
            FavoriteRow(id = 7, favoritedAt = 500L),
            FavoriteRow(id = 9, favoritedAt = 500L),
            FavoriteRow(id = 8, favoritedAt = 500L),
        )
        assertEquals(listOf(9L, 8L, 7L), FavoritesPolicy.orderByFavoriteTime(rows).map { it.id })
    }

    @Test
    fun `排序时过滤掉脏时间戳行`() {
        val rows = listOf(
            FavoriteRow(id = 1, favoritedAt = null),
            FavoriteRow(id = 2, favoritedAt = 0L),
            FavoriteRow(id = 3, favoritedAt = 42L),
        )
        assertEquals(listOf(3L), FavoritesPolicy.orderByFavoriteTime(rows).map { it.id })
    }

    @Test
    fun `队列与收藏顺序一致且锚点下标正确`() {
        val rows = listOf(
            FavoriteRow(id = 11, favoritedAt = 100L),
            FavoriteRow(id = 12, favoritedAt = 300L),
            FavoriteRow(id = 13, favoritedAt = 200L),
        )
        val q = FavoritesPolicy.queue(rows, anchorId = 13)
        assertEquals(listOf(12L, 13L, 11L), q.ids)
        assertEquals(1, q.index)
        assertFalse(q.isEmpty)
    }

    @Test
    fun `锚点不在收藏里时下标为负一`() {
        val rows = listOf(FavoriteRow(id = 1, favoritedAt = 10L))
        assertEquals(-1, FavoritesPolicy.queue(rows, anchorId = 99).index)
    }

    @Test
    fun `空收藏的队列为空且下标为负一`() {
        val q = FavoritesPolicy.queue(emptyList(), anchorId = 1)
        assertTrue(q.isEmpty)
        assertEquals(-1, q.index)
    }

    @Test
    fun `超过上限时截断为最近收藏的若干条`() {
        val rows = (1L..500L).map { FavoriteRow(id = it, favoritedAt = it) }
        val q = FavoritesPolicy.queue(rows, anchorId = 500L, limit = 400)
        assertEquals(400, FavoritesPolicy.queue(rows, 1L).ids.size)
        assertEquals(400, q.ids.size)
        // 最新收藏（时间戳最大）排第一，锚点也在队列里
        assertEquals(500L, q.ids.first())
        assertEquals(0, q.index)
    }
}
