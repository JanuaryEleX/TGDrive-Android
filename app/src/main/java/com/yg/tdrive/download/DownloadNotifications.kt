package com.yg.tgdrive.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 离线下载通知（完成 / 失败）。
 *
 * - 渠道 id = "download"（Android 8+ 必须建渠道）。
 * - 点击通知打开应用；额外带 [EXTRA_OPEN_ROUTE]="download"，供 MainActivity/AppRoot
 *   后续识别并直接路由到「下载管理」页（当前 MainActivity 未消费该 extra，属 Lead 接线项）。
 * - Android 13+ 需要 POST_NOTIFICATIONS 运行时权限；未授权时 notify 静默失败（runCatching 兜底）。
 */
class DownloadNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "download"
        private const val CHANNEL_NAME = "下载"
        const val EXTRA_OPEN_ROUTE = "open_route"
        const val ROUTE_DOWNLOAD = "download"
        private const val ID_BASE = 8200
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "离线下载完成 / 失败提醒"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun notifyCompleted(taskId: Long, fileName: String) {
        post(
            taskId = taskId,
            title = "下载完成",
            text = "已保存：$fileName",
            icon = android.R.drawable.stat_sys_download_done,
        )
    }

    fun notifyFailed(taskId: Long, fileName: String, error: String?) {
        post(
            taskId = taskId,
            title = "下载失败",
            text = error?.takeIf { it.isNotBlank() } ?: "「$fileName」下载失败",
            icon = android.R.drawable.stat_sys_warning,
        )
    }

    private fun post(taskId: Long, title: String, text: String, icon: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Android 13+ 无权限时 SecurityException；用 runCatching 静默降级
        runCatching {
            NotificationManagerCompat.from(context)
                .notify((ID_BASE + taskId).toInt(), notification)
        }
    }

    private fun contentIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        launch.putExtra(EXTRA_OPEN_ROUTE, ROUTE_DOWNLOAD)
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
