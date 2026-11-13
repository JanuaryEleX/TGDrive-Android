package com.yg.tgdrive.ui.player

/**
 * 临时「照抄现状」实现，仅用于让新测试先红：确认测试真的能抓到
 * 「播放恢复后错误层仍滞留」「重试提示不清」这两个缺陷。
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
