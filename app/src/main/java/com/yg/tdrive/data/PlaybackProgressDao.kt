package com.yg.tgdrive.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * playback_progress 表 DAO（SPEC §4.4 / §5）。
 */
@Dao
interface PlaybackProgressDao {

    @Upsert
    suspend fun upsert(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE media_id = :mediaId LIMIT 1")
    suspend fun get(mediaId: Long): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE media_id = :mediaId LIMIT 1")
    fun observe(mediaId: Long): Flow<PlaybackProgressEntity?>

    @Query("DELETE FROM playback_progress WHERE media_id = :mediaId")
    suspend fun clear(mediaId: Long)

    @Query("DELETE FROM playback_progress")
    suspend fun clearAll()
}
