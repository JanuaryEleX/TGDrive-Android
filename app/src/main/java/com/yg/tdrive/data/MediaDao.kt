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

    /** 顶栏按类型显示已入库总数；不受分页和当前已加载页限制。 */
    @Query("SELECT COUNT(*) FROM media WHERE kind = :kind")
    fun observeKindCount(kind: String): Flow<Int>

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

    /**
     * 全局搜索：跨 video+image 按 display_name 匹配（复用 media_fts MATCH）。
     * kind 非空时按 kind 过滤；按 message_date DESC 分页。
     */
    @Query(
        """
        SELECT media.* FROM media
        JOIN media_fts ON media_fts.rowid = media.id
        WHERE media_fts MATCH :query
          AND (:kind IS NULL OR media.kind = :kind)
        ORDER BY media.message_date DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchAll(
        query: String,
        kind: String?,
        limit: Int,
        offset: Int,
    ): List<MediaEntity>

    /**
     * LIKE 子串搜索（中文、单个字符用）。
     *
     * FTS5 的 unicode61 分词把连续汉字当成一个 token，`"视"*` 匹配不到「我的视频」，
     * 所以中文/单字查询改走这里做真正的子串匹配。pattern 由 SearchQueryPolicy 构造，
     * 已转义 % 与 _，配合 ESCAPE '\'。
     */
    @Query(
        """
        SELECT * FROM media
        WHERE display_name LIKE :pattern ESCAPE '\'
          AND (:kind IS NULL OR kind = :kind)
        ORDER BY message_date DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchLikeAll(
        pattern: String,
        kind: String?,
        limit: Int,
        offset: Int,
    ): List<MediaEntity>

    /** 同上，非分页版本（首页搜索用）。 */
    @Query(
        """
        SELECT * FROM media
        WHERE display_name LIKE :pattern ESCAPE '\'
        ORDER BY message_date DESC
        LIMIT 100
        """
    )
    suspend fun searchLike(pattern: String): List<MediaEntity>

    /** 词云只读取本地索引的标题文本，避免一次加载整表的媒体/缩略图字段。 */
    @Query("SELECT display_name FROM media ORDER BY message_date DESC LIMIT :limit")
    suspend fun recentDisplayNames(limit: Int): List<String>

    /** 搜索词与筛选条件同时生效；标题匹配在 SQL 分页之前执行。 */
    @Query(
        """
        SELECT * FROM media
        WHERE display_name LIKE :pattern ESCAPE '\'
          AND (:kind IS NULL OR kind = :kind)
          AND (:minSize IS NULL OR size >= :minSize)
          AND (:maxSize IS NULL OR size <= :maxSize)
          AND (:minDurationMs IS NULL OR duration_ms >= :minDurationMs)
          AND (:maxDurationMs IS NULL OR duration_ms <= :maxDurationMs)
          AND (:dateFrom IS NULL OR message_date >= :dateFrom)
          AND (:dateTo IS NULL OR message_date <= :dateTo)
        ORDER BY
          CASE WHEN :sortBy = 'name'     AND :desc = 0 THEN display_name END ASC,
          CASE WHEN :sortBy = 'name'     AND :desc = 1 THEN display_name END DESC,
          CASE WHEN :sortBy = 'date'     AND :desc = 0 THEN message_date END ASC,
          CASE WHEN :sortBy = 'date'     AND :desc = 1 THEN message_date END DESC,
          CASE WHEN :sortBy = 'size'     AND :desc = 0 THEN size END ASC,
          CASE WHEN :sortBy = 'size'     AND :desc = 1 THEN size END DESC,
          CASE WHEN :sortBy = 'duration' AND :desc = 0 THEN duration_ms END ASC,
          CASE WHEN :sortBy = 'duration' AND :desc = 1 THEN duration_ms END DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchFiltered(
        pattern: String, kind: String?, minSize: Long?, maxSize: Long?,
        minDurationMs: Long?, maxDurationMs: Long?, dateFrom: Long?, dateTo: Long?,
        sortBy: String, desc: Boolean, limit: Int, offset: Int,
    ): List<MediaEntity>

    /**
     * 条件筛选 + 排序 + 分页（全局，跨驱动器）。
     * sortBy ∈ {name,date,size,duration}；desc=true 降序。
     * 动态排序用 CASE 表达式实现（Room 不允许直接拼接 ORDER BY 片段）。
     */
    @Query(
        """
        SELECT * FROM media
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:minSize IS NULL OR size >= :minSize)
          AND (:maxSize IS NULL OR size <= :maxSize)
          AND (:minDurationMs IS NULL OR duration_ms >= :minDurationMs)
          AND (:maxDurationMs IS NULL OR duration_ms <= :maxDurationMs)
          AND (:dateFrom IS NULL OR message_date >= :dateFrom)
          AND (:dateTo IS NULL OR message_date <= :dateTo)
        ORDER BY
          CASE WHEN :sortBy = 'name'     AND :desc = 0 THEN display_name END ASC,
          CASE WHEN :sortBy = 'name'     AND :desc = 1 THEN display_name END DESC,
          CASE WHEN :sortBy = 'date'     AND :desc = 0 THEN message_date END ASC,
          CASE WHEN :sortBy = 'date'     AND :desc = 1 THEN message_date END DESC,
          CASE WHEN :sortBy = 'size'     AND :desc = 0 THEN size END ASC,
          CASE WHEN :sortBy = 'size'     AND :desc = 1 THEN size END DESC,
          CASE WHEN :sortBy = 'duration' AND :desc = 0 THEN duration_ms END ASC,
          CASE WHEN :sortBy = 'duration' AND :desc = 1 THEN duration_ms END DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun filtered(
        kind: String?,
        minSize: Long?,
        maxSize: Long?,
        minDurationMs: Long?,
        maxDurationMs: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        sortBy: String,
        desc: Boolean,
        limit: Int,
        offset: Int,
    ): List<MediaEntity>

    /**
     * 海报墙/图片网格的「筛选 + 排序」分页数据源（Paging3，按驱动器 + kind 限定）。
     * 与 observeWallPaged 并列；默认排序等价于 message_date DESC。
     */
    @Query(
        """
        SELECT * FROM media
        WHERE drive_id = :driveId AND kind = :kind
          AND (:minSize IS NULL OR size >= :minSize)
          AND (:maxSize IS NULL OR size <= :maxSize)
          AND (:minDurationMs IS NULL OR duration_ms >= :minDurationMs)
          AND (:maxDurationMs IS NULL OR duration_ms <= :maxDurationMs)
          AND (:dateFrom IS NULL OR message_date >= :dateFrom)
          AND (:dateTo IS NULL OR message_date <= :dateTo)
        ORDER BY
          CASE WHEN :sortBy = 'name'     AND :desc = 0 THEN display_name END ASC,
          CASE WHEN :sortBy = 'name'     AND :desc = 1 THEN display_name END DESC,
          CASE WHEN :sortBy = 'date'     AND :desc = 0 THEN message_date END ASC,
          CASE WHEN :sortBy = 'date'     AND :desc = 1 THEN message_date END DESC,
          CASE WHEN :sortBy = 'size'     AND :desc = 0 THEN size END ASC,
          CASE WHEN :sortBy = 'size'     AND :desc = 1 THEN size END DESC,
          CASE WHEN :sortBy = 'duration' AND :desc = 0 THEN duration_ms END ASC,
          CASE WHEN :sortBy = 'duration' AND :desc = 1 THEN duration_ms END DESC
        """
    )
    fun observeFilteredPaged(
        driveId: Long,
        kind: String,
        minSize: Long?,
        maxSize: Long?,
        minDurationMs: Long?,
        maxDurationMs: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        sortBy: String,
        desc: Boolean,
    ): PagingSource<Int, MediaEntity>

    @Query(
        """
        DELETE FROM media
        WHERE chat_id = :chatId AND message_id IN (:messageIds)
        """
    )
    suspend fun deleteByMessage(chatId: Long, messageIds: List<Long>)

    /** 同组视频已有的描述名：供跨页扫描继承；忽略原始文件名。 */
    @Query("""SELECT display_name FROM media
        WHERE drive_id = :driveId AND media_album_id = :albumId AND kind = 'video'
          AND original_name != '' AND display_name != original_name LIMIT 1""")
    suspend fun albumVideoCaption(driveId: Long, albumId: Long): String?

    /** 同组视频可改名的行：只匹配 display_name == original_name，保护用户手动重命名。 */
    @Query("""UPDATE media SET display_name = :caption
        WHERE drive_id = :driveId AND media_album_id = :albumId AND kind = 'video'
          AND display_name = original_name AND display_name != :caption""")
    suspend fun applyAlbumCaption(driveId: Long, albumId: Long, caption: String): Int

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

    /** 增量扫描停止判定：本页中哪些 message_id 已在库里（= 旧消息）。 */
    @Query("SELECT message_id FROM media WHERE chat_id = :chatId AND message_id IN (:messageIds)")
    suspend fun existingMessageIds(chatId: Long, messageIds: List<Long>): List<Long>

    /** M2 缩略图回写。 */
    @Query("UPDATE media SET thumb_path = :thumbPath WHERE id = :id")
    suspend fun updateThumbPath(id: Long, thumbPath: String?)

    /** M4 stale 清理：删除某驱动器内全部失效行。 */
    @Query("DELETE FROM media WHERE drive_id = :driveId AND stale = 1")
    suspend fun deleteStaleByDrive(driveId: Long)

    /** 缺封面行计数（诊断/补抓用）。 */
    @Query("SELECT COUNT(*) FROM media WHERE drive_id = :driveId AND thumb_path IS NULL")
    suspend fun countMissingThumb(driveId: Long): Int

    /** 缺封面行计数（按 kind，诊断用）。 */
    @Query(
        """SELECT COUNT(*) FROM media
           WHERE drive_id = :driveId AND kind = :kind AND thumb_path IS NULL"""
    )
    suspend fun countMissingThumbByKind(driveId: Long, kind: String): Int

    /** 第二阶段补抓：缺封面的图片行（相册图封面必须先于视频分配补齐）。 */
    @Query(
        """SELECT * FROM media
           WHERE drive_id = :driveId AND kind = 'image' AND thumb_path IS NULL"""
    )
    suspend fun imagesMissingThumb(driveId: Long): List<MediaEntity>

    /** 第二阶段补抓：仍缺封面的视频行（相册无图封面 / 自身封面下载失败）。 */
    @Query(
        """SELECT * FROM media
           WHERE drive_id = :driveId AND kind = 'video' AND thumb_path IS NULL"""
    )
    suspend fun videosMissingThumb(driveId: Long): List<MediaEntity>

    /**
     * 相册优先封面：每条视频 → 同相册组内 `message_id` 最小的**已下载**图片封面。
     * 子查询按 message_id ASC LIMIT 1 取相册首图（视觉上最接近 Telegram 相册）；
     * 同组无可用图封面时 thumbPath 为 NULL，调用方回退视频自身封面。
     */
    @Query(
        """
        SELECT v.id AS videoId,
               (
                   SELECT i.thumb_path FROM media i
                   WHERE i.media_album_id = v.media_album_id
                     AND i.kind = 'image'
                     AND i.thumb_path IS NOT NULL
                   ORDER BY i.message_id ASC
                   LIMIT 1
               ) AS thumbPath
        FROM media v
        WHERE v.drive_id = :driveId
          AND v.kind = 'video'
          AND v.media_album_id IS NOT NULL
        """
    )
    suspend fun videoAlbumCovers(driveId: Long): List<VideoAlbumCover>

    /** 幂等写封面：仅当值变化时 UPDATE（重复扫描不产生写放大）。 */
    @Query(
        """UPDATE media SET thumb_path = :thumbPath
           WHERE id = :id AND (thumb_path IS NULL OR thumb_path != :thumbPath)"""
    )
    suspend fun updateThumbPathIfChanged(id: Long, thumbPath: String): Int

    /** 清空某驱动器内缺封面行的 thumb_path，让下一轮扫描重新补抓。 */
    @Query("UPDATE media SET thumb_path = NULL WHERE drive_id = :driveId AND thumb_path IS NULL")
    suspend fun noopClearMissingThumb(driveId: Long)

    // ── 相册（media_album_id）相关 ─────────────────────────────────────────

    /**
     * 批量判断「哪些相册里含视频」，并顺带取组内视频时长（取最短的那个视频作为角标时长）。
     * 图片 tab 渲染时一次 IN 查询拿全，避免每格 N+1。
     */
    @Query(
        """
        SELECT media_album_id AS albumId, MIN(duration_ms) AS durationMs
        FROM media
        WHERE media_album_id IN (:albumIds) AND kind = 'video'
        GROUP BY media_album_id
        """
    )
    suspend fun albumVideoInfos(albumIds: List<Long>): List<AlbumVideoInfo>

    /** 看图页：同相册的图片，按消息 id 升序（相册内原始顺序）。 */
    @Query(
        """SELECT * FROM media WHERE media_album_id = :albumId AND kind = 'image'
           ORDER BY message_id ASC"""
    )
    suspend fun imagesInAlbum(albumId: Long): List<MediaEntity>

    /** 相册内的视频（供「播放视频」跳转/多视频列表）。 */
    @Query(
        """SELECT * FROM media WHERE media_album_id = :albumId AND kind = 'video'
           ORDER BY message_id ASC"""
    )
    suspend fun videosInAlbum(albumId: Long): List<MediaEntity>

    /** 回填：待补相册 ID 的行（media_album_id IS NULL 且未失效）。 */
    @Query(
        """SELECT * FROM media WHERE media_album_id IS NULL AND stale = 0 AND drive_id = :driveId
           ORDER BY id ASC LIMIT :limit"""
    )
    suspend fun rowsMissingAlbum(driveId: Long, limit: Int): List<MediaEntity>

    /** 回填：全库还有多少行缺相册 ID。 */
    @Query("SELECT COUNT(*) FROM media WHERE media_album_id IS NULL")
    suspend fun countMissingAlbum(): Int

    /** 回填：还有缺相册 ID 行的驱动器列表。 */
    @Query("SELECT DISTINCT drive_id FROM media WHERE media_album_id IS NULL")
    suspend fun driveIdsMissingAlbum(): List<Long>

    /** 回填：写入某行的相册 ID（按主键）。 */
    @Query("UPDATE media SET media_album_id = :albumId WHERE id = :id")
    suspend fun updateAlbumId(id: Long, albumId: Long?)

    /**
     * 回填：按 (chat_id, message_id) 写相册 ID，且只在尚未回填时生效。
     * `:albumId IS NOT NULL` 是必要的：非相册消息的 media_album_id 归一化为 NULL，
     * 若不排除，UPDATE NULL→NULL 仍算命中，重复页会把「处理数」刷虚（4051 > 实际缺失 1096）。
     * 返回受影响行数（0 = 库里没这条 / 已回填过 / 本身不是相册消息）。
     */
    @Query(
        """UPDATE media SET media_album_id = :albumId
           WHERE chat_id = :chatId AND message_id = :messageId
             AND media_album_id IS NULL AND :albumId IS NOT NULL"""
    )
    suspend fun backfillAlbumId(chatId: Long, messageId: Long, albumId: Long?): Int

    // ── 收藏（favorited_at，v3→v4）─────────────────────────────────────────
    // 只追加：不修改任何既有查询，收藏逻辑由 FavoritesRepository 统一封装。

    /**
     * 收藏页数据源：已收藏媒体（视频+图片混排）按收藏时间倒序分页。
     * 排序与 FavoritesPolicy.orderByFavoriteTime 的契约一致：favorited_at DESC，同刻按 id DESC。
     * `favorited_at IS NOT NULL` 直接命中 idx_media_favorited_at。
     */
    @Query(
        """
        SELECT * FROM media
        WHERE favorited_at IS NOT NULL
        ORDER BY favorited_at DESC, id DESC
        """
    )
    fun observeFavoritesPaged(): PagingSource<Int, MediaEntity>

    /** 某行当前的收藏时间（null = 未收藏 / 行不存在）。切换收藏前先读它。 */
    @Query("SELECT favorited_at FROM media WHERE id = :id LIMIT 1")
    suspend fun favoritedAtOf(id: Long): Long?

    /** 写收藏时间（null = 取消收藏）。返回受影响行数（0 = 行已被删除）。 */
    @Query("UPDATE media SET favorited_at = :favoritedAt WHERE id = :id")
    suspend fun setFavoritedAt(id: Long, favoritedAt: Long?): Int

    /**
     * 全部收藏行的 (id, favorited_at)：播放队列按收藏时间倒序在这里算。
     * 只取两列，几千条收藏也只是几十 KB，比按分页已加载项拼队列更准（不受 400 条上限影响）。
     */
    @Query("SELECT id, favorited_at FROM media WHERE favorited_at IS NOT NULL")
    suspend fun favoriteRows(): List<FavoriteRow>
}

/** 视频 → 同相册首图封面路径（相册无可用图封面时 thumbPath 为 NULL）。 */
data class VideoAlbumCover(
    val videoId: Long,
    val thumbPath: String?,
)

/** 相册 → 组内视频时长（角标用）。 */
data class AlbumVideoInfo(
    val albumId: Long,
    val durationMs: Long?,
)
