package com.yg.tgdrive.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalScanPolicyTest {
    @Test fun `new drive without checkpoint needs initial full scan`() {
        assertTrue(IncrementalScanPolicy.needsFullScan(0, ScanState.IDLE))
    }

    @Test fun `completed drive with checkpoint uses incremental scan`() {
        assertFalse(IncrementalScanPolicy.needsFullScan(120, ScanState.DONE))
    }

    @Test fun `unfinished initial scan needs continuation rather than marking done`() {
        assertTrue(IncrementalScanPolicy.needsFullScan(25, ScanState.ERROR))
    }

    @Test fun `successful incremental scan advances checkpoint to newest`() {
        assertEquals(150L, IncrementalScanPolicy.checkpointAfterSuccess(120, 150))
    }

    @Test fun `failed incremental scan retains previous checkpoint`() {
        assertEquals(120L, IncrementalScanPolicy.checkpointAfterFailure(120, 50))
    }

    @Test fun `already known message ends incremental traversal`() {
        assertTrue(IncrementalScanPolicy.reachedOldMessages(listOf(150L, 149L, 120L), 120))
        assertFalse(IncrementalScanPolicy.reachedOldMessages(listOf(150L, 149L), 120))
    }
}
