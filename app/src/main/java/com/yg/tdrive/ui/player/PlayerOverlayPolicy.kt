package com.yg.tgdrive.ui.player

/**
 * 临时现状实现仅供红灯测试：onReady 不清旧错误层 / 重试 HUD。
 * 替换为状态机后重新执行所有单测。
 */
internal data class PlayerOverlay(
    val hud: String? = null,
    val error: String? = null,
)

internal object PlayerOverlayPolicy {
    internal const val RETRY_HUD_PREFIX = "播放中断，正在重试 "
    fun retryHud(attempt: Int, max: Int): String = "$RETRY_HUD_PREFIX$attempt/$max"
    fun onReady(current: PlayerOverlay): PlayerOverlay = current
    fun onPrepareStart(current: PlayerOverlay): PlayerOverlay = current.copy(error = null)
    fun onTerminalError(current: PlayerOverlay, message: String): PlayerOverlay =
        current.copy(error = message)
}

internal object PlaybackRetryOverlayPolicy {
    private const val RETRY_PREFIX = "播放中断，正在重试 "
    fun afterReady(current: String?): String? =
        current?.takeUnless { it.startsWith(RETRY_PREFIX) }
    fun afterTerminalError(current: String?): String? = afterReady(current)
}
