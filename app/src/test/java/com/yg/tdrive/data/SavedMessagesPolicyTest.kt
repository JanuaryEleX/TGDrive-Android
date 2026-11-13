package com.yg.tgdrive.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「我的收藏」置顶规则回归：确保 Saved Messages 恒在勾选列表首位、
 * 标题统一为「我的收藏」、入库 order_idx 恒为 -1（驱动器列表/海报墙默认选中）。
 */
class SavedMessagesPolicyTest {

    private val me = 777_000L

    private fun chat(id: Long, title: String, type: String = "chatTypeSupergroup") =
        SelectableChat(chatId = id, title = title, type = type)

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
        // 置顶语义：-1 小于任何普通驱动器序号
        assertTrue(SavedMessagesPolicy.ORDER_IDX < 0)
    }

    @Test
    fun `pinFirst 把已存在的收藏抽出来置顶并统一标题`() {
        val list = listOf(chat(11L, "频道A"), chat(me, "张三"), chat(12L, "群B"))
        val out = SavedMessagesPolicy.pinFirst(list, me)
        assertEquals(listOf(me, 11L, 12L), out.map { it.chatId })
        assertEquals("我的收藏", out.first().title)
        // 不产生重复行
        assertEquals(3, out.size)
        assertEquals(1, out.count { it.chatId == me })
    }

    @Test
    fun `pinFirst 在收藏缺失时补一条置顶`() {
        val list = listOf(chat(11L, "频道A"), chat(12L, "群B"))
        val out = SavedMessagesPolicy.pinFirst(list, me)
        assertEquals(3, out.size)
        assertEquals(me, out.first().chatId)
        assertEquals("我的收藏", out.first().title)
        assertEquals(SavedMessagesPolicy.CHAT_TYPE, out.first().type)
        assertEquals(listOf(11L, 12L), out.drop(1).map { it.chatId })
    }

    @Test
    fun `pinFirst 在 myUserId 未知时原样返回`() {
        val list = listOf(chat(11L, "频道A"))
        assertEquals(list, SavedMessagesPolicy.pinFirst(list, 0L))
    }

    @Test
    fun `pinFirst 对空列表也能补出收藏`() {
        val out = SavedMessagesPolicy.pinFirst(emptyList(), me)
        assertEquals(1, out.size)
        assertEquals("我的收藏", out.single().title)
        assertTrue(out.single().isSavedMessages)
    }

    @Test
    fun `subtitle 收藏用专属文案其余按类型翻译`() {
        val saved = SavedMessagesPolicy.pinFirst(emptyList(), me).single()
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

    @Test
    fun `pinFirst 置顶的收藏带 isSavedMessages 标记`() {
        val out = SavedMessagesPolicy.pinFirst(listOf(chat(11L, "频道A")), me)
        assertTrue(out.first().isSavedMessages)
        assertTrue(!out[1].isSavedMessages)
    }
}
