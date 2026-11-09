package com.yg.tgdrive.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时长角标：ms → "1:23:45" / "12:34"；空值返回空串。 */
fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** 大小角标：bytes → "1.23 GB" / "345.6 MB"。 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

/** 收录日期：epoch 秒 → "yyyy-MM-dd"。 */
fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochSeconds * 1000))
}
