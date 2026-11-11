package com.yg.tgdrive.ui.player

import android.content.Context
import android.content.Intent
import com.yg.tgdrive.ui.common.MediaEntity

/**
 * 跳转播放页。
 *
 * [queue] 是发起播放时那一刻的列表顺序（网格已加载项 / 搜索结果），
 * 播放器只按它做手动上下集，绝不自动连播；传空数组则不显示上下集。
 * Intent extra: mediaId / queue / index。
 */
fun openPlayer(
    context: Context,
    mediaId: Long,
    queue: LongArray = LongArray(0),
    index: Int = -1,
) {
    val intent = Intent(context, PlayerActivity::class.java)
    intent.putExtra(PlayerActivity.EXTRA_MEDIA_ID, mediaId)
    if (queue.isNotEmpty()) {
        intent.putExtra(PlayerActivity.EXTRA_QUEUE, queue)
        intent.putExtra(PlayerActivity.EXTRA_INDEX, index)
    }
    context.startActivity(intent)
}

/** MediaEntity 便捷重载。 */
fun openPlayer(
    context: Context,
    media: MediaEntity,
    queue: LongArray = LongArray(0),
    index: Int = -1,
) = openPlayer(context, media.id, queue, index)
