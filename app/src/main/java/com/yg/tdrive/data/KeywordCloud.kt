package com.yg.tgdrive.data

import java.util.Locale

/** 从本地索引中的显示名提取候选词；不联网、不采集用户的搜索历史。 */
object KeywordCloud {
    data class Word(val text: String, val count: Int)

    private val parts = Regex("[\\p{IsHan}]{2,}|[A-Za-z]{3,}")
    private val noise = setOf("视频", "图片", "照片", "文件", "原图", "image", "photo", "video", "screenshot", "screen", "shot", "img", "vid", "mp4", "mkv", "mov", "jpg", "jpeg", "png", "webp")
    private val extensions = Regex("\\.(mp4|mkv|mov|avi|webm|jpg|jpeg|png|webp|gif|heic)$", RegexOption.IGNORE_CASE)
    private val han = Regex("[\\p{IsHan}]{2,}")

    fun fromNames(names: List<String>, limit: Int = 18): List<Word> {
        if (limit <= 0) return emptyList()
        val counts = HashMap<String, Int>()
        names.forEach { name ->
            val base = name.replace(extensions, "")
            val distinct = parts.findAll(base).flatMap { match ->
                val token = match.value
                if (han.matches(token)) {
                    // 连续中文标题按相邻双字抽取，能在「海边旅行」与「海边日落」中提取“海边”。
                    (0 until token.length - 1).asSequence().map { token.substring(it, it + 2) }
                } else sequenceOf(token.lowercase(Locale.ROOT))
            }.filter { it.lowercase(Locale.ROOT) !in noise }.toSet()
            distinct.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.entries.asSequence()
            .filter { it.value >= 2 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Word(it.key, it.value) }.toList()
    }
}
