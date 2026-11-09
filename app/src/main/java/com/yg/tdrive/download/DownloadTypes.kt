package com.yg.tgdrive.download

import java.io.IOException

/**
 * ensureRange 返回值（SPEC §4.3 / 任务书契约）：
 * - partialPath：可读数据所在的本地文件（TDLib partial / TDLib 最终文件 / LRU 完整缓存）。
 * - globalOffset：该文件「字节 0」对应的全局偏移。
 *   调用方读绝对位置 pos：RandomAccessFile(partialPath).seek(pos - globalOffset)。
 */
data class PartialRef(
    val partialPath: String,
    val globalOffset: Long,
)

/**
 * 下载层失败（SPEC §4.3 容错：chunk 重试耗尽 / 等待超时 / 窗口竞态）。
 * 继承 IOException —— TdDataSource 直接上抛，ExoPlayer 按加载错误处理并触发错误覆盖层。
 */
class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

