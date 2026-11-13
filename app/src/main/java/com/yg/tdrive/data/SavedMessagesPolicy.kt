package com.yg.tgdrive.data

/**
 * 「我的收藏」（Telegram Saved Messages）在驱动器体系里的统一规则。
 *
 * TDLib 里 Saved Messages 就是「和自己对话」的那条 chat，chat_id 等于当前登录用户
 * 自身 user id（getMe 取），所以它天然会混在一堆聊天里、标题是本机账号名，
 * 在勾选列表里既认不出来也不在顶部。这里把规则收口成纯函数：
 *
 * 1. [isSavedMessages] —— 识别（chatId == 自己 user id）
 * 2. [resolveTitle]    —— 显示名统一成「我的收藏」，不显示账号名
 * 3. [resolveOrderIdx] —— 启用后 order_idx 恒为 -1，驱动器列表里置顶
 * 4. [planToggle]      —— 设置页开关的决策：默认**不启用**，勾选才建驱动器
 *
 * 全是无副作用的纯函数，便于单测覆盖；调用方（DriveManager / 设置页）只负责取
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

    /**
     * 默认是否启用。
     *
     * 用户要求：这一项必须由用户在**设置页驱动扫描处主动勾选**才生效，
     * 不能默认出现在任何人的驱动器列表里（不预置、不自动挂载、不首扫）。
     */
    const val DEFAULT_ENABLED = false

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
     * 设置页开关的落库决策（纯函数，无副作用）。
     *
     * @param wantEnabled 用户点击后的目标状态（勾选=true / 取消=false）
     * @param myUserId    getMe 拿到的自身 id；0 表示还没登录好
     */
    fun planToggle(wantEnabled: Boolean, myUserId: Long): TogglePlan = when {
        // 登录态没就绪时既不能建也不能删，UI 应禁用开关
        myUserId == 0L -> TogglePlan.Unavailable
        !wantEnabled -> TogglePlan.Disable(chatId = myUserId)
        else -> TogglePlan.Enable(
            chatId = myUserId,
            title = TITLE,
            orderIdx = ORDER_IDX,
        )
    }

    /** [planToggle] 的结果。 */
    sealed interface TogglePlan {
        /** getMe 未就绪，本轮什么也不做。 */
        data object Unavailable : TogglePlan

        /** 取消勾选：移除该驱动器（连同本地索引），云端消息不动。 */
        data class Disable(val chatId: Long) : TogglePlan

        /** 勾选启用：新建驱动器，固定标题与置顶序号。 */
        data class Enable(
            val chatId: Long,
            val title: String,
            val orderIdx: Int,
        ) : TogglePlan
    }
}
