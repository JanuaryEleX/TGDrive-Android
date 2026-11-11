package com.yg.tgdrive.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** media.kind 取值（SPEC §5）。 */
object MediaKind {
    const val VIDEO = "video"
    const val IMAGE = "image"
}

/** drives.scan_state 取值（SPEC §5）。 */
object ScanState {
    const val IDLE = "idle"
    const val SCANNING = "scanning"
    const val DONE = "done"
    const val ERROR = "error"
}

/**
 * 驱动器 = 用户勾选的 TG 聊天（私有频道/群/Saved Messages）。
 * 字段与 SPEC §5 `drives` 表一一对应。
 */
@Entity(
    tableName = "drives",
    indices = [Index(value = ["chat_id"], unique = true)],
)
data class DriveEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "order_idx", defaultValue = "0")
    val orderIdx: Int = 0,
    /** epoch ms */
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "last_scanned_message_id", defaultValue = "0")
    val lastScannedMessageId: Long = 0,
    @ColumnInfo(name = "scan_state", defaultValue = "idle")
    val scanState: String = ScanState.IDLE,
)

/**
 * 媒体索引行。file_id 可失效（TDLib 库重置），播放前 freshFileId 重取。
 * 字段与 SPEC §5 `media` 表一一对应；UNIQUE(chat_id, message_id) 幂等。
 */
@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = DriveEntity::class,
            parentColumns = ["id"],
            childColumns = ["drive_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chat_id", "message_id"], unique = true),
        Index(
            value = ["drive_id", "kind", "message_date"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC],
            name = "idx_media_wall",
        ),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "drive_id")
    val driveId: Long,
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    /** MediaKind.VIDEO | MediaKind.IMAGE */
    @ColumnInfo(name = "kind")
    val kind: String,
    /** TDLib 短期 file_id，可能失效 */
    @ColumnInfo(name = "file_id")
    val fileId: Int,
    @ColumnInfo(name = "remote_file_id")
    val remoteFileId: String,
    /** LRU 缓存 key / 缩略图文件名 */
    @ColumnInfo(name = "file_unique_id")
    val fileUniqueId: String,
    /** 含本地覆盖重命名 */
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "original_name")
    val originalName: String,
    @ColumnInfo(name = "mime")
    val mime: String?,
    @ColumnInfo(name = "size")
    val size: Long,
    /** image 为 NULL */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,
    @ColumnInfo(name = "width")
    val width: Int?,
    @ColumnInfo(name = "height")
    val height: Int?,
    /** epoch s */
    @ColumnInfo(name = "message_date")
    val messageDate: Long,
    /**
     * TG 相册 ID：同一次「发送相册」产生的多条 photo/video message 共享同一 id；
     * 不属于相册时为 NULL（TDLib 里非相册消息 media_album_id=0，入库时归一化为 null）。
     * 图片 tab 靠它判断「这张图所在的相册里有没有视频」。
     */
    @ColumnInfo(name = "media_album_id")
    val mediaAlbumId: Long? = null,
    /** 本地缩略图缓存绝对路径，可 NULL */
    @ColumnInfo(name = "thumb_path")
    val thumbPath: String? = null,
    /** file_id 失效待重取 */
    @ColumnInfo(name = "stale", defaultValue = "0")
    val stale: Boolean = false,
)

/**
 * FTS4 外部内容表，挂 media.display_name（SPEC §4.5 搜索）。
 * Room 官方 external content FTS 写法：@Fts4(contentEntity=...) 由 Room 自动创建
 * `content=media, content_rowid=id` 虚表及全部同步触发器（AFTER INSERT / BEFORE/AFTER UPDATE / BEFORE/AFTER DELETE），
 * 无需手写 trigger。rowid 即 media.id。
 */
@Fts4(contentEntity = MediaEntity::class)
@Entity(tableName = "media_fts")
data class MediaFts(
    @ColumnInfo(name = "display_name")
    val displayName: String,
)

/**
 * 播放进度（SPEC §4.4 续播）：≥95% 或播完 finished=true，不再进继续观看。
 */
@Entity(
    tableName = "playback_progress",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaybackProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "finished", defaultValue = "0")
    val finished: Boolean = false,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
