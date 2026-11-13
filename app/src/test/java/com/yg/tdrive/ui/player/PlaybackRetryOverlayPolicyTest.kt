package com.yg.tgdrive.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackRetryOverlayPolicyTest {
    @Test fun readyClearsRetryNotice() {
        assertNull(PlaybackRetryOverlayPolicy.afterReady("播放中断，正在重试 1/2"))
    }

    @Test fun readyKeepsUnrelatedHudNotice() {
        assertEquals("快进 10 秒", PlaybackRetryOverlayPolicy.afterReady("快进 10 秒"))
    }

    @Test fun terminalErrorClearsRetryNotice() {
        assertNull(PlaybackRetryOverlayPolicy.afterTerminalError("播放中断，正在重试 2/2"))
    }
}
