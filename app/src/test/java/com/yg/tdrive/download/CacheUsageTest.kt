package com.yg.tgdrive.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheUsageTest {
    @Test fun countsOnlyRealCacheFiles() {
        val entries = listOf(
            "AgAD_video1" to 5_000L,
            "AgAD_video2" to 3_000L,
        )
        assertEquals(8_000L, CacheUsage.totalBytes(entries))
    }

    @Test fun skipsAccessMarkersAndTempFiles() {
        val entries = listOf(
            "AgAD_video1" to 5_000L,
            "AgAD_video1.at" to 13L,
            "AgAD_video2.tmp" to 900_000L,
        )
        assertEquals(5_000L, CacheUsage.totalBytes(entries))
    }

    @Test fun markerAndTempAreNotEntries() {
        assertTrue(CacheUsage.isEntry("AgAD_x"))
        assertFalse(CacheUsage.isEntry("AgAD_x.at"))
        assertFalse(CacheUsage.isEntry("AgAD_x.tmp"))
    }

    @Test fun emptyCacheIsZero() {
        assertEquals(0L, CacheUsage.totalBytes(emptyList()))
    }

    @Test fun percentIsClampedAndSafeForZeroCap() {
        assertEquals(0, CacheUsage.percentOf(0L, 0L))
        assertEquals(50, CacheUsage.percentOf(2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024))
        assertEquals(100, CacheUsage.percentOf(9L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024))
    }
}
