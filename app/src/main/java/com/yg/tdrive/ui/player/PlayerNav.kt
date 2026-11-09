package com.yg.tgdrive.ui.player

import android.content.Context
import android.content.Intent
import com.yg.tgdrive.ui.common.MediaEntity

/**
 * 跳转播放页（PlayerActivity 在 ui.player 包，本文件仅提供 UI 层调用的辅助函数）。
 * Intent extra: mediaId: Long —— PlayerActivity 自行查库取元信息与 fresh file_id。
 */
fun openPlayer(context: Context, mediaId: Long) {
    val intent = Intent(context, com.yg.tgdrive.ui.player.PlayerActivity::class.java)
    intent.putExtra("mediaId", mediaId)
    context.startActivity(intent)
}

/** MediaEntity 便捷重载。 */
fun openPlayer(context: Context, media: MediaEntity) = openPlayer(context, media.id)
