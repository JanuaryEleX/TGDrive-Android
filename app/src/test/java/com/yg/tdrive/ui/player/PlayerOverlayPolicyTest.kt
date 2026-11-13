package com.yg.tgdrive.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerOverlayPolicyTest {
    @Test fun readyClearsStaleErrorOverlay() {
        val after = PlayerOverlayPolicy.onReady(
            PlayerOverlay(hud = null, error = "播放失败：ERROR_CODE_IO_UNSPECIFIED（已自动重试 2 次）")
        )
        assertNull(after.error)
    }

    @Test fun readyClearsRetryNotice() {
        val after = PlayerOverlayPolicy.onReady(
            PlayerOverlay(hud = PlayerOverlayPolicy.retryHud(1, 2), error = null)
        )
        assertNull(after.hud)
        assertNull(after.error)
    }

    @Test fun readyKeepsGestureHud() {
        val after = PlayerOverlayPolicy.onReady(PlayerOverlay(hud = "快进 10 秒", error = null))
        assertEquals("快进 10 秒", after.hud)
    }

    @Test fun terminalErrorShowsMessageAndDropsRetryNotice() {
        val after = PlayerOverlayPolicy.onTerminalError(
            PlayerOverlay(hud = PlayerOverlayPolicy.retryHud(2, 2), error = null),
            "播放失败：ERROR_CODE_IO_UNSPECIFIED（已自动重试 2 次）",
        )
        assertNull(after.hud)
        assertEquals("播放失败：ERROR_CODE_IO_UNSPECIFIED（已自动重试 2 次）", after.error)
    }

    @Test fun prepareStartClearsBothLayers() {
        val after = PlayerOverlayPolicy.onPrepareStart(
            PlayerOverlay(hud = PlayerOverlayPolicy.retryHud(2, 2), error = "媒体不存在 (id=7)")
        )
        assertNull(after.hud)
        assertNull(after.error)
    }
}
