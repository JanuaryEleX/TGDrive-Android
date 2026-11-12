package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLayerPolicyTest {
    @Test fun activeTabIsOnTopOfEveryOtherPage() {
        listOf(0, 1, 2, 3).forEach { other ->
            assertTrue(
                "活动页应高于 $other",
                TabLayerPolicy.zIndex(2, active = 2) > TabLayerPolicy.zIndex(other, active = 2),
            )
        }
    }

    @Test fun inactivePagesShareTheBaseLayer() {
        assertEquals(TabLayerPolicy.zIndex(0, active = 2), TabLayerPolicy.zIndex(3, active = 2), 0f)
    }

    @Test fun onlyActiveTabReceivesTouches() {
        assertTrue(TabLayerPolicy.isInteractive(2, active = 2))
        assertFalse(TabLayerPolicy.isInteractive(0, active = 2))
    }
}
