package com.yg.tgdrive.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「我的收藏」开关规则回归。
 *
 * 关键约束（用户明确要求）：这一项必须在**设置页驱动扫描处由用户勾选**才启用，
 * **默认不启用** —— 不预置驱动器、不自动挂载、不触发首扫。
 */
class SavedMessagesPolicyTest {

    private val me = 777_000L

    private fun chat(id: Long, title: String, type: String = "chatTypeSupergroup") =
        SelectableChat(chatId = id, title = title, type = type)

    // ---- 默认值 ----

    @Test
    fun `默认不启用`() {
        assertTrue(!SavedMessagesPolicy.DEFAULT_ENABLED)
    }

    @Test
    fun `默认状态下 planToggle 未调用即无动作`() {
        // 未登录（myUserId=0）时任何操作都不得落库
        assertEquals(
            SavedMessagesPolicy.TogglePlan.Unavailable,
            SavedMessagesPolicy.planToggle(wantEnabled = true, myUserId = 0L),
        )
        assertEquals(
            SavedMessagesPolicy.TogglePlan.Unavailable,
            SavedMessagesPolicy.planToggle(wantEnabled = false, myUserId = 0L),
        )
    }

    // ---- 识别与命名 ----

    @Test
    fun `isSavedMessages 只在 myUserId 已知且相等时成立`() {
        assertTrue(SavedMessagesPolicy.isSavedMessages(me, me))
        // myUserId 未知时绝不把 chatId=0 误判成收藏
        assertTrue(!SavedMessagesPolicy.isSavedMessages(0L, 0L))
        assertTrue(!SavedMessagesPolicy.isSavedMessages(me + 1, me))
    }

    @Test
    fun `resolveTitle 收藏恒显示我的收藏`() {
        assertEquals("我的收藏", SavedMessagesPolicy.resolveTitle(me, me, "张三"))
        assertEquals("我的收藏", SavedMessagesPolicy.resolveTitle(me, me, null))
        // 普通聊天沿用原标题，空标题退化为 Chat {id}
        assertEquals("影视仓库", SavedMessagesPolicy.resolveTitle(5L, me, "影视仓库"))
        assertEquals("Chat 5", SavedMessagesPolicy.resolveTitle(5L, me, "   "))
    }

    @Test
    fun `resolveOrderIdx 收藏恒为 -1 排在所有普通驱动器之前`() {
        assertEquals(-1, SavedMessagesPolicy.resolveOrderIdx(me, me, fallback = 3))
        assertEquals(3, SavedMessagesPolicy.resolveOrderIdx(5L, me, fallback = 3))
        assertTrue(SavedMessagesPolicy.ORDER_IDX < 0)
    }

    // ---- 开关决策 ----

    @Test
    fun `勾选后返回 Enable 且固定标题与置顶序号`() {
        val plan = SavedMessagesPolicy.planToggle(wantEnabled = true, myUserId = me)
        assertTrue(plan is SavedMessagesPolicy.TogglePlan.Enable)
        plan as SavedMessagesPolicy.TogglePlan.Enable
        assertEquals(me, plan.chatId)
        assertEquals("我的收藏", plan.title)
        assertEquals(-1, plan.orderIdx)
        // 置顶语义：序号小于任何普通驱动器
        assertTrue(plan.orderIdx < 0)
    }

    @Test
    fun `取消勾选返回 Disable 且指向同一个 chatId`() {
        val plan = SavedMessagesPolicy.planToggle(wantEnabled = false, myUserId = me)
        assertTrue(plan is SavedMessagesPolicy.TogglePlan.Disable)
        assertEquals(me, (plan as SavedMessagesPolicy.TogglePlan.Disable).chatId)
    }

    @Test
    fun `重复勾选决策幂等 每次都是同一个 Enable`() {
        val a = SavedMessagesPolicy.planToggle(true, me)
        val b = SavedMessagesPolicy.planToggle(true, me)
        assertEquals(a, b)
    }

    // ---- 副标题 ----

    @Test
    fun `subtitle 收藏用专属文案其余按类型翻译`() {
        val saved = SelectableChat(
            chatId = me,
            title = SavedMessagesPolicy.TITLE,
            type = SavedMessagesPolicy.CHAT_TYPE,
            isSavedMessages = true,
        )
        assertEquals(SavedMessagesPolicy.SUBTITLE, SavedMessagesPolicy.subtitle(saved))
        assertEquals(
            "私有频道",
            SavedMessagesPolicy.subtitle(chat(5L, "频道", "chatTypePrivateChannel")),
        )
        assertEquals("超级群组", SavedMessagesPolicy.subtitle(chat(6L, "群", "chatTypeSupergroup")))
        assertEquals("普通群组", SavedMessagesPolicy.subtitle(chat(7L, "群", "chatTypeBasicGroup")))
        // 未知类型原样透出，空类型不显示空白
        assertEquals("chatTypeSecret", SavedMessagesPolicy.subtitle(chat(8L, "x", "chatTypeSecret")))
        assertEquals("聊天", SavedMessagesPolicy.subtitle(chat(9L, "x", "")))
    }
}
