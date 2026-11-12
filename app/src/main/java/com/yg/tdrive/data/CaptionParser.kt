package com.yg.tgdrive.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从 TDLib 的 `message.content` 里取相册/媒体描述正文（caption）。
 *
 * 为什么单独抽出来：caption 在 TDLib 返回里**有两个位置**，位置判断错会静默丢字，
 * 表现为「回填跑了 80 页但带描述消息=0、0 条改名」，非常难从结果上看出来。抽成纯函数
 * 才能用单测把两种真实结构钉死。
 *
 * 位置 1（本机实测 `getChatHistory` 的 messageVideo，caption 与 video 平级）：
 * ```
 * {"@type":"messageVideo","video":{...},"caption":{"text":"描述"},
 *  "show_caption_above_media":false,"has_spoiler":false,"is_secret":false}
 * ```
 * 位置 2（老版本 / 部分消息类型，caption 在媒体对象内部）：
 * ```
 * {"@type":"messageVideo","video":{...,"caption":{"text":"描述"}}}
 * ```
 */
object CaptionParser {

    /** 支持带 caption 的媒体 content 类型 → 媒体对象字段名。 */
    private val MEDIA_FIELD = mapOf(
        "messageVideo" to "video",
        "messagePhoto" to "photo",
        "messageDocument" to "document",
        "messageAnimation" to "animation",
    )

    /**
     * @param content TDLib 消息的 `content` 对象（可为 null，非媒体消息直接返回 null）
     * @return caption 纯文本；没有 caption 或为空串时返回 null（调用方按「无描述」处理）
     */
    fun captionOf(content: JsonObject?): String? {
        if (content == null) return null
        // 位置 1：content 顶层（新版 TDLib）
        textOf(content["caption"] as? JsonObject)?.let { return it }
        // 位置 2：媒体对象内部
        val type = (content["@type"] as? JsonPrimitive)?.content ?: return null
        val field = MEDIA_FIELD[type] ?: return null
        val media = content[field] as? JsonObject ?: return null
        return textOf(media["caption"] as? JsonObject)
    }

    /** formattedText → 纯文本；空白视为没有。 */
    private fun textOf(formatted: JsonObject?): String? {
        val text = (formatted?.get("text") as? JsonPrimitive)?.content
        return text?.takeIf { it.isNotBlank() }
    }
}
