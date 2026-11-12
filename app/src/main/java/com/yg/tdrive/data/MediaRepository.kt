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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
import kotlinx.serialization.json.contentOrNull
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

    /** 本轮已派发的缩略图作业（分阶段补封面时 join，保证「先图片后视频」）。 */
    private val thumbJobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()


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
     * 循环翻页到头部；每页 delay(150) 节流。
     *
     * 翻页正确性（本轮修复）：
     * - 每轮记录已处理的 message_id 集合，**同一页永不重复解析**（翻页返回包含起始
     *   message_id，边界条目天然重复一次，被去重）。
     * - 只要本页带来新 id 就继续向更老推进；返回不足一页且**没有新 id** 才算「TDLib
     *   已无更老历史」；仅此处做最多 SHORT_PAGE_RETRIES 次原地重试。
     * - 只有 `messages.isEmpty()` 才是「到达头部」的确认信号 → DONE + last_scanned=本轮最新 id。
     * - 重试耗尽 = 无法确认头部 → ERROR，last_scanned 只写到本轮到达的最老 id，
     *   绝不写 newestId（否则未扫段会被 incrementalSync 的幂等短路永久跳过）。
     *
     * 扫完清理 stale 行，然后按「阶段1 图片封面 → 阶段2 视频相册封面/自身封面」补封面，
     * 最后落 scan_state。
     */
    suspend fun fullScan(driveId: Long) {
        val drive = driveDao.getById(driveId) ?: return
        driveDao.setScanState(driveId, ScanState.SCANNING)
        ScanDiagnostics.begin("fullScan", driveId)
        var scanned = 0
        var inserted = 0
        var emptyRetries = 0
        var stuckRetries = 0
        var newestId = 0L
        var oldestReached = 0L
        val processedIds = HashSet<Long>()
        // messageId → 本轮解析到的缩略图 file 对象：给「库里已存在但缺封面」的行重试用，
        // 避免为了拿 thumbnail.file 再发一轮 getMessage。
        val roundThumbs = HashMap<Long, JsonObject>()
        publishProgress(ScanProgress(driveId = driveId, running = true))
        try {
            val chatId = drive.chatId
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
                    // 唯一可确认「到头」的信号。首轮空页仍可能是 TDLib 历史未落盘。
                    if (processedIds.isEmpty()) {
                        if (emptyRetries < EMPTY_PAGE_RETRIES) {
                            emptyRetries++
                            publishProgress(
                                ScanProgress(driveId, true, scanned, inserted, "等待 Telegram 下发历史…")
                            )
                            delay(EMPTY_PAGE_DELAY_MS)
                            continue
                        }
                        throw IllegalStateException(
                            "历史未就绪：连续 $EMPTY_PAGE_RETRIES 次空页，本轮中止（可重扫重试）"
                        )
                    }
                    break
                }
                emptyRetries = 0

                val pageIds = messages.mapNotNull {
                    it["id"]?.jsonPrimitive?.longOrNull
                }
                val fresh = messages.filter { msg ->
                    val id = msg["id"]?.jsonPrimitive?.longOrNull
                    id != null && id !in processedIds
                }
                if (fresh.isEmpty()) {
                    // 同页重复返回且没有任何新 id：TDLib 已给不出更老的历史。
                    // 不解析、不入库（保持 parsedByKind 可信），只短暂等待并用尽重试上限。
                    stuckRetries++
                    if (stuckRetries > SHORT_PAGE_RETRIES) {
                        throw ScanIncompleteException(
                            "翻页无新消息：连续 $SHORT_PAGE_RETRIES 次重复同页（from=$fromMessageId），头部未确认"
                        )
                    }
                    publishProgress(
                        ScanProgress(driveId, true, scanned, inserted, "等待更早的历史…")
                    )
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                stuckRetries = 0
                processedIds.addAll(pageIds)
                pageIds.forEach { id ->
                    if (id > newestId) newestId = id
                    if (oldestReached == 0L || id < oldestReached) oldestReached = id
                }
                val ingested = ingestPage(chatId, driveId, fresh)
                roundThumbs.putAll(ingested.thumbs)
                inserted += ingested.inserted
                scanned += fresh.size
                publishProgress(ScanProgress(driveId, true, scanned, inserted))

                val pageOldestId = pageIds.minOrNull() ?: break
                fromMessageId = pageOldestId
                delay(PAGE_DELAY_MS)
            }
            mediaDao.deleteStaleByDrive(driveId)

            // 两阶段补封面（顺序关键）：图片全部落盘后，视频才能拿同组图封面。
            runCoverPhases(driveId, roundThumbs, scanned, inserted)

            val missing = mediaDao.countMissingThumb(driveId)
            if (missing > 0) Log.w(TAG, "drive=$driveId 仍有 $missing 条缺封面")
            // 诊断：本轮结束后 DB 里 video/image 的真实行数（含往轮遗留），
            // 与 parsedUniqueByKind 对比即可分清「解析丢」还是「重复页虚增」。
            ScanDiagnostics.dbKindCounts(
                mediaDao.getAllByDrive(driveId).groupingBy { it.kind }.eachCount()
            )
            driveDao.setLastScannedMessageId(driveId, newestId)
            driveDao.setScanState(driveId, ScanState.DONE)
        } catch (e: ScanIncompleteException) {
            // 未确认头部：保留真实的续扫边界（最老已到达 id），标记 ERROR 而非 DONE。
            // 已入库部分仍按两阶段补封面（幂等，下一轮会继续收敛）。
            Log.e(TAG, "fullScan drive=$driveId incomplete: ${e.message}")
            ScanDiagnostics.error(e.message ?: "incomplete")
            runCoverPhases(driveId, roundThumbs, scanned, inserted)
            ScanDiagnostics.dbKindCounts(
                mediaDao.getAllByDrive(driveId).groupingBy { it.kind }.eachCount()
            )
            if (newestId > 0L) driveDao.setLastScannedMessageId(driveId, newestId)
            driveDao.setScanState(driveId, ScanState.ERROR)
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
     * 增量同步（SPEC §4.2 第4步）：从聊天最新消息往下扫，**扫到库里已有的旧消息就停**
     * （或碰到检查点 last_scanned_message_id 为止），只把新消息入库。
     *
     * 关键约束（修复「下滑一次就全量重扫」）：
     * - 检查点是「已索引区间的上界」，只前进不回退。
     * - 历史没扫全（无检查点 / scan_state != done）时，下拉刷新只取最新一页，
     *   全量交给设置页「重扫」，绝不在这里静默走完整段历史。
     * - 无新消息时幂等返回。
     */
    suspend fun incrementalSync(driveId: Long) {
        val drive = driveDao.getById(driveId) ?: return
        driveDao.setScanState(driveId, ScanState.SCANNING)
        ScanDiagnostics.begin("incrementalSync", driveId)
        var scanned = 0
        var inserted = 0
        var emptyRetries = 0
        var stuckRetries = 0
        val processedIds = HashSet<Long>()
        val roundThumbs = HashMap<Long, JsonObject>()
        publishProgress(ScanProgress(driveId = driveId, running = true))
        try {
            val chatId = drive.chatId
            val stopId = drive.lastScannedMessageId
            val wasFullyScanned = !IncrementalScanPolicy.needsFullScan(stopId, drive.scanState)
            // 没有检查点（从未建索引 / 历史没扫全）时，下拉刷新只取最新一页，
            // 绝不在这里悄悄走完整段历史 —— 全量走设置页「重扫」。
            val pageBudget = if (wasFullyScanned) Int.MAX_VALUE else 1
            val chat = td.tdCall(tdObj("@type" to "getChat", "chat_id" to chatId))
            val lastMessage = chat["last_message"] as? JsonObject
            if (lastMessage == null) {
                if (wasFullyScanned) driveDao.setScanState(driveId, ScanState.DONE)
                return
            }
            val newestId = lastMessage["id"]?.jsonPrimitive?.longOrNull ?: 0L
            if (newestId <= stopId) {
                if (wasFullyScanned) driveDao.setScanState(driveId, ScanState.DONE)
                return
            }
            var fromMessageId = newestId
            var pagesFetched = 0
            while (true) {
                if (pagesFetched >= pageBudget) break
                pagesFetched++
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
                    // 重试耗尽：本轮未扫完，标记 ERROR 而非 DONE
                    throw IllegalStateException(
                        "历史未就绪：连续 $EMPTY_PAGE_RETRIES 次空页，本轮中止（可重扫重试）"
                    )
                }
                emptyRetries = 0

                val pageIds = messages.mapNotNull {
                    it["id"]?.jsonPrimitive?.longOrNull
                }
                val fresh = messages.filter { msg ->
                    val id = msg["id"]?.jsonPrimitive?.longOrNull
                    id != null && id !in processedIds
                }
                if (fresh.isEmpty()) {
                    stuckRetries++
                    if (stuckRetries > SHORT_PAGE_RETRIES) {
                        throw ScanIncompleteException(
                            "翻页无新消息：连续 $SHORT_PAGE_RETRIES 次重复同页（from=$fromMessageId），未到达 stopId=$stopId"
                        )
                    }
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                stuckRetries = 0
                processedIds.addAll(pageIds)

                // 「从最新的往下扫，扫到旧的为止」：本页出现库里已有的消息即停，
                // 而不是每次下滑把所有历史重新走一遍。
                val knownIds = mediaDao.existingMessageIds(chatId, pageIds.filter { it > 0L })
                val stopHere =
                    IncrementalScanPolicy.reachedOldMessages(pageIds, stopId) ||
                        IncrementalScanPolicy.reachedKnownMessages(pageIds, knownIds.toSet())
                val toIngest = fresh.filter {
                    (it["id"]?.jsonPrimitive?.longOrNull ?: 0L) > stopId
                }
                scanned += fresh.size
                if (toIngest.isNotEmpty()) {
                    val ingested = ingestPage(chatId, driveId, toIngest)
                    inserted += ingested.inserted
                    roundThumbs.putAll(ingested.thumbs)
                }
                publishProgress(ScanProgress(driveId, true, scanned, inserted))

                if (stopHere) break
                fromMessageId = pageIds.minOrNull() ?: break
                delay(PAGE_DELAY_MS)
            }
            // 有新增行才跑封面阶段（增量无新内容时避免全表相册子查询）。
            if (inserted > 0) runCoverPhases(driveId, roundThumbs, scanned, inserted)
            ScanDiagnostics.dbKindCounts(
                mediaDao.getAllByDrive(driveId).groupingBy { it.kind }.eachCount()
            )
            // 检查点只前进到当前最新消息（上界），失败/未扫全时保持原值，
            // 不回退到「本轮到达的最老 id」—— 那会让下次下拉重复走老消息。
            driveDao.setLastScannedMessageId(
                driveId,
                IncrementalScanPolicy.checkpointAfterSuccess(stopId, newestId),
            )
            // 只有原本历史已扫全的驱动器才保持 DONE；否则不要谎报完成。
            if (!IncrementalScanPolicy.needsFullScan(stopId, drive.scanState)) {
                driveDao.setScanState(driveId, ScanState.DONE)
            }
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

    /** 单页解析入库结果：新行数 + messageId→thumbnail.file（供分阶段补封面重试）。 */
    private data class PageIngest(
        val inserted: Int,
        val thumbs: Map<Long, JsonObject>,
    )

    /**
     * 单页入库：解析 → IGNORE 幂等插入（回读 rowId）→ 异步补抓缩略图。
     * 调用方保证 `messages` 里没有本轮已处理过的 id（重复调用不会重复解析/下载）。
     */
    private suspend fun ingestPage(chatId: Long, driveId: Long, messages: List<JsonObject>): PageIngest {
        val parsedRaw = messages.mapNotNull { parseMedia(chatId, driveId, it) }
        // ── 相册命名规则 ──
        // 同一 media_album_id 的整组消息若带文字描述（caption），视频一律以该描述命名
        // （不带文件后缀）；组内没有文字描述时才回退到视频自身的文件名。
        // 相册的 caption 通常只挂在其中一条消息上，所以必须按组跨消息汇总后再统一命名。
        val albumCaptions: Map<Long, String> = buildMap {
            for (msg in messages) {
                val albumId = msg["media_album_id"]?.jsonPrimitive?.longOrNull
                    ?.takeIf { it != 0L } ?: continue
                if (containsKey(albumId)) continue
                // TDLib 的 caption 不是 message 顶层字段，而是挂在 content 的媒体对象上
                // （video.caption / photo.caption = formattedText{text}）；相册里通常
                // 只有其中一条带描述，所以要跨整组消息汇总。
                val cap = captionOf(msg)?.let { sanitizeCaption(it) }
                if (!cap.isNullOrBlank()) put(albumId, cap)
            }
        }
        // 相册描述可能只在相邻扫描页的一张图片上；先从本地已入库行继承，
        // 再用本页真实 caption 覆盖。不要把显示名当 original_name 改写。
        val albumIds = parsedRaw.mapNotNull { it.first.mediaAlbumId }.distinct()
        val knownCaptions = albumIds.mapNotNull { id ->
            mediaDao.albumVideoCaption(driveId, id)?.let { id to it }
        }.toMap()
        val resolvedCaptions = knownCaptions + albumCaptions
        val parsed = parsedRaw.map { pair ->
            val entity = pair.first
            if (entity.kind != MediaKind.VIDEO) return@map pair
            val cap = entity.mediaAlbumId?.let { resolvedCaptions[it] } ?: return@map pair
            pair.copy(first = entity.copy(displayName = cap))
        }
        if (parsed.isEmpty()) {
            albumCaptions.forEach { (albumId, caption) ->
                mediaDao.applyAlbumCaption(driveId, albumId, caption)
            }
            return PageIngest(0, emptyMap())
        }
        val entities = parsed.map { it.first }
        val rowIds = withContext(Dispatchers.IO) { mediaDao.insertAllIgnoring(entities) }
        albumCaptions.forEach { (albumId, caption) ->
            mediaDao.applyAlbumCaption(driveId, albumId, caption)
        }
        val thumbs = HashMap<Long, JsonObject>()
        parsed.forEachIndexed { i, (entity, thumb) ->
            if (thumb != null) thumbs[entity.messageId] = thumb
            // rowId == -1L：已存在的行（重扫），入库时不派发下载；缺封面由阶段1/2b 补
            if (rowIds[i] != -1L) launchThumbJob(entity.copy(id = rowIds[i]), thumb)
        }
        return PageIngest(rowIds.count { it != -1L }, thumbs)
    }

    // ═══════════════════════ 封面：两阶段（相册优先） ═══════════════════════

    /**
     * 串起两阶段封面补齐（顺序不可换）：
     * 阶段1 图片封面落盘 → 阶段2a 视频改用同相册首图封面 → 阶段2b 无相册图的视频补自身封面。
     * 幂等：重复执行不产生新文件，只在值变化时 UPDATE。补封面失败不影响扫描状态。
     */
    private suspend fun runCoverPhases(
        driveId: Long,
        thumbs: Map<Long, JsonObject>,
        scanned: Int,
        inserted: Int,
    ) {
        try {
            publishProgress(ScanProgress(driveId, true, scanned, inserted, "补图片封面…"))
            fillImageCovers(driveId, thumbs)
            publishProgress(ScanProgress(driveId, true, scanned, inserted, "分配相册封面…"))
            assignAlbumCovers(driveId)
            fillVideoCovers(driveId, thumbs)
            recordCoverStats(driveId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "补封面阶段失败 drive=$driveId", t)
        }
    }

    /**
     * 阶段1：先把图片封面补齐。
     * 等本轮已派发的图片下载落盘后，对仍缺封面的图片行用本轮解析到的 thumbnail.file
     * 再试一次（老行入库被 IGNORE，不会派发下载，这是它们一直缺封面的原因之一）。
     */
    private suspend fun fillImageCovers(driveId: Long, thumbs: Map<Long, JsonObject>) {
        awaitThumbJobs()
        val missing = withContext(Dispatchers.IO) { mediaDao.imagesMissingThumb(driveId) }
        var retried = 0
        for (row in missing) {
            val thumb = thumbs[row.messageId] ?: continue
            launchThumbJob(row, thumb)
            retried++
        }
        if (retried > 0) {
            Log.i(TAG, "阶段1 补图片封面: drive=$driveId 重试=$retried/${missing.size}")
            awaitThumbJobs()
        }
    }

    /**
     * 阶段2a：视频封面一律改用同相册组内 `message_id` 最小的图片封面
     * （用户定案：视觉统一，即使视频自身已有封面也覆盖）。SQL 见 MediaDao.videoAlbumCovers。
     * 幂等：updateThumbPathIfChanged 只在值变化时写。
     */
    private suspend fun assignAlbumCovers(driveId: Long) {
        val covers = withContext(Dispatchers.IO) { mediaDao.videoAlbumCovers(driveId) }
        var assigned = 0
        for (cover in covers) {
            val path = cover.thumbPath ?: continue
            val n = withContext(Dispatchers.IO) {
                mediaDao.updateThumbPathIfChanged(cover.videoId, path)
            }
            if (n > 0) assigned++
        }
        Log.i(TAG, "阶段2 相册封面: drive=$driveId 分配=$assigned/${covers.size}")
    }

    /**
     * 阶段2b：没有相册图封面的视频回退自身 `thumbnail`。
     * 只处理 thumb_path 仍为 NULL 的行：已有相册封面/已成功下载的绝不覆盖。
     */
    private suspend fun fillVideoCovers(driveId: Long, thumbs: Map<Long, JsonObject>) {
        val missing = withContext(Dispatchers.IO) { mediaDao.videosMissingThumb(driveId) }
        var retried = 0
        for (row in missing) {
            val thumb = thumbs[row.messageId] ?: continue
            launchThumbJob(row, thumb)
            retried++
        }
        if (retried > 0) {
            Log.i(TAG, "阶段2 补视频自身封面: drive=$driveId 重试=$retried/${missing.size}")
            awaitThumbJobs()
        }
    }

    /** 落盘本次封面统计（诊断报告 covers 段；人工验收「视频缺封面数下降」用）。 */
    private suspend fun recordCoverStats(driveId: Long) {
        val stats = withContext(Dispatchers.IO) {
            mapOf(
                "videoMissingThumb" to mediaDao.countMissingThumbByKind(driveId, MediaKind.VIDEO),
                "imageMissingThumb" to mediaDao.countMissingThumbByKind(driveId, MediaKind.IMAGE),
                "videoAlbumCover" to mediaDao.videoAlbumCovers(driveId).count { it.thumbPath != null },
            )
        }
        ScanDiagnostics.covers(stats)
    }

    /**
     * 等本轮已派发的缩略图作业结束。单个作业内部有 20s 超时，这里再加整体兜底，
     * 避免异常情况下扫描状态永远停在 SCANNING。
     */
    private suspend fun awaitThumbJobs() {
        val jobs = ArrayList<Job>()
        while (true) {
            val job = thumbJobs.poll() ?: break
            jobs.add(job)
        }
        if (jobs.isEmpty()) return
        withTimeoutOrNull(THUMB_PHASE_TIMEOUT_MS) { jobs.joinAll() }
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
                // 同一历史页里图片的 caption 也要用于已经存在的视频。
                val captions = messages.mapNotNull { msg ->
                    val id = msg["media_album_id"]?.jsonPrimitive?.longOrNull
                        ?.takeIf { it != 0L } ?: return@mapNotNull null
                    val text = captionOf(msg)?.let(::sanitizeCaption).orEmpty()
                    if (text.isBlank()) null else id to text
                }.toMap()
                captions.forEach { (id, caption) ->
                    mediaDao.applyAlbumCaption(driveId, id, caption)
                }
                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: break
                if (lastId == 0L) break
                if (messages.size < SCAN_PAGE_SIZE) {
                    // 回填只在安装后跑一次，宁可多等 TDLib 补齐历史也别漏段：
                    // 用独立的（较大的）重试上限，不受扫描侧 SHORT_PAGE_RETRIES=1 影响。
                    shortRetries++
                    if (shortRetries > BACKFILL_SHORT_PAGE_RETRIES) break
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                shortRetries = 0
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
            Log.i(TAG, "相册回填: drive=$driveId 累计=$done")
        }
        // 注意：done 只统计真正写入非空 album_id 的行（见 MediaDao.backfillAlbumId 的
        // `:albumId IS NOT NULL`）。剩余缺相册的行既可能是本轮未覆盖，也可能是本身
        // 就不是相册消息（media_album_id 在 TDLib 里为 0 → 归一化为 NULL），两者用下面的
        // left 值 + 报告里的 totalMessages 对比判断。
        val left = runCatching { mediaDao.countMissingAlbum() }.getOrDefault(-1)
        Log.i(TAG, "相册回填完成: 实际填充=$done 总缺=$total 剩余缺=$left")
        return done
    }

    /**
     * 按组内文字描述重命名历史视频（老数据回填）。
     *
     * 为什么必须单独回填：旧包入库时没有写描述名，而 `insertAllIgnoring` 不会更新已存在行，
     * 所以即便新包扫描同一页也无法修正老行 —— 必须重新走一遍历史页，把同组描述批量应用。
     * 只改 `display_name == original_name` 的行，用户手动改过的名字不动。
     *
     * @return 实际改名的行数。
     */
    suspend fun backfillAlbumCaptions(
        onProgress: (pages: Int, renamed: Int) -> Unit = { _, _ -> },
    ): Int {
        var renamed = 0
        var pages = 0
        var captioned = 0
        var capDiagSamples = 0
        val capDiag = StringBuilder()
        val drives = withContext(Dispatchers.IO) { driveDao.getAll() }
        for (drive in drives) {
            var fromMessageId = 0L
            var emptyRetries = 0
            var shortRetries = 0
            while (true) {
                val resp = runCatching {
                    callWithFloodRetry(
                        tdObj(
                            "@type" to "getChatHistory",
                            "chat_id" to drive.chatId,
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
                // 诊断：统计本页有多少条消息真带 caption，以及前几条的原始结构。
                // 目的：历史数据 0 改名时，区分「描述根本不在返回里」和「解析/写库出错」。
                // 走文件而不是 logcat：这台机器上 logcat 读不到本应用的日志。
                for (msg in messages) {
                    val ct = ((msg["content"] as? JsonObject)?.get("@type") as? JsonPrimitive)?.content ?: "?"
                    val rawCap = captionOf(msg)
                    if (!rawCap.isNullOrBlank()) captioned++
                    if (capDiagSamples < 6) {
                        capDiagSamples++
                        val keys = (msg["content"] as? JsonObject)?.keys?.joinToString(",") ?: "-"
                        capDiag.append(
                            "type=$ct album=${msg["media_album_id"]?.jsonPrimitive?.longOrNull}" +
                                " capLen=${rawCap?.length ?: -1} cap=${rawCap?.take(50)} keys=$keys\n",
                        )
                    }
                }
                emptyRetries = 0
                // 同一相册的描述可能挂在图片上，视频自己为空 —— 整页先聚合成
                // albumId -> caption，再一次性批量改写该组所有视频。
                val captions = messages.mapNotNull { msg ->
                    val id = msg["media_album_id"]?.jsonPrimitive?.longOrNull
                        ?.takeIf { it != 0L } ?: return@mapNotNull null
                    val text = captionOf(msg)?.let(::sanitizeCaption).orEmpty()
                    if (text.isBlank()) null else id to text
                }.toMap()
                if (captions.isNotEmpty()) {
                    for ((albumId, caption) in captions) {
                        val n = withContext(Dispatchers.IO) {
                            mediaDao.applyAlbumCaption(drive.id, albumId, caption)
                        }
                        renamed += n
                    }
                }
                pages++
                onProgress(pages, renamed)
                // 每页落盘一次：回填耗时长，中途也能看到进度与样本
                appContext?.let { ctx ->
                    runCatching {
                        File(ctx.filesDir, "caption-diag.txt").writeText(
                            "页数=$pages 改名行数=$renamed 带描述消息=$captioned\n$capDiag",
                        )
                    }
                }
                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: break
                if (lastId == 0L) break
                if (messages.size < SCAN_PAGE_SIZE) {
                    shortRetries++
                    if (shortRetries > BACKFILL_SHORT_PAGE_RETRIES) break
                    delay(SHORT_PAGE_DELAY_MS)
                    continue
                }
                shortRetries = 0
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
        }
        val summary = "页数=$pages 改名行数=$renamed 带描述消息=$captioned\n$capDiag"
        Log.i(TAG, "描述改名回填完成: $summary")
        appContext?.let { ctx ->
            runCatching { File(ctx.filesDir, "caption-diag.txt").writeText(summary) }
                .onFailure { Log.w(TAG, "写 caption-diag.txt 失败", it) }
        }
        return renamed
    }

    /** 文件名搜索：中文和单字走 LIKE 子串查询，拉丁词走 FTS 前缀索引。 */
    suspend fun search(q: String): List<MediaItem> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        return if (SearchQueryPolicy.useLike(query)) {
            val pattern = SearchQueryPolicy.likePattern(query)
            runCatching { mediaDao.searchLike(pattern) }
                .onFailure { Log.w(TAG, "searchLike failed: $pattern", it) }
                .getOrDefault(emptyList())
        } else {
            val ftsQuery = SearchQueryPolicy.ftsQuery(query)
            runCatching { mediaDao.search(ftsQuery) }
                .onFailure { Log.w(TAG, "search failed: $ftsQuery", it) }
                .getOrDefault(emptyList())
        }
    }

    /** 从本地索引标题生成词云。 */
    suspend fun keywordCloud(): List<KeywordCloud.Word> = withContext(Dispatchers.IO) {
        KeywordCloud.fromNames(mediaDao.recentDisplayNames(3000))
    }

    /** 全局分页搜索：中文/单字可匹配文件名的任意位置。 */
    suspend fun searchAll(
        query: String,
        kind: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        if (SearchQueryPolicy.useLike(q)) {
            val pattern = SearchQueryPolicy.likePattern(q)
            runCatching { mediaDao.searchLikeAll(pattern, kind, limit, offset) }
                .onFailure { Log.w(TAG, "searchLikeAll failed: $pattern", it) }
                .getOrDefault(emptyList())
        } else {
            val ftsQuery = SearchQueryPolicy.ftsQuery(q)
            runCatching { mediaDao.searchAll(ftsQuery, kind, limit, offset) }
                .onFailure { Log.w(TAG, "searchAll failed: $ftsQuery", it) }
                .getOrDefault(emptyList())
        }
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
     * 用持久化的 remote id 换当前 TDLib 会话的 fresh file_id。
     *
     * 根因：TDLib 的 file id 只在**当前库会话**内有效，App 重启/重装后旧 id 一律
     * 报 `400: File not found`。媒体表里存的是扫描当时的 id，所以离线索引一旦跨
     * 会话复用（看图拿原图、缩略图重下）就必须先换 id。getRemoteFile 只认
     * remote id，不需要 chat 权限、不发网络请求，比 getMessage 更轻。
     *
     * 成功回写 fileId/size 并清 stale；换不到返回 -1（调用方保留旧的失败态）。
     */
    suspend fun liveFileId(item: MediaItem): Int {
        val remoteId = item.remoteFileId.takeIf { it.isNotBlank() } ?: return -1
        return try {
            val fileType = when (item.kind) {
                "image" -> "fileTypePhoto"
                "video" -> "fileTypeVideo"
                else -> "fileTypeNone"
            }
            val fileObj = td.tdCall(
                tdObj(
                    "@type" to "getRemoteFile",
                    "remote_file_id" to remoteId,
                    "file_type" to tdObj("@type" to fileType),
                )
            )
            val fileId = fileObj["id"]?.jsonPrimitive?.longOrNull?.toInt() ?: return -1
            val size = fileObj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            withContext(Dispatchers.IO) {
                mediaDao.upsertAll(
                    listOf(
                        item.copy(
                            fileId = fileId,
                            size = if (size > 0L) size else item.size,
                            stale = false,
                        )
                    )
                )
            }
            fileId
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "liveFileId remote=${item.remoteFileId} failed", t)
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
    /**
     * 相册描述清洗成可用作显示名/文件名的字符串：压平换行、去掉路径与非法字符、
     * 折叠重复空白、限制长度。描述为空时返回空串（调用方回退原文件名）。
     */
    private fun sanitizeCaption(raw: String): String {
        val flat = raw.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace('/', '／').replace('\\', '＼')
            .replace(Regex("[\\p{Cntrl}]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (flat.isEmpty()) return ""
        return if (flat.length > 120) flat.take(120).trimEnd() else flat
    }

    /**
     * 取消息的相册/媒体描述正文。位置判断见 [CaptionParser]（caption 可能在 content 顶层，
     * 也可能在媒体对象内部；只看后者会漏掉全部相册描述）。
     */
    private fun captionOf(msg: JsonObject): String? =
        CaptionParser.captionOf(msg["content"] as? JsonObject)

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
        val job = scope.launch(Dispatchers.IO) {
            thumbSemaphore.withPermit {
                val path = downloadThumbnail(entity.fileUniqueId, thumbFile)
                // 幂等写：值未变化就不 UPDATE，避免扫描期间每次封面落盘都
                // invalidate 一次 PagingSource（UI 层会表现为列表反复重建/屏闪）。
                if (path != null) mediaDao.updateThumbPathIfChanged(entity.id, path)
            }
        }
        thumbJobs.add(job)
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
        private const val EMPTY_PAGE_RETRIES = 30
        /** 相册回填的同页重试上限（一次性作业，允许慢，保持原有 30 次）。 */
        private const val BACKFILL_SHORT_PAGE_RETRIES = 30
        /**
         * 同页无新 id 的原地重试上限。上限 1 = 同一 from_message_id 最多请求 2 次
         * （验收 maxPageRepeats ≤ 2）；每次不再解析页面，只等 TDLib 补更早历史。
         */
        private const val SHORT_PAGE_RETRIES = 1
        /** 空页重试间隔（给 TDLib 后台拉历史的时间）。 */
        private const val EMPTY_PAGE_DELAY_MS = 2_000L
        /** 短页重试间隔：给 TDLib 从服务端拉更早历史的时间。 */
        private const val SHORT_PAGE_DELAY_MS = 2_500L
        /** 分阶段补封面的整体等待上限（单作业内部另有 20s 超时）。 */
        private const val THUMB_PHASE_TIMEOUT_MS = 180_000L

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


/**
 * 翻页未确认到达头部（同页重复返回且重试耗尽）。
 * 单独成类：fullScan/incrementalSync 在此出口必须写 ScanState.ERROR + 真实续扫边界，
 * 绝不能落到 DONE（历史缺口会被增量同步的幂等短路永久跳过）。
 */
private class ScanIncompleteException(message: String) : Exception(message)
