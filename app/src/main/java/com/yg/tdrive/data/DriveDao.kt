package com.yg.tgdrive.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * drives 表 DAO（SPEC §4.2 驱动器 + §6）。
 */
@Dao
interface DriveDao {

    @Upsert
    suspend fun upsert(drive: DriveEntity): Long

    @Query("SELECT * FROM drives ORDER BY order_idx, id")
    suspend fun getAll(): List<DriveEntity>

    @Query("SELECT * FROM drives ORDER BY order_idx, id")
    fun observeAll(): Flow<List<DriveEntity>>

    @Query("DELETE FROM drives WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM drives WHERE chat_id = :chatId LIMIT 1")
    suspend fun getByChatId(chatId: Long): DriveEntity?

    @Query("SELECT * FROM drives WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DriveEntity?

    @Query("UPDATE drives SET scan_state = :state WHERE id = :id")
    suspend fun setScanState(id: Long, state: String)

    @Query("UPDATE drives SET last_scanned_message_id = :messageId WHERE id = :id")
    suspend fun setLastScannedMessageId(id: Long, messageId: Long)

    @Query("UPDATE drives SET title = :title WHERE chat_id = :chatId")
    suspend fun updateTitle(chatId: Long, title: String)

    /**
     * 把「我的收藏」钉到首位并归一标题：order_idx 恒为 -1（小于任何普通驱动器），
     * 供登录后的自动补挂流程修正历史上已入库、但序号/标题都不对的收藏驱动器。
     */
    @Query("UPDATE drives SET order_idx = :orderIdx, title = :title WHERE chat_id = :chatId")
    suspend fun pinDrive(chatId: Long, orderIdx: Int, title: String)
}
