package com.yg.tgdrive.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 【临时诊断 · 取证用，非功能代码】扫描丢因统计。
 *
 * 目的：定位 `media` 表 video 比 TDLib `messageVideo` 少 890 条的原因。
 * 只做计数/取样，**不改变任何解析结果**；定位完成后整文件 + MediaRepository 的打点可一并删除。
 *
 * 设计约束：
 * - `drop()` 只做一次 ConcurrentHashMap.merge，不开协程、不写 Android 日志（logcat 会被冲掉）；
 * - 全部状态在内存，扫描结束（fullScan / incrementalSync 的 finally）落一份 JSON 到
 *   `filesDir/scan-diag.json`，路径用 `Log.i("ScanDiag", ...)` 打一行；
 * - 假定同一时刻只有一轮扫描（设置页按钮与重扫桥接共用本单例；并发时会混计）。
 *
 * 关键区分（报告里必须同时看这几组数，否则会被重复页误导）：
 * - totalMessages      = 本轮处理的**消息条数（含重复页重取）**
 * - uniqueMessages     = 本轮处理过的**去重消息数**
 * - contentTypes       = 按去重消息统计的 @type 分布（可直接和 DB 行数对比）
 * - contentTypesAll    = 按处理次数统计的 @type 分布（含重复页）
 * - drops              = parseMedia 各失败出口的命中次数（按处理次数）
 * - parsed/parsedUnique= 解析成功（按次数 / 按去重消息），并分 kind
 * - dbCountByKind      = 扫描结束时 DB 里该驱动器 media.kind 的实际行数
 * - pages/repeatedPages/maxPageRepeats = 翻页与同页重取情况（同页重试上限 1 次 → ≤2）
 * - covers             = 扫描结束时的封面统计（相册优先两阶段补齐后的真值）
 *
 * 注：MediaRepository 现在按 message_id 去重后再解析，同一页**不会**被重复解析；
 * 因此 parsedByKind 应等于 parsedUniqueByKind，contentTypesAll 应等于 contentTypes。
 */
object ScanDiagnostics {

    private const val TAG = "ScanDiag"
    private const val SAMPLE_LIMIT = 2
    private const val SAMPLE_MAX_CHARS = 1500
    const val FILE_NAME = "scan-diag.json"

    // ── 原因代号（稳定字符串，报告 drops 的 key） ──

    const val NO_ID = "NO_ID"
    const val NO_DATE = "NO_DATE"
    const val NO_CONTENT = "NO_CONTENT"

    const val VIDEO_NO_VIDEO_OBJ = "VIDEO_NO_VIDEO_OBJ"
    const val VIDEO_NO_FILE = "VIDEO_NO_FILE"

    const val PHOTO_NO_PHOTO = "PHOTO_NO_PHOTO"
    const val PHOTO_NO_FILE = "PHOTO_NO_FILE"

    const val DOC_NO_DOC = "DOC_NO_DOC"
    const val DOC_NO_FILE = "DOC_NO_FILE"
    const val DOC_NOT_VIDEO = "DOC_NOT_VIDEO"

    const val ANIM_NO_ANIM = "ANIM_NO_ANIM"
    const val ANIM_NO_FILE = "ANIM_NO_FILE"

    const val NO_REMOTE = "NO_REMOTE"
    const val NO_REMOTE_ID = "NO_REMOTE_ID"
    const val NO_UNIQUE_ID = "NO_UNIQUE_ID"
    const val NO_FILE_ID = "NO_FILE_ID"

    /** `when(contentType)` 落到 else：`UNKNOWN_TYPE:<@type>`。 */
    fun unknownType(type: String): String = "UNKNOWN_TYPE:" + type.ifEmpty { "<empty>" }

    // ── 计数 ──

    private val drops = ConcurrentHashMap<String, Long>()
    private val contentTypes = ConcurrentHashMap<String, Long>()
    private val contentTypesAll = ConcurrentHashMap<String, Long>()
    private val parsedByKind = ConcurrentHashMap<String, Long>()
    private val parsedUniqueByKind = ConcurrentHashMap<String, Long>()
    private val dbCounts = ConcurrentHashMap<String, Long>()
    private val covers = ConcurrentHashMap<String, Long>()
    private val pageRequests = ConcurrentHashMap<Long, Long>()
    private val seenMessageIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val uniqueMessages = AtomicLong()
    private val parsedAttempts = AtomicLong()
    private val parsedUnique = AtomicLong()
    private val pages = AtomicLong()
    private val repeatedPages = AtomicLong()
    private val maxPageRepeats = AtomicLong()
    private val minMessageId = AtomicLong(Long.MAX_VALUE)
    private val maxMessageId = AtomicLong(Long.MIN_VALUE)

    @Volatile private var scanName = ""
    @Volatile private var scanDriveId = -1L
    @Volatile private var errorText: String? = null

    /** 每个失败原因最多 2 条 content.toString()（截断 1500 字符）。 */
    val samples: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    /** 对象键名并集：path（如 "messageVideo.video"）→ 去重排序后的键名。 */
    private val keySets: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()

    /** 清空全部内存计数（扫描入口与调试按钮都会调）。 */
    fun reset() {
        drops.clear()
        contentTypes.clear()
        contentTypesAll.clear()
        parsedByKind.clear()
        parsedUniqueByKind.clear()
        dbCounts.clear()
        covers.clear()
        pageRequests.clear()
        seenMessageIds.clear()
        samples.clear()
        keySets.clear()
        uniqueMessages.set(0)
        parsedAttempts.set(0)
        parsedUnique.set(0)
        pages.set(0)
        repeatedPages.set(0)
        maxPageRepeats.set(0)
        minMessageId.set(Long.MAX_VALUE)
        maxMessageId.set(Long.MIN_VALUE)
        scanName = ""
        scanDriveId = -1L
        errorText = null
    }

    /** 一轮扫描开始：先 reset 再记住轮次名（写进报告，区分 fullScan / incrementalSync）。 */
    fun begin(scan: String, driveId: Long) {
        reset()
        scanName = scan
        scanDriveId = driveId
    }

    /** parseMedia 失败出口。零成本：只 merge。 */
    fun drop(reason: String) {
        drops.merge(reason, 1L, Long::plus)
    }

    /** 每个被处理的消息记一次 @type（含重复页重取）。 */
    fun record(contentType: String) {
        contentTypesAll.merge(contentType, 1L, Long::plus)
    }

    /** 去重消息的 @type（可直接与 DB 行数对比）。 */
    fun recordUnique(contentType: String) {
        contentTypes.merge(contentType, 1L, Long::plus)
    }

    /**
     * 登记消息 id；返回 true 表示本轮首次见到（用于去重统计）。
     * 顺带维护 message_id 的 min/max，证明扫描覆盖范围。
     */
    fun message(messageId: Long): Boolean {
        val first = seenMessageIds.add(messageId)
        if (first) uniqueMessages.incrementAndGet()
        minMessageId.updateAndGet { if (messageId < it) messageId else it }
        maxMessageId.updateAndGet { if (messageId > it) messageId else it }
        return first
    }

    /** 解析成功（按次数 + 按去重消息分 kind 累计）。 */
    fun parsed(kind: String, firstSeen: Boolean) {
        parsedAttempts.incrementAndGet()
        parsedByKind.merge(kind, 1L, Long::plus)
        if (firstSeen) {
            parsedUnique.incrementAndGet()
            parsedUniqueByKind.merge(kind, 1L, Long::plus)
        }
    }

    /** 每次 getChatHistory 调用：页数 + 同一 from_message_id 的重取次数。 */
    fun page(fromMessageId: Long) {
        pages.incrementAndGet()
        val n = pageRequests.merge(fromMessageId, 1L, Long::plus) ?: 1L
        if (n > 1) repeatedPages.incrementAndGet()
        maxPageRepeats.updateAndGet { if (n > it) n else it }
    }

    /** 扫描结束时 DB 内 media.kind 的实际行数。 */
    fun dbKindCounts(counts: Map<String, Int>) {
        dbCounts.clear()
        counts.forEach { (k, v) -> dbCounts[k] = v.toLong() }
    }

    /** 扫描结束时封面统计（videoMissingThumb/imageMissingThumb/videoAlbumCover）。 */
    fun covers(counts: Map<String, Int>) {
        covers.clear()
        counts.forEach { (k, v) -> covers[k] = v.toLong() }
    }

    /** 一行摘要（设置页诊断按钮回显；区分 DONE / ERROR 用 errorText）。 */
    fun summary(): String {
        val parts = covers.toSortedMap().map { (k, v) -> "$k=$v" }
        val err = errorText?.let { "error=$it" } ?: "ok"
        return (listOf(err) + parts).joinToString(" ")
    }

    fun error(message: String?) {
        errorText = message
    }

    /** 原始 JSON 样本（最多 2 条/原因）：截断到 1500 字符，避免报告膨胀。 */
    fun sample(reason: String, content: JsonObject) {
        val list = samples.getOrPut(reason) { mutableListOf() }
        synchronized(list) {
            if (list.size < SAMPLE_LIMIT) {
                var s = content.toString()
                if (s.length > SAMPLE_MAX_CHARS) {
                    s = s.substring(0, SAMPLE_MAX_CHARS) + "…<truncated>"
                }
                list.add(s)
            }
        }
    }

    /** 记录某个对象的键名并集（path 形如 "messageVideo.video"），值一律不记。 */
    fun keys(path: String, keys: Set<String>) {
        keySets.getOrPut(path) { ConcurrentHashMap.newKeySet() }.addAll(keys)
    }

    /** drops 快照（reason → 次数）。 */
    fun snapshot(): Map<String, Long> = drops.toMap()

    /**
     * 写 `<filesDir>/scan-diag.json`。context 为 null 时跳过写文件（内存计数照常）。
     * 用 NonCancellable + IO：扫描被取消时 finally 仍能落盘。
     */
    suspend fun report(
        context: Context?,
        scan: String,
        driveId: Long,
        totalMessages: Int,
        ingested: Int,
    ): File? = withContext(NonCancellable + Dispatchers.IO) {
        if (context == null) {
            Log.w(TAG, "appContext == null，跳过 $FILE_NAME（内存计数不变）")
            return@withContext null
        }
        val file = File(context.filesDir, FILE_NAME)
        try {
            val root = buildJsonObject {
                put("generatedAt", System.currentTimeMillis() / 1000)
                put("scan", scanName.ifEmpty { scan })
                put("driveId", driveId)
                put("totalMessages", totalMessages)
                put("uniqueMessages", uniqueMessages.get())
                put("ingested", ingested)
                put("parsed", parsedAttempts.get())
                put("parsedUnique", parsedUnique.get())
                put("parsedByKind", longObj(parsedByKind))
                put("parsedUniqueByKind", longObj(parsedUniqueByKind))
                put("dbCountByKind", longObj(dbCounts))
                put("covers", longObj(covers))
                put("contentTypes", longObj(contentTypes))
                put("contentTypesAll", longObj(contentTypesAll))
                put("drops", longObj(drops))
                put(
                    "samples",
                    buildJsonObject {
                        samples.toSortedMap().forEach { (reason, list) ->
                            val copy = synchronized(list) { list.toList() }
                            put(
                                reason,
                                buildJsonArray { copy.forEach { add(JsonPrimitive(it)) } },
                            )
                        }
                    },
                )
                put(
                    "contentKeys",
                    buildJsonObject {
                        keySets.toSortedMap().forEach { (path, keys) ->
                            put(
                                path,
                                buildJsonArray { keys.sorted().forEach { add(JsonPrimitive(it)) } },
                            )
                        }
                    },
                )
                put("pages", pages.get())
                put("repeatedPages", repeatedPages.get())
                put("maxPageRepeats", maxPageRepeats.get())
                put("firstMessageId", minMessageId.get().takeIf { it != Long.MAX_VALUE })
                put("lastMessageId", maxMessageId.get().takeIf { it != Long.MIN_VALUE })
                put("error", errorText)
                put(
                    "note",
                    "全部按本轮去重消息统计；同页重试不再重复解析（maxPageRepeats ≤ 2）。" +
                        "parsedByKind 应等于 parsedUniqueByKind，contentTypesAll 应等于 contentTypes。",
                )
            }
            file.writeText(json.encodeToString(JsonObject.serializer(), root))
            Log.i(TAG, "report -> " + file.absolutePath)
            file
        } catch (t: Throwable) {
            // 诊断绝不能把扫描本身搞挂
            Log.e(TAG, "写 $FILE_NAME 失败", t)
            null
        }
    }

    private val json = kotlinx.serialization.json.Json { prettyPrint = true }

    private fun longObj(m: Map<String, Long>): JsonObject = buildJsonObject {
        m.toSortedMap().forEach { (k, v) -> put(k, v) }
    }
}
