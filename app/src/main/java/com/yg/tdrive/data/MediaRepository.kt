package com.yg.tgdrive.data

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.tdObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** UI/上层消费的媒体条目（SPEC §6 MediaItem 语义；字段即 MediaEntity）。 */
typealias MediaItem = MediaEntity

/**
 * 媒体库仓库（SPEC §4.2 / §6）。
 *
 * 职责：
 * - 全量扫描 / 增量同步：getChatHistory 翻页（limit=100，页间 delay 150ms），解析
 *   messageVideo / messagePhoto / messageDocument(视频 mime 或视频后缀) → media 表
 * - 缩略图：downloadFile(thumbnail.file, 0, 0, priority=2) + fileUpdates 等待落盘，
 *   拷贝到 cacheDir/thumbs/{mediaFileUniqueId}.jpg 回写 thumb_path（4 路并发）
 * - 海报墙 Paging3 / 继续观看 / FTS 搜索
 * - 云端删除：deleteMessages(revoke=true) → 先订阅再调用的 updateDeleteMessages 回执
 *   等待（防 SharedFlow 丢事件）→ 删本地行
 * - 重命名：仅 UPDATE display_name（Q17）
 * - freshFileId：getMessage 重取 file_id，失败置 stale=1
 *
 * ServiceLocator 契约：MediaRepository(db: AppDatabase, td: TdClient)。
 * 【假设】第三个参数 appContext 可缺省（默认 null）：ServiceLocator 若保持两参构造，
 * 缩略图链路静默跳过（thumb_path 保持 NULL）；集成时建议显式传 Application context。
 * 【假设】缩略图等扫描外异步作业挂在内部 CoroutineScope(SupervisorJob()+IO) 上，
 * 不随单次扫描协程取消 —— ServiceLocator.appScope 未随构造注入，避免改契约签名。
 */
/** 扫描进度快照（主页顶部进度条订阅；仅内存态，不落库）。 */
data class ScanProgress(
    val driveId: Long = -1L,
    /** 是否正在扫描。 */
    val running: Boolean = false,
    /** 已遍历消息数。 */
    val scanned: Int = 0,
    /** 本次新入库媒体数。 */
    val inserted: Int = 0,
    /** 阶段性提示（等待历史下发 / 完成）。 */
    val phase: String = "",
)

class MediaRepository(
    private val db: AppDatabase,
    private val td: TdClient,
    private val appContext: Context? = null,
) {

    private val mediaDao get() = db.mediaDao()
    private val driveDao get() = db.driveDao()

    /** 扫描期外异步作业（缩略图下载）的宿主作用域。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 缩略图下载并发上限（priority=2 小文件，4 路足够，SPEC §7 速率约束之内）。 */
    private val thumbSemaphore = Semaphore(4)


    // ── 扫描进度：UI 顶部进度条订阅（主页/设置页共用） ──
    private val _scanProgress = MutableStateFlow(ScanProgress())
    /** 当前扫描进度；running=false 时 scanned/inserted 保留本轮结果供 UI 展示。 */
    val scanProgress: StateFlow<ScanProgress> = _scanProgress

    private fun publishProgress(p: ScanProgress) {
        _scanProgress.value = p
    }

    // ═══════════════════════ 扫描 ═══════════════════════

    /**
     * 全量首扫（SPEC §4.2 第1~3步）：getChatHistory(chat_id, from_message_id=0, limit=100)
     * 循环翻页到头部（返回数 < limit 即头部）；每页 delay(150) 节流。
     * 扫完清理 stale 行、写 last_scanned_message_id、置 scan_state=done。
     */
    suspend fun fullScan(driveId: Long) {
        val drive = driveDao.getById(driveId) ?: return
        driveDao.setScanState(driveId, ScanState.SCANNING)
        ScanDiagnostics.begin("fullScan", driveId)
        var scanned = 0
        var inserted = 0
        var emptyRetries = 0
        var shortRetries = 0
        publishProgress(ScanProgress(driveId = driveId, running = true))
        try {
            val chatId = drive.chatId
            var newestId = 0L
            var fromMessageId = 0L
            while (true) {
                ScanDiagnostics.page(fromMessageId)
                val resp = callWithFloodRetry(
                    tdObj(
                        "@type" to "getChatHistory",
                        "chat_id" to chatId,
                        "from_message_id" to fromMessageId,
                        "offset" to 0,
                        "limit" to SCAN_PAGE_SIZE,
                        "only_local" to false,
                    )
                )
                val messages = resp["messages"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject } ?: emptyList()
                if (messages.isEmpty()) {
                    // TDLib 历史尚未落盘时会先返回空页。直接 break 曾把 last_scanned 写成
                    // 最新 id，导致之后所有增量同步被幂等短路 → 永远空库。
                    if (emptyRetries < EMPTY_PAGE_RETRIES) {
                        emptyRetries++
                        publishProgress(
                            ScanProgress(driveId, true, scanned, inserted, "等待 Telegram 下发历史…")
                        )
                        delay(EMPTY_PAGE_DELAY_MS)
                        continue
                    }
                    // 重试耗尽：视为本轮未扫完，标记 ERROR 而非 DONE，
                    // 避免 last_scanned 被写成最新 id 后增量同步永久幂等短路
                    throw IllegalStateException(
                        "历史未就绪：连续 $EMPTY_PAGE_RETRIES 次空页，本轮中止（可重扫重试）"
                    )
                }
                emptyRetries = 0

                if (newestId == 0L) {
                    newestId = messages.first()["id"]?.jsonPrimitive?.longOrNull ?: 0L
                }
                scanned += messages.size
                inserted += ingestPage(chatId, driveId, messages)
                publishProgress(ScanProgress(driveId, true, scanned, inserted))

                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: 0L
                if (lastId == 0L) break
                // 不足一页不一定是到头：TDLib 只返回已缓存部分，中间段还没拉下来。
                // 关键：短页时**不推进** fromMessageId，原地重试，否则会永久跳过未缓存区段。
                if (messages.size < SCAN_PAGE_SIZE) {
                    shortRetries++
                    if (shortRetries > SHORT_PAGE_RETRIES) break
                    publishProgress(
                        ScanProgress(driveId, true, scanned, inserted, "等待更早的历史…")
                    )
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                shortRetries = 0
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
            mediaDao.deleteStaleByDrive(driveId)
            // 已入库但缺封面的行（字段名修正前写入的旧数据）无法从消息重取，
            // 这里仅统计，实际补抓由下一轮 parseMedia 自然完成。
            val missing = mediaDao.countMissingThumb(driveId)
            if (missing > 0) Log.w(TAG, "drive=$driveId 仍有 $missing 条缺封面")
            // 诊断：本轮结束后 DB 里 video/image 的真实行数（含往轮遗留），
            // 与 parsedUniqueByKind 对比即可分清「解析丢」还是「重复页虚增」。
            ScanDiagnostics.dbKindCounts(
                mediaDao.getAllByDrive(driveId).groupingBy { it.kind }.eachCount()
            )
            driveDao.setLastScannedMessageId(driveId, newestId)
            driveDao.setScanState(driveId, ScanState.DONE)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                ScanDiagnostics.error("cancelled")
                throw t
            }
            Log.e(TAG, "fullScan drive=$driveId failed", t)
            ScanDiagnostics.error(t.message ?: t.javaClass.simpleName)
            driveDao.setScanState(driveId, ScanState.ERROR)
        } finally {
            ScanDiagnostics.report(appContext, "fullScan", driveId, scanned, inserted)
            publishProgress(
                ScanProgress(driveId, false, scanned, inserted, if (inserted > 0) "完成" else "")
            )
        }
    }

    /**
     * 增量同步（SPEC §4.2 第4步）：从当前 last_message 向下扫到 last_scanned_message_id
     * 为止（仅 strictly newer 消息入库）。无新消息幂等返回。
     */
    suspend fun incrementalSync(driveId: Long) {
        val drive = driveDao.getById(driveId) ?: return
        driveDao.setScanState(driveId, ScanState.SCANNING)
        ScanDiagnostics.begin("incrementalSync", driveId)
        var scanned = 0
        var inserted = 0
        var emptyRetries = 0
        var shortRetries = 0
        publishProgress(ScanProgress(driveId = driveId, running = true))
        try {
            val chatId = drive.chatId
            val stopId = drive.lastScannedMessageId
            val chat = td.tdCall(tdObj("@type" to "getChat", "chat_id" to chatId))
            val lastMessage = chat["last_message"] as? JsonObject
            if (lastMessage == null) {
                driveDao.setScanState(driveId, ScanState.DONE)
                return
            }
            val newestId = lastMessage["id"]?.jsonPrimitive?.longOrNull ?: 0L
            if (newestId <= stopId) {
                driveDao.setScanState(driveId, ScanState.DONE)
                return
            }
            var fromMessageId = newestId
            while (true) {
                ScanDiagnostics.page(fromMessageId)
                val resp = callWithFloodRetry(
                    tdObj(
                        "@type" to "getChatHistory",
                        "chat_id" to chatId,
                        "from_message_id" to fromMessageId,
                        "offset" to 0,
                        "limit" to SCAN_PAGE_SIZE,
                        "only_local" to false,
                    )
                )
                val messages = resp["messages"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject } ?: emptyList()
                if (messages.isEmpty()) {
                    // 同 fullScan：历史未落盘的空页要重试，不能当作扫完
                    if (emptyRetries < EMPTY_PAGE_RETRIES) {
                        emptyRetries++
                        publishProgress(
                            ScanProgress(driveId, true, scanned, inserted, "等待 Telegram 下发历史…")
                        )
                        delay(EMPTY_PAGE_DELAY_MS)
                        continue
                    }
                    // 重试耗尽：视为本轮未扫完，标记 ERROR 而非 DONE，
                    // 避免 last_scanned 被写成最新 id 后增量同步永久幂等短路
                    throw IllegalStateException(
                        "历史未就绪：连续 $EMPTY_PAGE_RETRIES 次空页，本轮中止（可重扫重试）"
                    )
                }
                emptyRetries = 0

                val stopHere = messages.any {
                    (it["id"]?.jsonPrimitive?.longOrNull ?: 0L) <= stopId
                }
                val fresh = messages.filter {
                    (it["id"]?.jsonPrimitive?.longOrNull ?: 0L) > stopId
                }
                scanned += messages.size
                if (fresh.isNotEmpty()) inserted += ingestPage(chatId, driveId, fresh)
                publishProgress(ScanProgress(driveId, true, scanned, inserted))

                if (stopHere) break
                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: break
                if (messages.size < SCAN_PAGE_SIZE) {
                    shortRetries++
                    if (shortRetries > SHORT_PAGE_RETRIES) break
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                shortRetries = 0
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
            ScanDiagnostics.dbKindCounts(
                mediaDao.getAllByDrive(driveId).groupingBy { it.kind }.eachCount()
            )
            driveDao.setLastScannedMessageId(driveId, newestId)
            driveDao.setScanState(driveId, ScanState.DONE)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                ScanDiagnostics.error("cancelled")
                throw t
            }
            Log.e(TAG, "incrementalSync drive=$driveId failed", t)
            ScanDiagnostics.error(t.message ?: t.javaClass.simpleName)
            driveDao.setScanState(driveId, ScanState.ERROR)
        } finally {
            ScanDiagnostics.report(appContext, "incrementalSync", driveId, scanned, inserted)
            publishProgress(
                ScanProgress(driveId, false, scanned, inserted, if (inserted > 0) "完成" else "")
            )
        }
    }

    /** 单页入库：解析 → IGNORE 幂等插入（回读 rowId）→ 异步补抓缩略图。 */
    private suspend fun ingestPage(chatId: Long, driveId: Long, messages: List<JsonObject>): Int {
        val parsed = messages.mapNotNull { parseMedia(chatId, driveId, it) }
        if (parsed.isEmpty()) return 0
        val entities = parsed.map { it.first }
        val rowIds = withContext(Dispatchers.IO) { mediaDao.insertAllIgnoring(entities) }
        parsed.zip(rowIds) { (entity, thumb), rowId ->
            // rowId == -1L：已存在的行（重扫），保留其本地覆盖（display_name/thumb_path），跳过缩略图
            if (rowId != -1L) launchThumbJob(entity.copy(id = rowId), thumb)
        }
        return rowIds.count { it != -1L }
    }

    // ═══════════════════════ 观察流 ═══════════════════════

    /** 海报墙/图片网格（SPEC §6 observeWall）：Paging3，message_date DESC（idx_media_wall）。 */
    fun observeWall(driveId: Long, kind: String): Flow<PagingData<MediaItem>> =
        Pager(
            config = PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false),
            pagingSourceFactory = { mediaDao.observeWallPaged(driveId, kind) },
        ).flow

    /** 继续观看行（SPEC §4.5）：有进度未看完的 video，updated_at DESC，前 20。 */
    fun observeContinueWatching(): Flow<List<MediaItem>> = mediaDao.observeContinueWatching()

    // ── 相册（media_album_id）──────────────────────────────────────────────

    /**
     * 批量取「哪些相册含视频 + 组内视频时长」。
     * 图片 tab 一页图片的相册 id 去重后一次 IN 查询拿全，避免逐格 N+1。
     */
    suspend fun albumVideoInfo(albumIds: Collection<Long>): Map<Long, Long?> {
        val ids = albumIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            mediaDao.albumVideoInfos(ids).associate { it.albumId to it.durationMs }
        }
    }

    /** 看图页：同相册图片序列。 */
    suspend fun imagesInAlbum(albumId: Long): List<MediaEntity> =
        withContext(Dispatchers.IO) { mediaDao.imagesInAlbum(albumId) }

    /** 看图页跳播：同相册视频序列。 */
    suspend fun videosInAlbum(albumId: Long): List<MediaEntity> =
        withContext(Dispatchers.IO) { mediaDao.videosInAlbum(albumId) }

    /**
     * 相册 ID 回填（v1 → v2 老数据）。
     *
     * 为什么必须单独回填：`insertAllIgnoring` 对已存在行返回 -1 且**不更新任何字段**，
     * 所以重跑 fullScan 填不上老行的 media_album_id。唯一可靠路径是逐条 getMessage
     * 取 `media_album_id` 再 UPDATE 回写。
     *
     * 分批 + 节流，避免触发 TG FLOOD_WAIT；进度经 [onProgress] 回调。
     * 返回实际处理的行数。
     */
    suspend fun backfillAlbumIds(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val total = withContext(Dispatchers.IO) { mediaDao.countMissingAlbum() }
        if (total <= 0) return 0
        val driveIds = withContext(Dispatchers.IO) { mediaDao.driveIdsMissingAlbum() }
        Log.i(TAG, "相册回填开始: 缺 $total 行, 驱动器=$driveIds")
        var done = 0
        for (driveId in driveIds) {
            val drive = driveDao.getById(driveId) ?: continue
            val chatId = drive.chatId
            var fromMessageId = 0L
            var emptyRetries = 0
            var shortRetries = 0
            while (true) {
                val resp = runCatching {
                    callWithFloodRetry(
                        tdObj(
                            "@type" to "getChatHistory",
                            "chat_id" to chatId,
                            "from_message_id" to fromMessageId,
                            "offset" to 0,
                            "limit" to SCAN_PAGE_SIZE,
                            "only_local" to false,
                        )
                    )
                }.getOrNull() ?: break
                val messages = resp["messages"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject } ?: emptyList()
                if (messages.isEmpty()) {
                    if (emptyRetries < EMPTY_PAGE_RETRIES) {
                        emptyRetries++
                        delay(EMPTY_PAGE_DELAY_MS)
                        continue
                    }
                    break
                }
                emptyRetries = 0
                // 用一条历史页批量回填本地缺相册的行：整页只发一次网络请求，
                // 逐条走本地 UPDATE（比逐条 getMessage 快两个数量级）。
                for (msg in messages) {
                    val msgId = msg["id"]?.jsonPrimitive?.longOrNull ?: continue
                    val albumId = msg["media_album_id"]?.jsonPrimitive?.longOrNull?.takeIf { it != 0L }
                    val updated = withContext(Dispatchers.IO) {
                        mediaDao.backfillAlbumId(chatId, msgId, albumId)
                    }
                    if (updated > 0) {
                        done += updated
                        onProgress(done, total)
                    }
                }
                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: break
                if (lastId == 0L) break
                if (messages.size < SCAN_PAGE_SIZE) {
                    shortRetries++
                    if (shortRetries > SHORT_PAGE_RETRIES) break
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                shortRetries = 0
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
            Log.i(TAG, "相册回填: drive=$driveId 累计=$done")
        }
        Log.i(TAG, "相册回填完成: 处理=$done 总缺=$total")
        return done
    }

    /** FTS 搜索（SPEC §4.5）：display_name 挂 media_fts；空查询返回空列表。 */
    suspend fun search(q: String): List<MediaItem> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        // 每个空白分隔 token 转成 "token"* 前缀查询；内嵌引号剥除防 FTS 语法破坏
        val ftsQuery = query.split(Regex("\\s+"))
            .joinToString(" ") { "\"${it.replace("\"", "")}\"*" }
        return runCatching { mediaDao.search(ftsQuery) }
            .onFailure { Log.w(TAG, "search failed: $ftsQuery", it) }
            .getOrDefault(emptyList())
    }

    /**
     * 全局搜索（跨 video+image，按 display_name FTS）分页。
     * kind 非空时只返回该类型；空查询返回空列表。
     */
    suspend fun searchAll(
        query: String,
        kind: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val ftsQuery = q.split(Regex("\\s+"))
            .joinToString(" ") { "\"${it.replace("\"", "")}\"*" }
        runCatching { mediaDao.searchAll(ftsQuery, kind, limit, offset) }
            .onFailure { Log.w(TAG, "searchAll failed: $ftsQuery", it) }
            .getOrDefault(emptyList())
    }

    /**
     * 条件筛选 + 排序 + 分页（全局）。dateFrom/dateTo 单位为 epoch 秒。
     * sortBy ∈ name|date|size|duration。
     */
    suspend fun filtered(
        kind: String? = null,
        minSize: Long? = null,
        maxSize: Long? = null,
        minDurationMs: Long? = null,
        maxDurationMs: Long? = null,
        dateFrom: Long? = null,
        dateTo: Long? = null,
        sortBy: String = "date",
        desc: Boolean = true,
        limit: Int = 60,
        offset: Int = 0,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        runCatching {
            mediaDao.filtered(
                kind, minSize, maxSize, minDurationMs, maxDurationMs,
                dateFrom, dateTo, sortBy, desc, limit, offset,
            )
        }.onFailure { Log.w(TAG, "filtered failed", it) }
            .getOrDefault(emptyList())
    }

    /** 海报墙/图片网格「筛选 + 排序」分页流（限定 driveId + kind）。 */
    fun observeFilteredWall(
        driveId: Long,
        kind: String,
        minSize: Long? = null,
        maxSize: Long? = null,
        minDurationMs: Long? = null,
        maxDurationMs: Long? = null,
        dateFrom: Long? = null,
        dateTo: Long? = null,
        sortBy: String = "date",
        desc: Boolean = true,
    ): Flow<PagingData<MediaItem>> =
        Pager(
            config = PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false),
            pagingSourceFactory = {
                mediaDao.observeFilteredPaged(
                    driveId, kind, minSize, maxSize, minDurationMs, maxDurationMs,
                    dateFrom, dateTo, sortBy, desc,
                )
            },
        ).flow

    // ═══════════════════════ 管理 ═══════════════════════

    /**
     * 云端删除（SPEC §4.2 第5步）：先起 updateDeleteMessages 回执监听（async，防
     * SharedFlow 在订阅前丢事件），再 deleteMessages(revoke=true)，等回执后删本地行。
     * 回执等待 30s 超时仍删本地（保证 UI 不悬挂；云端状态以 TDLib 自身同步为准）。
     */
    suspend fun delete(item: MediaItem) {
        kotlinx.coroutines.coroutineScope {
            val receipt = async { waitDeleteReceipt(item.chatId, item.messageId) }
            td.tdCall(
                tdObj(
                    "@type" to "deleteMessages",
                    "chat_id" to item.chatId,
                    "message_ids" to longJsonArray(item.messageId),
                    "revoke" to true,
                )
            )
            receipt.await()
        }
        mediaDao.deleteByMessage(item.chatId, listOf(item.messageId))
    }

    /** Q17：重命名仅 UPDATE display_name（本地显示名覆盖，不碰云端消息）。 */
    suspend fun rename(item: MediaItem, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == item.displayName) return
        mediaDao.updateDisplayName(item.id, name)
    }

    /**
     * 播放前重取 fresh file_id（SPEC §4.2 失效恢复）：getMessage(chat_id, message_id)
     * → 按消息类型抽 file.id；成功回写并清 stale；任何失败置 stale=1 并返回 -1。
     */
    suspend fun freshFileId(item: MediaItem): Int {
        return try {
            val msg = td.tdCall(
                tdObj(
                    "@type" to "getMessage",
                    "chat_id" to item.chatId,
                    "message_id" to item.messageId,
                )
            )
            val fileObj = extractFileObject(msg)
                ?: throw IllegalArgumentException("message ${item.messageId} 无媒体文件")
            val fileId = fileObj["id"]?.jsonPrimitive?.longOrNull?.toInt()
                ?: throw IllegalArgumentException("file.id 缺失")
            val remote = fileObj["remote"] as? JsonObject
            val uniqueId = (remote?.get("unique_id") as? JsonPrimitive)?.content
                ?: item.fileUniqueId
            val remoteId = (remote?.get("id") as? JsonPrimitive)?.content
                ?: item.remoteFileId
            withContext(Dispatchers.IO) {
                mediaDao.upsertAll(
                    listOf(
                        item.copy(
                            fileId = fileId,
                            remoteFileId = remoteId,
                            fileUniqueId = uniqueId,
                            stale = false,
                        )
                    )
                )
            }
            fileId
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "freshFileId msg=${item.messageId} failed", t)
            mediaDao.setStale(item.id, true)
            -1
        }
    }

    /**
     * 缩略图下载（SPEC §4.2 第3步）：downloadFile(thumbnail.file, 0, 0, priority=2)。
     * 先订阅 fileUpdates 再发起下载（防丢事件），完成后 getFile 快照兜底；
     * 落盘 cacheDir/thumbs/{mediaFileUniqueId}.jpg。返回绝对路径；失败返回 null。
     */
    suspend fun downloadThumbnail(mediaFileUniqueId: String, thumbnailFile: JsonObject): String? {
        val context = appContext ?: return null
        val fileId = thumbnailFile["id"]?.jsonPrimitive?.longOrNull?.toInt() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val path = kotlinx.coroutines.coroutineScope {
                    val listener = async { waitForLocalPathUpdate(fileId) }
                    td.tdCall(
                        tdObj(
                            "@type" to "downloadFile",
                            "file_id" to fileId,
                            "offset" to 0,
                            "limit" to 0,          // 0 = 下载整个文件
                            "priority" to THUMB_PRIORITY,
                        )
                    )
                    // 快照已命中 → 取消订阅等待；否则等 update 回执（最长 20s）
                    val snapshot = currentLocalPath(fileId)
                    if (snapshot != null) {
                        listener.cancel()
                        snapshot
                    } else {
                        listener.await()
                    }
                }
                if (path.isNullOrBlank()) return@withContext null
                val dest = File(File(context.cacheDir, THUMB_DIR), "$mediaFileUniqueId.jpg")
                dest.parentFile?.mkdirs()
                File(path).copyTo(dest, overwrite = true)
                dest.absolutePath
            } catch (t: Throwable) {
                Log.w(TAG, "downloadThumbnail $mediaFileUniqueId failed", t)
                null
            }
        }
    }

    // ═══════════════════════ 管理：进度/缓存 ═══════════════════════

    /** 重播：清除该条目播放进度（详情弹层"重播"按钮）。 */
    suspend fun resetProgress(mediaId: Long) = db.playbackProgressDao().clear(mediaId)

    /** 下载层缓存清理钩子（ServiceLocator 构建完 downloadManager 后注册；可为 null）。 */
    var clearCacheHook: (suspend () -> Unit)? = null

    /**
     * 清空缓存（设置页"清空缓存"）：缩略图目录 + 下载层 LRU 缓存（钩子委托）。
     * TDLib 自身文件目录不在清理范围（由 TDLib storage optimizer 管理）。
     */
    suspend fun clearCache() {
        val context = appContext ?: return
        withContext(Dispatchers.IO) {
            File(context.cacheDir, THUMB_DIR).deleteRecursively()
        }
        clearCacheHook?.invoke()
    }

    // ═══════════════════════ 内部：解析 ═══════════════════════

    /**
     * TDLib message → (MediaEntity, thumbnailFile?)；非视频/图片消息返回 null。
     * kind 判定：messageVideo=video；messagePhoto=image；messageDocument 且
     * mime 以 video/ 开头或后缀 ∈ {mp4,mkv,avi,mov,webm,ts,m4v} → video，其余 null。
     */
    private fun parseMedia(chatId: Long, driveId: Long, msg: JsonObject): Pair<MediaEntity, JsonObject?>? {
        // ── 临时诊断打点：每个失败出口一个稳定代号；video/文档/动画出口附带原始 content 样本 ──
        val messageId = msg["id"]?.jsonPrimitive?.longOrNull
            ?: return dropMedia(ScanDiagnostics.NO_ID)
        val messageDate = msg["date"]?.jsonPrimitive?.longOrNull
            ?: return dropMedia(ScanDiagnostics.NO_DATE)
        val firstSeen = ScanDiagnostics.message(messageId)
        val content = msg["content"] as? JsonObject
            ?: return dropMedia(ScanDiagnostics.NO_CONTENT)
        val contentType = (content["@type"] as? JsonPrimitive)?.content ?: ""
        ScanDiagnostics.record(contentType)
        if (firstSeen) ScanDiagnostics.recordUnique(contentType)
        ScanDiagnostics.keys(contentType, content.keys)
        // 只给 messageVideo 的后续出口留样本：890 条缺口就出在这一类里。
        val videoSample = content.takeIf { contentType == "messageVideo" }

        val fileObj: JsonObject
        val kind: String
        var displayName = ""
        var mime: String? = null
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var thumbFile: JsonObject? = null

        when (contentType) {
            "messageVideo" -> {
                // TDLib video 类型：文件字段名是 video_file（非 file）；无 file_name 字段；
                // 缩略图在 thumbnails:vector<photoSize>（photoSize.photo = file 对象）
                val video = content["video"] as? JsonObject
                    ?: return dropMedia(ScanDiagnostics.VIDEO_NO_VIDEO_OBJ, videoSample)
                ScanDiagnostics.keys("messageVideo.video", video.keys)
                // 实测 tdjson 1.8.65：video 对象的文件字段就是 "video"，
                // 旧版/文档中的 video_file 并不存在；兼容三者以防版本差异。
                fileObj = (video["video"] as? JsonObject
                    ?: video["video_file"] as? JsonObject
                    ?: video["file"] as? JsonObject)
                    ?: return dropMedia(ScanDiagnostics.VIDEO_NO_FILE, videoSample)
                ScanDiagnostics.keys("messageVideo.video.file", fileObj.keys)
                kind = MediaKind.VIDEO
                mime = (video["mime_type"] as? JsonPrimitive)?.content
                displayName = (video["file_name"] as? JsonPrimitive)?.content ?: ""
                durationMs = video["duration"]?.jsonPrimitive?.longOrNull?.times(1000)
                width = video["width"]?.jsonPrimitive?.longOrNull?.toInt()
                height = video["height"]?.jsonPrimitive?.longOrNull?.toInt()
                thumbFile = singleThumb(video["thumbnail"] as? JsonObject)
                    ?: smallestThumb(video["thumbnails"] as? JsonArray)
            }
            "messagePhoto" -> {
                val photo = content["photo"] as? JsonObject
                    ?: return dropMedia(ScanDiagnostics.PHOTO_NO_PHOTO)
                // photoSize.file 本身无宽高字段，宽高取自最大的 photoSize 块
                fileObj = photoMainFile(photo)
                    ?: return dropMedia(ScanDiagnostics.PHOTO_NO_FILE)
                kind = MediaKind.IMAGE
                photoMainSize(photo)?.let { biggest ->
                    width = biggest["width"]?.jsonPrimitive?.longOrNull?.toInt()
                    height = biggest["height"]?.jsonPrimitive?.longOrNull?.toInt()
                }
                thumbFile = photoThumbFile(photo)
            }
            "messageDocument" -> {
                val doc = content["document"] as? JsonObject
                    ?: return dropMedia(ScanDiagnostics.DOC_NO_DOC, content)
                ScanDiagnostics.keys("messageDocument.document", doc.keys)
                mime = (doc["mime_type"] as? JsonPrimitive)?.content
                displayName = (doc["file_name"] as? JsonPrimitive)?.content ?: ""
                val isVideo = mime?.startsWith("video/") == true ||
                    displayName.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXTENSIONS
                if (!isVideo) return dropMedia(ScanDiagnostics.DOC_NOT_VIDEO, content)
                // TDLib document 类型：文件字段名是 document_file（非 document）
                fileObj = (doc["document"] as? JsonObject
                    ?: doc["document_file"] as? JsonObject)
                    ?: return dropMedia(ScanDiagnostics.DOC_NO_FILE, content)
                ScanDiagnostics.keys("messageDocument.document.file", fileObj.keys)
                kind = MediaKind.VIDEO
                thumbFile = singleThumb(doc["thumbnail"] as? JsonObject)
                    ?: smallestThumb(doc["thumbnails"] as? JsonArray)
            }
            "messageAnimation" -> {
                // GIF/无声视频（TDLib animation 类型），按视频入库（多为 mp4/gif 可播）
                val anim = content["animation"] as? JsonObject
                    ?: return dropMedia(ScanDiagnostics.ANIM_NO_ANIM, content)
                ScanDiagnostics.keys("messageAnimation.animation", anim.keys)
                fileObj = (anim["animation"] as? JsonObject
                    ?: anim["animation_file"] as? JsonObject)
                    ?: return dropMedia(ScanDiagnostics.ANIM_NO_FILE, content)
                ScanDiagnostics.keys("messageAnimation.animation.file", fileObj.keys)
                kind = MediaKind.VIDEO
                mime = (anim["mime_type"] as? JsonPrimitive)?.content
                displayName = (anim["file_name"] as? JsonPrimitive)?.content ?: ""
                durationMs = anim["duration"]?.jsonPrimitive?.longOrNull?.times(1000)
                width = anim["width"]?.jsonPrimitive?.longOrNull?.toInt()
                height = anim["height"]?.jsonPrimitive?.longOrNull?.toInt()
                thumbFile = singleThumb(anim["thumbnail"] as? JsonObject)
                    ?: smallestThumb(anim["thumbnails"] as? JsonArray)
            }
            else -> return dropMedia(ScanDiagnostics.unknownType(contentType), content)
        }

        val remote = fileObj["remote"] as? JsonObject
            ?: return dropMedia(ScanDiagnostics.NO_REMOTE, videoSample)
        val remoteFileId = (remote["id"] as? JsonPrimitive)?.content
            ?: return dropMedia(ScanDiagnostics.NO_REMOTE_ID, videoSample)
        val fileUniqueId = (remote["unique_id"] as? JsonPrimitive)?.content
            ?: return dropMedia(ScanDiagnostics.NO_UNIQUE_ID, videoSample)
        val fileId = fileObj["id"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: return dropMedia(ScanDiagnostics.NO_FILE_ID, videoSample)
        val size = fileObj["expected_size"]?.jsonPrimitive?.longOrNull
            ?: fileObj["size"]?.jsonPrimitive?.longOrNull ?: 0L

        if (displayName.isBlank()) {
            displayName = if (kind == MediaKind.VIDEO) {
                String.format(Locale.US, "VIDEO_%d_%d.mp4", messageDate, messageId)
            } else {
                String.format(Locale.US, "IMG_%d_%d.jpg", messageDate, messageId)
            }
        }

        val entity = MediaEntity(
            driveId = driveId,
            chatId = chatId,
            messageId = messageId,
            kind = kind,
            fileId = fileId,
            remoteFileId = remoteFileId,
            fileUniqueId = fileUniqueId,
            displayName = displayName,
            originalName = displayName,
            mime = mime,
            size = size,
            durationMs = durationMs,
            width = width,
            height = height,
            messageDate = messageDate,
            // TDLib：非相册消息 media_album_id=0，归一化为 null；同相册多条消息共享同一 id
            mediaAlbumId = msg["media_album_id"]?.jsonPrimitive?.longOrNull?.takeIf { it != 0L },
            thumbPath = null,
            stale = false,
        )
        ScanDiagnostics.parsed(kind, firstSeen)
        return entity to thumbFile
    }

    /** 诊断专用：记一次失败原因（可选原始样本）。恒返回 null 供 `?: return` 使用；不改变解析结果。 */
    private fun dropMedia(reason: String, content: JsonObject? = null): Nothing? {
        ScanDiagnostics.drop(reason)
        if (content != null) ScanDiagnostics.sample(reason, content)
        return null
    }

    // ═══════════════════════ 内部：缩略图 ═══════════════════════

    private fun launchThumbJob(entity: MediaEntity, thumbFile: JsonObject?) {
        if (thumbFile == null || appContext == null) return
        scope.launch(Dispatchers.IO) {
            thumbSemaphore.withPermit {
                val path = downloadThumbnail(entity.fileUniqueId, thumbFile)
                if (path != null) mediaDao.updateThumbPath(entity.id, path)
            }
        }
    }

    /** getFile 快照：已下载完成则直接返回 local.path。 */
    private suspend fun currentLocalPath(fileId: Int): String? {
        return runCatching {
            val snapshot = td.tdCall(tdObj("@type" to "getFile", "file_id" to fileId))
            val local = snapshot["local"] as? JsonObject
            val completed = (local?.get("is_downloading_completed") as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() == true
            val path = (local?.get("path") as? JsonPrimitive)?.content
            if (completed && !path.isNullOrBlank()) path else null
        }.getOrNull()
    }

    /** 订阅 fileUpdates 等 local.is_downloading_completed=true 且 local.path 非空；20s 超时 null。 */
    private suspend fun waitForLocalPathUpdate(fileId: Int): String? =
        withTimeoutOrNull(THUMB_TIMEOUT_MS) {
            td.fileUpdates
                .filter { upd ->
                    (upd["file"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull?.toInt()) == fileId
                }
                .map { upd -> upd["file"] as? JsonObject }
                .firstOrNull { f ->
                    val local = f?.get("local") as? JsonObject
                    val completed = (local?.get("is_downloading_completed") as? JsonPrimitive)
                        ?.content?.toBooleanStrictOrNull() == true
                    val path = (local?.get("path") as? JsonPrimitive)?.content
                    completed && !path.isNullOrBlank()
                }
        }?.let { f ->
            (f["local"] as? JsonObject)?.let { local ->
                (local["path"] as? JsonPrimitive)?.content
            }
        }

    // ═══════════════════════ 内部：删除回执 ═══════════════════════

    /** 等 updateDeleteMessages 回执（chatUpdates 流；chat_id+message_id 命中且 is_permanent）。 */
    private suspend fun waitDeleteReceipt(chatId: Long, messageId: Long) {
        withTimeoutOrNull(DELETE_RECEIPT_TIMEOUT_MS) {
            td.chatUpdates
                .filter { upd ->
                    (upd["@type"] as? JsonPrimitive)?.content == "updateDeleteMessages" &&
                        (upd["from_chat_id"]?.jsonPrimitive?.longOrNull) == chatId &&
                        (upd["message_ids"]?.jsonArray
                            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                            ?.contains(messageId) == true) &&
                        (upd["is_permanent"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
                }
                .firstOrNull()
        }
    }

    // ═══════════════════════ 内部：TD 调用 ═══════════════════════

    /**
     * 带严格 FLOOD_WAIT 重试的调用（SPEC §8）：解析 FLOOD_WAIT_N 的 N，sleep 后重试
     * （至多 2 次）；N >120s 或非 FLOOD_WAIT 错误直接上抛。
     */
    private suspend fun callWithFloodRetry(query: JsonObject, maxRetries: Int = 2): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return td.tdCall(query)
            } catch (e: TdRequestException) {
                val suggested: Int? = e.message
                    ?.substringAfter("FLOOD_WAIT_", "")
                    ?.takeWhile { it.isDigit() }
                    ?.toString()
                    ?.toIntOrNull()
                    ?: if (e.code == 429) 5 else null
                if (attempt >= maxRetries || suggested == null || suggested > 120) throw e
                delay(suggested.toLong() * 1000L)
                attempt++
            }
        }
    }

    companion object {
        private const val TAG = "MediaRepository"


        private const val SCAN_PAGE_SIZE = 100
        private const val PAGE_DELAY_MS = 150L
        private const val THUMB_PRIORITY = 2
        private const val THUMB_DIR = "thumbs"
        private const val THUMB_TIMEOUT_MS = 20_000L
        private const val DELETE_RECEIPT_TIMEOUT_MS = 30_000L

        /** 空页重试上限：TDLib 刚登录/重启时历史未落盘，getChatHistory 会先返回空。 */
        /** 短页重试上限：TDLib 返回不足一页并不代表到头，只是服务端历史还没拉够。 */
        private const val SHORT_PAGE_RETRIES = 30
        private const val EMPTY_PAGE_RETRIES = 30
        /** 空页重试间隔（给 TDLib 后台拉历史的时间）。 */
        private const val EMPTY_PAGE_DELAY_MS = 2_000L
        /** 短页重试间隔：给 TDLib 从服务端拉更早历史的时间。 */
        private const val SHORT_PAGE_DELAY_MS = 2_500L

        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v")

        private fun longJsonArray(vararg values: Long) =
            JsonArray(values.map { JsonPrimitive(it) })

        /** 从任意消息 JsonObject 抽取 video/photo/document 的主 file 对象（freshFileId 用）。 */
        private fun extractFileObject(msg: JsonObject): JsonObject? {
            val content = msg["content"] as? JsonObject ?: return null
            return when ((content["@type"] as? JsonPrimitive)?.content) {
                // 实测 tdjson 1.8.65 的文件字段名就是类型名本身：
                // video.video / document.document / animation.animation
                // （旧版 video_file/document_file/animation_file 一并兼容）
                "messageVideo" -> content["video"]?.jsonObject?.let { v ->
                    (v["video"] as? JsonObject)
                        ?: (v["video_file"] as? JsonObject)
                        ?: (v["file"] as? JsonObject)
                }
                "messagePhoto" -> content["photo"]?.jsonObject?.let { photoMainFile(it) }
                "messageDocument" -> content["document"]?.jsonObject?.let { d ->
                    (d["document"] as? JsonObject)
                        ?: (d["document_file"] as? JsonObject)
                }
                "messageAnimation" -> content["animation"]?.jsonObject?.let { a ->
                    (a["animation"] as? JsonObject)
                        ?: (a["animation_file"] as? JsonObject)
                }
                else -> null
            }
        }

        /** video/document/animation 的 thumbnails 数组里取最小 photoSize 的 photo（file 对象）。 */
        /**
         * 单个缩略图对象 → 真正的 file 对象。两种 TDLib 形状都要兼容：
         *  - thumbnail  (video/document/animation)：file 字段
         *  - photoSize  (photo.sizes)：photo 字段
         */
        private fun singleThumb(ps: JsonObject?): JsonObject? {
            if (ps == null) return null
            (ps["file"] as? JsonObject)?.let { return it }
            (ps["photo"] as? JsonObject)?.let { return it }
            return null
        }

        private fun smallestThumb(thumbnails: JsonArray?): JsonObject? =
            thumbnails?.mapNotNull { it as? JsonObject }
                ?.minByOrNull { s ->
                    (s["width"]?.jsonPrimitive?.longOrNull ?: 0L) *
                        (s["height"]?.jsonPrimitive?.longOrNull ?: 0L)
                }
                ?.get("photo") as? JsonObject

        /** photo.sizes 里最大的 photoSize 的 photo 字段（媒体主文件：file_id/remote/大小来源）。 */
        private fun photoMainFile(photo: JsonObject): JsonObject? =
            photoMainSize(photo)?.get("photo") as? JsonObject

        private fun photoMainSize(photo: JsonObject): JsonObject? =
            photo["sizes"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.maxByOrNull { s ->
                    (s["width"]?.jsonPrimitive?.longOrNull ?: 0L) *
                        (s["height"]?.jsonPrimitive?.longOrNull ?: 0L)
                }

        /** 缩略图取最小尺寸 photoSize 的 photo（3 列海报墙小图足够，几十 KB）。 */
        private fun photoThumbFile(photo: JsonObject): JsonObject? =
            photo["sizes"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.minByOrNull { s ->
                    (s["width"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE) *
                        (s["height"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE)
                }
                ?.get("photo") as? JsonObject
    }
}

