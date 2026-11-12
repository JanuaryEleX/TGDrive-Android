package com.yg.tgdrive.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarPolicyTest {
    @Test fun videoAndImageEntriesOpenFilterOnLongPress() {
        assertTrue(BottomBarPolicy.supportsFilter(BottomBarPolicy.VIDEOS))
        assertTrue(BottomBarPolicy.supportsFilter(BottomBarPolicy.IMAGES))
    }

    @Test fun recentAndFavoritesHaveNoFilter() {
        assertFalse(BottomBarPolicy.supportsFilter(BottomBarPolicy.RECENT))
        assertFalse(BottomBarPolicy.supportsFilter(BottomBarPolicy.FAVORITES))
    }
}
