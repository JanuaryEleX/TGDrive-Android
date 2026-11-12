package com.yg.tdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRenderPolicyTest {
    @Test
    fun `three columns drop the per poster gradient to cut one draw per cell`() {
        assertTrue(!PosterRenderPolicy.decoration(3).drawGradient)
        assertTrue(!PosterRenderPolicy.decoration(4).drawGradient)
    }

    @Test
    fun `two columns keep the existing poster look`() {
        assertTrue(PosterRenderPolicy.decoration(2).drawGradient)
        assertTrue(PosterRenderPolicy.decoration(1).drawGradient)
    }

    @Test
    fun `decoration is decided only by column count so callers stay cheap`() {
        assertEquals(PosterRenderPolicy.decoration(2), PosterRenderPolicy.decoration(2))
    }
}
