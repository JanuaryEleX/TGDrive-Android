package com.yg.tgdrive.data

/**
 * 「我的收藏」（Telegram Saved Messages）在驱动器体系里的统一规则。
 *
 * TDLib 里 Saved Messages 就是「和自己对话」的那条 chat，chat_id 等于当前登录用户
 * 自身 user id（getMe 取），所以它天然会混在一堆聊天里、标题是本机账号名，
 * 在勾选列表里既认不出来也不在顶部。这里把三条规则收口成纯函数：
 *
 * 1. [pinFirst]  —— 勾选列表恒定把「我的收藏」放第一位（不存在则补一条）
 * 2. [resolveTitle] —— 显示名统一成「我的收藏」，不显示账号名
 * 3. [resolveOrderIdx] —— 入库时 order_idx 恒为 -1，驱动器列表/海报墙默认选中它
 *
 * 全是无副作用的纯函数，便于单测覆盖；调用方（DriveManager / 引导页）只负责取
 * myUserId 并落库。
 */
object SavedMessagesPolicy {

    /** 列表与驱动器列表里展示的名字。 */
    const val TITLE = "我的收藏"

    /** 勾选页副标题。 */
    const val SUBTITLE = "Saved Messages · 与自己对话"

    /** 排序键：比任何普通驱动器（0,1,2…）都小，保证置顶。 */
    const val ORDER_IDX = -1

    /** Saved Messages 的 TDLib chat type（和自己对话 = 普通私聊）。 */
    const val CHAT_TYPE = "chatTypePrivate"

    /** myUserId 未知（0）时不判定，避免把所有 chatId=0 误认成收藏。 */
    fun isSavedMessages(chatId: Long, myUserId: Long): Boolean =
        myUserId != 0L && chatId == myUserId

    /** TDLib chat type → 中文副标题（勾选列表用；收藏走 [SUBTITLE]）。 */
    fun typeLabel(type: String): String = when (type) {
        "chatTypePrivateChannel" -> "私有频道"
        "chatTypeSupergroup" -> "超级群组"
        "chatTypeBasicGroup" -> "普通群组"
        "chatTypePrivate" -> "私聊"
        else -> type.ifBlank { "聊天" }
    }

    /** 勾选列表副标题：收藏用专属文案，其余按类型翻译。 */
    fun subtitle(chat: SelectableChat): String =
        if (chat.isSavedMessages) SUBTITLE else typeLabel(chat.type)

    /** 收藏恒显示 [TITLE]；其余沿用 Telegram 标题，空标题退化为 Chat {id}。 */
    fun resolveTitle(chatId: Long, myUserId: Long, raw: String?): String =
        if (isSavedMessages(chatId, myUserId)) {
            TITLE
        } else {
            raw?.takeIf { it.isNotBlank() } ?: "Chat $chatId"
        }

    /** 收藏恒为 [ORDER_IDX]（置顶），其余用调用方给的「末尾」序号。 */
    fun resolveOrderIdx(chatId: Long, myUserId: Long, fallback: Int): Int =
        if (isSavedMessages(chatId, myUserId)) ORDER_IDX else fallback

    /**
     * 把收藏插到列表首位。
     *
     * - 已在列表里：抽出来换成统一标题后置顶，不产生重复行
     * - 不在列表里（getChats 没返回 / 被 limit 截断）：补一条
     * - myUserId 未知：原样返回，不做猜测
     */
    fun pinFirst(items: List<SelectableChat>, myUserId: Long): List<SelectableChat> {
        if (myUserId == 0L) return items
        val existing = items.firstOrNull { it.chatId == myUserId }
        val rest = items.filter { it.chatId != myUserId }
        val saved = SelectableChat(
            chatId = myUserId,
            title = TITLE,
            type = existing?.type?.takeIf { it.isNotBlank() } ?: CHAT_TYPE,
            isSavedMessages = true,
        )
        return listOf(saved) + rest
    }
}
