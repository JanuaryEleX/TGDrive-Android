package com.yg.tgdrive.data

import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.tdObj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** UI 层可见的驱动器快照（ServiceLocator 契约：至少含 id/chatId/title）。 */
data class DriveItem(
    val id: Long,
    val chatId: Long,
    val title: String,
)

private fun DriveEntity.toItem() = DriveItem(id = id, chatId = chatId, title = title)

/** 勾选页条目：type 为 TDLib chat type 字符串（chatTypePrivate/chatTypeBasicGroup/…）。 */
data class SelectableChat(
    val chatId: Long,
    val title: String,
    val type: String,
)

/**
 * 驱动器管理（SPEC §4.2）：勾选聊天入库 drives 表 + scan_state 标记。
 * ServiceLocator 契约：DriveManager(db: AppDatabase, td: TdClient)。
 *
 * 【假设】Saved Messages 的 chat_id = 当前用户自身 user id，通过 getMe 获取
 * （TDLib JSON 接口无 "me" 字面量，SPEC §4.2 的 getChat(chat_id=me) 语义按此落实）。
 */
class DriveManager(
    private val db: AppDatabase,
    private val td: TdClient,
) {

    private val driveDao get() = db.driveDao()

    // ---- 查询 ----

    /** 驱动器列表流（UI/ShareReceiver 消费；实体直出，字段见 DriveEntity）。 */
    fun observeDrives(): Flow<List<DriveEntity>> = driveDao.observeAll()

    suspend fun getDrives(): List<DriveItem> = driveDao.getAll().map { it.toItem() }

    suspend fun getDrive(driveId: Long): DriveItem? = driveDao.getById(driveId)?.toItem()

    suspend fun getByChatId(chatId: Long): DriveItem? = driveDao.getByChatId(chatId)?.toItem()

    // ---- 勾选入库 ----

    /**
     * 勾选聊天入库（SPEC §4.2 第1步）。drives.chat_id UNIQUE：已存在则幂等返回既有行。
     * title 可选传入（DrivePickerStep 已有 getChat 结果时避免二次请求）；缺失时回退
     * getChat 或 "Chat {chatId}"；order_idx 追加到末尾。
     */
    suspend fun addDrive(chatId: Long, title: String? = null): DriveItem {
        val info = runCatching { fetchChatInfo(chatId) }.getOrNull()
        driveDao.getByChatId(chatId)?.let { return it.toItem() }
        val entity = DriveEntity(
            chatId = chatId,
            title = title?.takeIf { it.isNotBlank() } ?: info?.title ?: "Chat $chatId",
            orderIdx = (driveDao.getAll().maxOfOrNull { it.orderIdx } ?: -1) + 1,
            addedAt = System.currentTimeMillis(),
        )
        val id = withContext(Dispatchers.IO) { driveDao.upsert(entity) }
        return entity.copy(id = id).toItem()
    }

    /**
     * 勾选页主入口（SPEC §4.2）：getChats(chatListMain, limit) 拉聊天摘要供 UI 过滤
     * 可选类型（私有频道/群/Saved Messages）。仅枚举，不做入库。
     */
    suspend fun listSelectableChats(limit: Int = SELECT_LIMIT): List<SelectableChat> =
        withContext(Dispatchers.IO) {
            val chatsResp = runCatching {
                td.tdCall(
                    tdObj(
                        "@type" to "getChats",
                        "chat_list" to tdObj("@type" to "chatListMain"),
                        "limit" to limit,
                    )
                )
            }.getOrNull() ?: return@withContext emptyList()
            val ids = chatsResp["chat_ids"]?.jsonArray?.mapNotNull {
                (it as? JsonPrimitive)?.content?.toLongOrNull()
            } ?: emptyList()
            ids.mapNotNull { id ->
                runCatching { fetchChatInfo(id) }.getOrNull()?.let {
                    SelectableChat(chatId = id, title = it.title, type = it.type)
                }
            }
        }

    /** Saved Messages 特判入口：getMe 取自身 user id（= Saved Messages chat_id）。 */
    suspend fun addSavedMessages(): DriveItem {
        val me = td.tdCall(tdObj("@type" to "getMe"))
        val myId = (me["id"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw IllegalStateException("getMe 未返回 user.id")
        return addDrive(myId)
    }

    // ---- 状态标记 ----

    suspend fun setScanState(driveId: Long, state: String) = driveDao.setScanState(driveId, state)

    suspend fun setLastScannedMessageId(driveId: Long, messageId: Long) =
        driveDao.setLastScannedMessageId(driveId, messageId)

    // ---- 删除 ----

    /** 仅移除本地驱动器记录（media 级联删除，云端消息不动）。 */
    suspend fun removeDrive(driveId: Long) = driveDao.delete(driveId)

    // ---- 重扫 ----

    /**
     * 手动重扫（设置页入口）：无论上次扫描状态如何，从 last_scanned_message_id
     * 增量拉齐到最新（成本低；全量重扫=先 removeDrive 再 addDrive）。
     */
    suspend fun rescan(driveId: Long) {
        mediaRepositoryBridge()?.invoke(driveId)
    }

    /** 可选反向桥（lead 接线）：避免 DriveManager→MediaRepository 构造环。 */
    var rescanBridge: (suspend (Long) -> Unit)? = null

    private fun mediaRepositoryBridge() = rescanBridge

    // ---- 内部 ----

    private data class ChatBrief(val title: String, val type: String)

    private suspend fun fetchChatInfo(chatId: Long): ChatBrief {
        val chat = td.tdCall(tdObj("@type" to "getChat", "chat_id" to chatId))
        return ChatBrief(
            title = (chat["title"] as? JsonPrimitive)?.content ?: "Chat $chatId",
            type = chat["type"]?.jsonObject?.get("@type")
                ?.let { it as? JsonPrimitive }?.content ?: "",
        )
    }

    companion object {
        private const val SELECT_LIMIT = 200
    }
}

