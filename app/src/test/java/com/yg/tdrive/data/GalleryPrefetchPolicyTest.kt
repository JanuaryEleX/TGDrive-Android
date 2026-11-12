package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPrefetchPolicyTest {

    /** 普通相册：5 张图，mediaId 10/20/30/40/50。 */
    private val fivePages = listOf(10L, 20L, 30L, 40L, 50L)

    @Test
    fun `当前页最先返回_随后左右邻居`() {
        val ids = GalleryPrefetchPolicy.prefetchIds(
            current = 1,
            total = 5,
            idsByPage = fivePages,
            loadedIds = emptySet(),
        )
        // 当前页(20) 必须排第一：它决定用户看到的那一屏何时变清晰
        assertEquals(listOf(20L, 10L, 30L), ids)
    }

    @Test
    fun `首末页裁剪越界邻居`() {
        assertEquals(
            listOf(10L, 20L),
            GalleryPrefetchPolicy.prefetchIds(0, 5, fivePages, emptySet()),
        )
        assertEquals(
            listOf(50L, 40L),
            GalleryPrefetchPolicy.prefetchIds(4, 5, fivePages, emptySet()),
        )
    }

    @Test
    fun `已加载的页不再返回`() {
        val ids = GalleryPrefetchPolicy.prefetchIds(
            current = 1,
            total = 5,
            idsByPage = fivePages,
            loadedIds = setOf(20L, 10L),
        )
        // 当前页与左邻都已加载 → 只剩右邻
        assertEquals(listOf(30L), ids)
    }

    @Test
    fun `窗口内重复的mediaId只返回一次`() {
        val ids = GalleryPrefetchPolicy.prefetchIds(
            current = 1,
            total = 3,
            idsByPage = listOf(20L, 10L, 20L),
            loadedIds = emptySet(),
        )
        assertEquals(listOf(10L, 20L), ids)
    }

    @Test
    fun `上千张的相册也只取窗口内的少数几张`() {
        val thousand = (1L..1000L).toList()
        val ids = GalleryPrefetchPolicy.prefetchIds(500, 1000, thousand, emptySet())
        assertEquals(3, ids.size)
        // 页码 500 → mediaId 501
        assertEquals(listOf(501L, 500L, 502L), ids)
    }

    @Test
    fun `半径为0时只取当前页`() {
        val ids = GalleryPrefetchPolicy.prefetchIds(
            current = 2,
            total = 5,
            idsByPage = fivePages,
            loadedIds = emptySet(),
            radius = 0,
        )
        assertEquals(listOf(30L), ids)
    }

    @Test
    fun `半径变大时两侧依次扩散`() {
        val ids = GalleryPrefetchPolicy.prefetchIds(
            current = 2,
            total = 6,
            idsByPage = listOf(10L, 20L, 30L, 40L, 50L, 60L),
            loadedIds = emptySet(),
            radius = 2,
        )
        assertEquals(listOf(30L, 20L, 40L, 10L, 50L), ids)
    }

    @Test
    fun `非正的mediaId被过滤`() {
        val ids = GalleryPrefetchPolicy.prefetchIds(
            current = 1,
            total = 3,
            idsByPage = listOf(0L, 10L, -5L),
            loadedIds = emptySet(),
        )
        // 0 与 -5 不是合法 fileId，不能拿去请求
        assertEquals(listOf(10L), ids)
    }

    @Test
    fun `非法入参不崩溃`() {
        assertTrue(GalleryPrefetchPolicy.prefetchIds(0, 0, emptyList(), emptySet()).isEmpty())
        assertTrue(GalleryPrefetchPolicy.prefetchIds(3, 5, emptyList(), emptySet()).isEmpty())
        // 页码越界按边界夹紧
        assertEquals(
            listOf(10L, 20L),
            GalleryPrefetchPolicy.prefetchIds(-9, 5, fivePages, emptySet()),
        )
        assertEquals(
            listOf(50L, 40L),
            GalleryPrefetchPolicy.prefetchIds(99, 5, fivePages, emptySet()),
        )
        // total 大于实际数据长度时以数据长度为准
        assertEquals(
            listOf(50L, 40L),
            GalleryPrefetchPolicy.prefetchIds(9, 999, fivePages, emptySet()),
        )
        // 负半径按 0 处理
        assertEquals(
            listOf(30L),
            GalleryPrefetchPolicy.prefetchIds(2, 5, fivePages, emptySet(), radius = -3),
        )
    }

    @Test
    fun `同一张图失败只提示一次`() {
        val notified = mutableSetOf<Long>()
        assertTrue(GalleryPrefetchPolicy.shouldNotifyFailure(10L, notified))
        notified.add(10L)
        assertFalse(GalleryPrefetchPolicy.shouldNotifyFailure(10L, notified))
        // 另一张图仍需提示
        assertTrue(GalleryPrefetchPolicy.shouldNotifyFailure(20L, notified))
        // 非法 id 不提示
        assertFalse(GalleryPrefetchPolicy.shouldNotifyFailure(0L, notified))
    }
}
