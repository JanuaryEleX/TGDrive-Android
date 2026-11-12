package com.yg.tdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterSizingTest {

    @Test
    fun `三列解码尺寸明显小于两列`() {
        val two = PosterSizing.decodePx(screenWidthDp = 400, columns = 2, density = 2.5f)
        val three = PosterSizing.decodePx(screenWidthDp = 400, columns = 3, density = 2.5f)
        assertTrue("三列($three) 应小于两列($two)", three < two)
    }

    @Test
    fun `列数越多单格像素越小`() {
        val sizes = (2..5).map { PosterSizing.decodePx(400, it, 2.5f) }
        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun `像素随屏幕密度线性放大`() {
        val mdpi = PosterSizing.decodePx(400, 3, 1f)
        val hdpi = PosterSizing.decodePx(400, 3, 2f)
        assertEquals(mdpi * 2, hdpi)
    }

    @Test
    fun `超出范围时被夹紧到上下限`() {
        assertEquals(180, PosterSizing.decodePx(screenWidthDp = 100, columns = 6, density = 1f))
        assertEquals(640, PosterSizing.decodePx(screenWidthDp = 3000, columns = 1, density = 3f))
    }

    @Test
    fun `非法入参不崩溃`() {
        // 列数为 0 或负数时按 1 列处理；密度为 0 时按 1x 处理，不能出现 0 或负数尺寸。
        assertTrue(PosterSizing.decodePx(400, 0, 2f) > 0)
        assertTrue(PosterSizing.decodePx(400, 3, 0f) > 0)
    }
}
