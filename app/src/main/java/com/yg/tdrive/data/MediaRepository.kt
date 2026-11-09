package com.yg.tgdrive.data

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.tdObj
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

    // ═══════════════════════ 扫描 ═══════════════════════

    /**
     * 全量首扫（SPEC §4.2 第1~3步）：getChatHistory(chat_id, from_message_id=0, limit=100)
     * 循环翻页到头部（返回数 < limit 即头部）；每页 delay(150) 节流。
     * 扫完清理 stale 行、写 last_scanned_message_id、置 scan_state=done。
     */
    suspend fun fullScan(driveId: Long) {
        val drive = driveDao.getById(driveId) ?: return
        driveDao.setScanState(driveId, ScanState.SCANNING)
        try {
            val chatId = drive.chatId
            var newestId = 0L
            var fromMessageId = 0L
            while (true) {
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
                if (messages.isEmpty()) break

                if (newestId == 0L) {
                    newestId = messages.first()["id"]?.jsonPrimitive?.longOrNull ?: 0L
                }
                ingestPage(chatId, driveId, messages)

                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: 0L
                if (messages.size < SCAN_PAGE_SIZE || lastId == 0L) break   // 到头部
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
            mediaDao.deleteStaleByDrive(driveId)
            driveDao.setLastScannedMessageId(driveId, newestId)
            driveDao.setScanState(driveId, ScanState.DONE)
        } catch (t: Throwable) {
            Log.e(TAG, "fullScan drive=$driveId failed", t)
            driveDao.setScanState(driveId, ScanState.ERROR)
        }
    }

    /**
     * 增量同步（SPEC §4.2 第4步）：从当前 last_message 向下扫到 last_scanned_message_id
     * 为止（仅 strictly newer 消息入库）。无新消息幂等返回。
     */
    suspend fun incrementalSync(driveId: Long) {
        val drive = driveDao.getById(driveId) ?: return
        driveDao.setScanState(driveId, ScanState.SCANNING)
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
                if (messages.isEmpty()) break

                val stopHere = messages.any {
                    (it["id"]?.jsonPrimitive?.longOrNull ?: 0L) <= stopId
                }
                val fresh = messages.filter {
                    (it["id"]?.jsonPrimitive?.longOrNull ?: 0L) > stopId
                }
                if (fresh.isNotEmpty()) ingestPage(chatId, driveId, fresh)

                if (stopHere) break
                val lastId = messages.last()["id"]?.jsonPrimitive?.longOrNull ?: break
                fromMessageId = lastId
                delay(PAGE_DELAY_MS)
            }
            driveDao.setLastScannedMessageId(driveId, newestId)
            driveDao.setScanState(driveId, ScanState.DONE)
        } catch (t: Throwable) {
            Log.e(TAG, "incrementalSync drive=$driveId failed", t)
            driveDao.setScanState(driveId, ScanState.ERROR)
        }
    }

    /** 单页入库：解析 → IGNORE 幂等插入（回读 rowId）→ 异步补抓缩略图。 */
    private suspend fun ingestPage(chatId: Long, driveId: Long, messages: List<JsonObject>) {
        val parsed = messages.mapNotNull { parseMedia(chatId, driveId, it) }
        if (parsed.isEmpty()) return
        val entities = parsed.map { it.first }
        val rowIds = withContext(Dispatchers.IO) { mediaDao.insertAllIgnoring(entities) }
        parsed.zip(rowIds) { (entity, thumb), rowId ->
            // rowId == -1L：已存在的行（重扫），保留其本地覆盖（display_name/thumb_path），跳过缩略图
            if (rowId != -1L) launchThumbJob(entity.copy(id = rowId), thumb)
        }
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
        val messageId = msg["id"]?.jsonPrimitive?.longOrNull ?: return null
        val messageDate = msg["date"]?.jsonPrimitive?.longOrNull ?: return null
        val content = msg["content"] as? JsonObject ?: return null
        val contentType = (content["@type"] as? JsonPrimitive)?.content ?: ""

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
                val video = content["video"] as? JsonObject ?: return null
                fileObj = video["file"] as? JsonObject ?: return null
                kind = MediaKind.VIDEO
                mime = (video["mime_type"] as? JsonPrimitive)?.content
                displayName = (video["file_name"] as? JsonPrimitive)?.content ?: ""
                durationMs = video["duration"]?.jsonPrimitive?.longOrNull?.times(1000)
                width = video["width"]?.jsonPrimitive?.longOrNull?.toInt()
                height = video["height"]?.jsonPrimitive?.longOrNull?.toInt()
                thumbFile = video["thumbnail"]?.jsonObject?.get("file") as? JsonObject
            }
            "messagePhoto" -> {
                val photo = content["photo"] as? JsonObject ?: return null
                // photoSize.file 本身无宽高字段，宽高取自最大的 photoSize 块
                fileObj = photoMainFile(photo) ?: return null
                kind = MediaKind.IMAGE
                photoMainSize(photo)?.let { biggest ->
                    width = biggest["width"]?.jsonPrimitive?.longOrNull?.toInt()
                    height = biggest["height"]?.jsonPrimitive?.longOrNull?.toInt()
                }
                thumbFile = photoThumbFile(photo)
            }
            "messageDocument" -> {
                val doc = content["document"] as? JsonObject ?: return null
                mime = (doc["mime_type"] as? JsonPrimitive)?.content
                displayName = (doc["file_name"] as? JsonPrimitive)?.content ?: ""
                val isVideo = mime?.startsWith("video/") == true ||
                    displayName.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXTENSIONS
                if (!isVideo) return null
                fileObj = doc["document"] as? JsonObject ?: return null
                kind = MediaKind.VIDEO
                thumbFile = doc["thumbnail"]?.jsonObject?.get("file") as? JsonObject
            }
            else -> return null
        }

        val remote = fileObj["remote"] as? JsonObject
        val remoteFileId = (remote?.get("id") as? JsonPrimitive)?.content ?: return null
        val fileUniqueId = (remote?.get("unique_id") as? JsonPrimitive)?.content ?: return null
        val fileId = fileObj["id"]?.jsonPrimitive?.longOrNull?.toInt() ?: return null
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
            thumbPath = null,
            stale = false,
        )
        return entity to thumbFile
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

        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v")

        private fun longJsonArray(vararg values: Long) =
            JsonArray(values.map { JsonPrimitive(it) })

        /** 从任意消息 JsonObject 抽取 video/photo/document 的主 file 对象（freshFileId 用）。 */
        private fun extractFileObject(msg: JsonObject): JsonObject? {
            val content = msg["content"] as? JsonObject ?: return null
            return when ((content["@type"] as? JsonPrimitive)?.content) {
                "messageVideo" -> content["video"]?.jsonObject?.get("file") as? JsonObject
                "messagePhoto" -> content["photo"]?.jsonObject?.let { photoMainFile(it) }
                "messageDocument" -> content["document"]?.jsonObject?.get("document") as? JsonObject
                else -> null
            }
        }

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

