package com.yg.tgdrive.ui.player

/** 错误层与中央 HUD 的状态转换，避免播放已恢复而旧错误仍遮住画面。 */
internal data class PlayerOverlay(
    val hud: String? = null,
    val error: String? = null,
)

internal object PlayerOverlayPolicy {
    private const val RETRY_HUD_PREFIX = "播放中断，正在重试 "

    fun retryHud(attempt: Int, max: Int): String = "$RETRY_HUD_PREFIX$attempt/$max"

    private fun withoutRetryHud(hud: String?): String? =
        hud?.takeUnless { it.startsWith(RETRY_HUD_PREFIX) }

    /** ExoPlayer 真正恢复就绪时，不再显示先前的错误层。 */
    fun onReady(current: PlayerOverlay): PlayerOverlay =
        PlayerOverlay(hud = withoutRetryHud(current.hud))

    fun onPrepareStart(current: PlayerOverlay): PlayerOverlay = onReady(current)

    fun onTerminalError(current: PlayerOverlay, message: String): PlayerOverlay =
        PlayerOverlay(hud = withoutRetryHud(current.hud), error = message)
}
