package com.yg.tgdrive.download

/** 待实现：落盘连续长度扫描。先红后绿——此处故意复刻旧的「有非零字节即整窗放行」。 */
object ReadableScan {
    const val MIN_FILL_RUN = 1024

    fun runLength(bytes: ByteArray, from: Int, limit: Int, minFillRun: Int = MIN_FILL_RUN): Int {
        if (from < 0 || limit <= 0 || from >= bytes.size) return 0
        val end = minOf(bytes.size.toLong(), from.toLong() + limit.toLong()).toInt()
        if (end <= from) return 0
        var anyNonZero = false
        for (i in from until end) {
            if (bytes[i] != 0.toByte()) anyNonZero = true
        }
        return if (anyNonZero) end - from else 0
    }
}
