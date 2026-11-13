package com.yg.tgdrive.download

/** 待实现的缓存占用统计（先红后绿：当前实现故意只做最直白的求和）。 */
internal object CacheUsage {
    fun isEntry(name: String): Boolean = true

    fun totalBytes(entries: List<Pair<String, Long>>): Long = entries.sumOf { it.second }

    fun percentOf(used: Long, capBytes: Long): Int = 0
}
