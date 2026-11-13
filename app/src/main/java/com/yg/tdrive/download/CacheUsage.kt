package com.yg.tgdrive.download

/**
 * LRU 缓存占用统计（设置页「已缓存」显示）。
 *
 * 缓存目录里除了真正的整文件缓存，还混有两类非数据文件：
 * - `<key>.at` 访问序标记（几十字节）
 * - `<key>.tmp` 拷贝中的临时文件
 * 两者都不能算进「已缓存」，否则数字会虚高、清空后也可能残留非零值。
 */
internal object CacheUsage {
    private const val MARKER_SUFFIX = ".at"
    private const val TEMP_SUFFIX = ".tmp"

    fun isEntry(name: String): Boolean =
        !name.endsWith(MARKER_SUFFIX) && !name.endsWith(TEMP_SUFFIX)

    fun totalBytes(entries: List<Pair<String, Long>>): Long =
        entries.filter { isEntry(it.first) }.sumOf { it.second.coerceAtLeast(0L) }

    /** 已用占上限的百分比（0..100）；上限未知/为 0 时返回 0。 */
    fun percentOf(used: Long, capBytes: Long): Int {
        if (capBytes <= 0L) return 0
        return ((used.coerceAtLeast(0L) * 100L) / capBytes).coerceIn(0L, 100L).toInt()
    }
}
