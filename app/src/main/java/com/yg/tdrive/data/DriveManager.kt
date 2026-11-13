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

/**
 * 勾选页条目：type 为 TDLib chat type 字符串（chatTypePrivate/chatTypeBasicGroup/…）。
 * isSavedMessages = 这条就是「我的收藏」（chat_id == 自己 user id），UI 用它换副标题。
 */
data class SelectableChat(
    val chatId: Long,
    val title: String,
    val type: String,
    val isSavedMessages: Boolean = false,
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
     *
     * 「我的收藏」（chatId == 自己 user id）特殊处理：标题恒为「我的收藏」，
     * order_idx 恒为 [SavedMessagesPolicy.ORDER_IDX](-1)，因此驱动器列表、海报墙、
     * 图库的默认选中项天然就是收藏盘，无需 UI 层再排序。
     */
    suspend fun addDrive(chatId: Long, title: String? = null): DriveItem {
        val info = runCatching { fetchChatInfo(chatId) }.getOrNull()
        driveDao.getByChatId(chatId)?.let { return it.toItem() }
        val myUserId = runCatching { fetchMyUserId() }.getOrDefault(0L)
        val fallbackOrder = (driveDao.getAll().maxOfOrNull { it.orderIdx } ?: -1) + 1
        val entity = DriveEntity(
            chatId = chatId,
            title = SavedMessagesPolicy.resolveTitle(
                chatId = chatId,
                myUserId = myUserId,
                raw = title?.takeIf { it.isNotBlank() } ?: info?.title,
            ),
            orderIdx = SavedMessagesPolicy.resolveOrderIdx(chatId, myUserId, fallbackOrder),
            addedAt = System.currentTimeMillis(),
        )
        val id = withContext(Dispatchers.IO) { driveDao.upsert(entity) }
        return entity.copy(id = id).toItem()
    }

    /**
     * 勾选页主入口（SPEC §4.2）：getChats(chatListMain, limit) 拉聊天摘要供 UI 过滤
     * 可选类型（私有频道/群/Saved Messages）。仅枚举，不做入库。
     *
     * 「我的收藏」处理：getChats 未必返回与自己对话的那条（也可能被 limit 截断），
     * 所以这里统一取 getMe 再走 [SavedMessagesPolicy.pinFirst] —— 恒定置顶且标题
     * 归一成「我的收藏」，用户在列表里一眼就能选中它。
     */
    suspend fun listSelectableChats(limit: Int = SELECT_LIMIT): List<SelectableChat> =
        withContext(Dispatchers.IO) {
            val myUserId = runCatching { fetchMyUserId() }.getOrDefault(0L)
            val chatsResp = runCatching {
                td.tdCall(
                    tdObj(
                        "@type" to "getChats",
                        "chat_list" to tdObj("@type" to "chatListMain"),
                        "limit" to limit,
                    )
                )
            }.getOrNull() ?: return@withContext SavedMessagesPolicy.pinFirst(emptyList(), myUserId)
            val ids = chatsResp["chat_ids"]?.jsonArray?.mapNotNull {
                (it as? JsonPrimitive)?.content?.toLongOrNull()
            } ?: emptyList()
            val chats = ids.mapNotNull { id ->
                runCatching { fetchChatInfo(id) }.getOrNull()?.let {
                    SelectableChat(
                        chatId = id,
                        title = SavedMessagesPolicy.resolveTitle(id, myUserId, it.title),
                        type = it.type,
                        isSavedMessages = SavedMessagesPolicy.isSavedMessages(id, myUserId),
                    )
                }
            }
            SavedMessagesPolicy.pinFirst(chats, myUserId)
        }

    /** 当前登录账号的 user id（= Saved Messages 的 chat_id）；失败返回 0。 */
    suspend fun fetchMyUserId(): Long = withContext(Dispatchers.IO) {
        val me = td.tdCall(tdObj("@type" to "getMe"))
        (me["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    }

    /** Saved Messages 特判入口：getMe 取自身 user id（= Saved Messages chat_id）。 */
    suspend fun addSavedMessages(): DriveItem {
        val myId = fetchMyUserId()
        if (myId == 0L) throw IllegalStateException("getMe 未返回 user.id")
        return addDrive(myId, SavedMessagesPolicy.TITLE)
    }

    /**
     * 登录就绪后自动挂上「我的收藏」并置顶（幂等，每次启动可安全重复调用）。
     *
     * - 已存在：只把 order_idx 归一到 [SavedMessagesPolicy.ORDER_IDX]、标题归一，不重扫
     * - 不存在：新建驱动器，返回 [SavedMessagesResult.Created]，由调用方触发首次全量扫描
     *
     * 之所以放在「就绪后自动」而不是只靠勾选页：老版本用户库里没有这条驱动器，
     * 光靠他们手动去设置页勾选是发现不了的；用户要求的是收藏盘默认就在、且在第一位。
     */
    suspend fun ensureSavedMessagesDrive(): SavedMessagesResult {
        val myId = runCatching { fetchMyUserId() }.getOrDefault(0L)
        if (myId == 0L) return SavedMessagesResult.Unavailable
        val existing = driveDao.getByChatId(myId)
        if (existing != null) {
            // 历史行可能标题是账号名、序号排在末尾，这里统一纠正（写库成本可忽略）
            if (existing.title != SavedMessagesPolicy.TITLE ||
                existing.orderIdx != SavedMessagesPolicy.ORDER_IDX
            ) {
                withContext(Dispatchers.IO) {
                    driveDao.pinDrive(myId, SavedMessagesPolicy.ORDER_IDX, SavedMessagesPolicy.TITLE)
                }
            }
            return SavedMessagesResult.AlreadyPinned(existing.copy(
                title = SavedMessagesPolicy.TITLE,
                orderIdx = SavedMessagesPolicy.ORDER_IDX,
            ).toItem())
        }
        val item = addDrive(myId, SavedMessagesPolicy.TITLE)
        return SavedMessagesResult.Created(item)
    }

    /** [ensureSavedMessagesDrive] 的结果，供调用方决定是否触发扫描/打日志。 */
    sealed interface SavedMessagesResult {
        /** 未登录或 getMe 失败，本轮跳过。 */
        data object Unavailable : SavedMessagesResult

        /** 已存在，仅纠正了标题/序号。 */
        data class AlreadyPinned(val drive: DriveItem) : SavedMessagesResult

        /** 本次新建，需要首次全量扫描。 */
        data class Created(val drive: DriveItem) : SavedMessagesResult
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

