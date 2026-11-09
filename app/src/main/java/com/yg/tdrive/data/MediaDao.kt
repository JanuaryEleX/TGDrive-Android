package com.yg.tgdrive.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * media 表 DAO（SPEC §4.2 / §5 / §6）。
 * 海报墙 Paging 按 message_date DESC；继续观看 join playback_progress。
 */
@Dao
interface MediaDao {

    /**
     * 扫描入库（幂等）：UNIQUE(chat_id, message_id) 冲突的行被跳过（返回 -1），
     * 已存在行的本地覆盖（display_name 重命名 / thumb_path）保持不动。
     * 注意：扫描入库不用 @Upsert —— 它只按主键处理冲突，撞到二级唯一索引会抛约束异常。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoring(items: List<MediaEntity>): List<Long>

    /** 主键级覆盖更新（freshFileId 重取回写用：id 相同 → 执行 UPDATE）。 */
    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    /**
     * 海报墙/图片网格数据源：驱动器内按 kind 过滤、message_date DESC 分页
     * （idx_media_wall 命中）。不过滤 stale：失效行播放前走 freshFileId 重取。
     */
    @Query(
        """
        SELECT * FROM media
        WHERE drive_id = :driveId AND kind = :kind
        ORDER BY message_date DESC
        """
    )
    fun observeWallPaged(driveId: Long, kind: String): PagingSource<Int, MediaEntity>

    /**
     * 继续观看：有播放进度且未看完的 video，按进度更新时间倒序取前 20。
     */
    @Query(
        """
        SELECT media.* FROM media
        INNER JOIN playback_progress ON playback_progress.media_id = media.id
        WHERE media.kind = 'video'
          AND playback_progress.finished = 0
        ORDER BY playback_progress.updated_at DESC
        LIMIT 20
        """
    )
    fun observeContinueWatching(): Flow<List<MediaEntity>>

    /** FTS 全文搜索（media_fts 外部内容表挂 display_name）。 */
    @Query(
        """
        SELECT media.* FROM media
        JOIN media_fts ON media_fts.rowid = media.id
        WHERE media_fts MATCH :query
        ORDER BY media.message_date DESC
        LIMIT 100
        """
    )
    suspend fun search(query: String): List<MediaEntity>

    @Query(
        """
        DELETE FROM media
        WHERE chat_id = :chatId AND message_id IN (:messageIds)
        """
    )
    suspend fun deleteByMessage(chatId: Long, messageIds: List<Long>)

    /** Q17：重命名只覆盖本地显示名，FTS 由触发器自动同步。 */
    @Query("UPDATE media SET display_name = :newName WHERE id = :id")
    suspend fun updateDisplayName(id: Long, newName: String)

    @Query("UPDATE media SET stale = :stale WHERE id = :id")
    suspend fun setStale(id: Long, stale: Boolean)

    @Query(
        """
        UPDATE media SET file_id = :fileId, stale = 0
        WHERE chat_id = :chatId AND message_id = :messageId
        """
    )
    suspend fun refreshFileId(chatId: Long, messageId: Long, fileId: Int)

    @Query("SELECT * FROM media WHERE chat_id = :chatId AND message_id = :messageId LIMIT 1")
    suspend fun getByChatAndMessage(chatId: Long, messageId: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE remote_file_id = :remoteFileId LIMIT 1")
    suspend fun getByRemoteFileId(remoteFileId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE drive_id = :driveId")
    suspend fun getAllByDrive(driveId: Long): List<MediaEntity>

    /** M2 缩略图回写。 */
    @Query("UPDATE media SET thumb_path = :thumbPath WHERE id = :id")
    suspend fun updateThumbPath(id: Long, thumbPath: String?)

    /** M4 stale 清理：删除某驱动器内全部失效行。 */
    @Query("DELETE FROM media WHERE drive_id = :driveId AND stale = 1")
    suspend fun deleteStaleByDrive(driveId: Long)
}
