package com.yg.tdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRenderPolicyTest {
    @Test
    fun `three columns use compact decoration while two columns keep regular layout`() {
        assertEquals(PosterRenderPolicy.Decoration.REGULAR, PosterRenderPolicy.decoration(2))
        assertEquals(PosterRenderPolicy.Decoration.COMPACT, PosterRenderPolicy.decoration(3))
    }

    @Test
    fun `compact decoration removes the per poster gradient without removing duration or details`() {
        val compact = PosterRenderPolicy.decoration(3)
        assertTrue(!compact.drawGradient)
        assertTrue(compact.showDuration)
        assertTrue(compact.showDetails)
    }

    @Test
    fun `regular decoration retains the existing poster look`() {
        val regular = PosterRenderPolicy.decoration(2)
        assertTrue(regular.drawGradient)
        assertTrue(regular.showDuration)
        assertTrue(regular.showDetails)
    }
}
