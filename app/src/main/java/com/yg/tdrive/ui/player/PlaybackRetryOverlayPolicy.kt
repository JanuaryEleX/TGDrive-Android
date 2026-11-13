package com.yg.tgdrive.ui.player

/** Retry notices are temporary; unrelated gesture HUD messages must survive a READY callback. */
internal object PlaybackRetryOverlayPolicy {
    private const val RETRY_PREFIX = "播放中断，正在重试 "

    fun afterReady(current: String?): String? =
        current?.takeUnless { it.startsWith(RETRY_PREFIX) }

    fun afterTerminalError(current: String?): String? = afterReady(current)
}