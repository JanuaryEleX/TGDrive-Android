package com.yg.tgdrive.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 离线下载任务（Wave1-B 离线下载子系统）。
 *
 * 一条记录对应「把某个 media 永久下载到本地」的任务。断点续传由 TDLib 自身的
 * .part 文件保证：App 重启后把 state in (0,1) 的任务重新调度即可续传。
 *
 * state：0 排队 / 1 下载中 / 2 暂停 / 3 完成 / 4 失败 / 5 取消
 */
@Entity(tableName = "download_task")
data class DownloadTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "file_id")
    val fileId: Int,
    /** "video" | "image" */
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** 完成后已落盘的实际路径（私有绝对路径 或 MediaStore content://） */
    @ColumnInfo(name = "target_path")
    val targetPath: String? = null,
    @ColumnInfo(name = "state")
    val state: Int = STATE_QUEUED,
    @ColumnInfo(name = "bytes_done")
    val bytesDone: Long = 0,
    @ColumnInfo(name = "bytes_total")
    val bytesTotal: Long = 0,
    @ColumnInfo(name = "priority")
    val priority: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "error_msg")
    val errorMsg: String? = null,
) {
    companion object {
        const val STATE_QUEUED = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_PAUSED = 2
        const val STATE_COMPLETED = 3
        const val STATE_FAILED = 4
        const val STATE_CANCELLED = 5
    }
}

/**
 * download_task DAO。
 *
 * 约定：
 * - [observeAll] / [queryAll] 均按 (state, created_at) 升序，UI 先看到未完成、早入队的。
 * - 调度取队首用 [byState]（priority DESC, created_at ASC）。
 * - 进度写库走 [updateProgress]，完成走 [markCompleted]，避免整行覆盖竞态。
 */
@Dao
interface DownloadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTask): Long

    @Update
    suspend fun update(task: DownloadTask)

    @Query("SELECT * FROM download_task ORDER BY state ASC, created_at ASC")
    fun observeAll(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_task ORDER BY state ASC, created_at ASC")
    suspend fun queryAll(): List<DownloadTask>

    @Query("SELECT * FROM download_task WHERE media_id = :mediaId LIMIT 1")
    suspend fun byMediaId(mediaId: Long): DownloadTask?

    @Query("SELECT * FROM download_task WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): DownloadTask?

    /** 调度取队首：同一优先级下按入队时间先来先下。 */
    @Query("SELECT * FROM download_task WHERE state = :state ORDER BY priority DESC, created_at ASC")
    suspend fun byState(state: Int): List<DownloadTask>

    @Query("SELECT * FROM download_task WHERE state IN (:states) ORDER BY priority DESC, created_at ASC")
    suspend fun byStateIn(states: List<Int>): List<DownloadTask>

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE download_task SET state = :state, error_msg = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: Int, error: String?)

    @Query("UPDATE download_task SET bytes_done = :done, bytes_total = :total WHERE id = :id")
    suspend fun updateProgress(id: Long, done: Long, total: Long)

    @Query(
        """UPDATE download_task
           SET state = 3, target_path = :targetPath, bytes_done = :total, bytes_total = :total, error_msg = NULL
           WHERE id = :id"""
    )
    suspend fun markCompleted(id: Long, targetPath: String, total: Long)

    /** App 重启：把上次停在「下载中」的任务回落为「排队」，由 reload 重新调度续传。 */
    @Query("UPDATE download_task SET state = 0 WHERE state = 1")
    suspend fun resetDownloadingToQueued()

    /** 进行中（排队+下载中）任务数，供顶栏徽标使用。 */
    @Query("SELECT COUNT(*) FROM download_task WHERE state IN (0, 1)")
    suspend fun countActive(): Int
}
