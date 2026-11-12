package com.yg.tgdrive.data

/**
 * 搜索查询策略：决定走 FTS5 还是 LIKE 子串匹配。
 *
 * 背景：FTS5 的 unicode61 分词器会把连续汉字整段当成**一个 token**，
 * 所以 `"视"*` 这种前缀查询匹配不到「我的视频」——用户搜单个汉字时结果为空。
 * 中文（含单字）与单字符查询统一改走 `LIKE '%q%'` 子串匹配；
 * 拉丁字母仍用 FTS 前缀查询，保留索引性能。
 */
object SearchQueryPolicy {

    private val HAN = Regex("[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]")

    /** 查询里是否含汉字。 */
    fun containsHan(query: String): Boolean = HAN.containsMatchIn(query)

    /** 是否应使用 LIKE 子串匹配（中文或单字符）。 */
    fun useLike(query: String): Boolean {
        val q = query.trim()
        return containsHan(q) || q.length < 2
    }

    /** 构造 LIKE 模式，转义 %、_ 与反斜杠（配合 SQL 的 ESCAPE '\'）。 */
    fun likePattern(query: String): String {
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /** 构造 FTS5 查询：每个空白分隔 token → "token"*，剥掉内嵌引号防语法破坏。 */
    fun ftsQuery(query: String): String = query.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { "\"${it.replace("\"", "")}\"*" }

    /** LIKE 模式的匹配结果（用于纯逻辑测试；SQL 与之一致）。 */
    fun matchesLike(name: String, query: String): Boolean =
        name.contains(query.trim(), ignoreCase = true)
}
