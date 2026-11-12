package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLayerPolicyTest {
    @Test fun activeTabIsDrawnLast() {
        val order = TabLayerPolicy.drawOrder(listOf(2, 0, 1), active = 0)
        assertEquals(0, order.last())
        assertEquals(listOf(2, 1, 0), order)
    }

    @Test fun unvisitedActiveTabIsNotAddedBlindly() {
        assertEquals(listOf<Int>(), TabLayerPolicy.drawOrder(listOf(2), active = 3))
    }

    @Test fun onlyActiveTabReceivesTouches() {
        assertTrue(TabLayerPolicy.isInteractive(2, active = 2))
        assertFalse(TabLayerPolicy.isInteractive(0, active = 2))
    }
}
