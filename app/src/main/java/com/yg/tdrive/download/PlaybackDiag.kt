package com.yg.tgdrive.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 【诊断 · 取证用】播放准备阶段计时 + 取消归因。
 *
 * 目标（用户反馈「长视频加载不出来 / 短视频下完才播」+「几秒后有速度、随后归零」）：
 * 1. 量化 prepare 总耗时，以及每一次远距离读（文件头 / 尾部 8MB / moov）的真实耗时；
 * 2. 把 TDLib `td error 200: Canceled by another downloadFile request` 按**调用点**计数
 *    （`window` / `stream` / `prepare` / `probe`），定位是哪个请求互踩；
 * 3. 记录每次 `downloadFile` 的实际并发度（单请求不变式：同一 file 恒 ≤ 1）。
 *
 * 设计约束（仿 ScanDiagnostics）：
 * - 打点只做一次加锁/merge，不开协程、不阻塞播放；
 * - 事件列表有上限（[MAX_ROWS]），避免长会话把 JSON 撑爆；
 * - 写文件失败绝不影响播放（全部 runCatching）；
 * - `Log.i("PlaybackDiag", ...)` 打一行摘要，logcat 被冲掉也能从文件取证。
 *
 * 读取方式：`adb shell run-as com.yg.tgdrive.debug cat files/play-diag.json`。
 */
object PlaybackDiag {

    private const val TAG = "PlaybackDiag"
    const val FILE_NAME = "play-diag.json"

    /** 每类明细行上限（超出的只计数不落行）。 */
    private const val MAX_ROWS = 200

    /** 关键事件行上限。 */
    private const val MAX_EVENTS = 120

    // ── 本轮会话标识 ──
    @Volatile private var mediaId = 0L
    @Volatile private var fileId = 0
    @Volatile private var size = 0L
    @Volatile private var startedAt = 0L

    // ── prepare 结果 ──
    @Volatile private var prepareMs = -1L
    @Volatile private var prepareTimedOut = false
    @Volatile private var planKind = "none"
    @Volatile private var planReason = ""
    @Volatile private var planMoovStart = -1L
    @Volatile private var planMoovLen = -1L
    @Volatile private var planPrefixLen = -1L
    @Volatile private var planVirtualSize = -1L
    @Volatile private var source = "none"
    @Volatile private var streamConcurrency = -1

    // ── 明细行 ──
    private val ensureRows = mutableListOf<JsonObject>()
    private val fetchRows = mutableListOf<JsonObject>()
    private val probeRows = mutableListOf<JsonObject>()
    private val events = mutableListOf<JsonObject>()
    private val boxChain = mutableListOf<String>()
    private val dropped = ConcurrentHashMap<String, Long>()

    // ── 取消归因（td error 200 / Canceled by another downloadFile request） ──
    private val cancelCount = AtomicLong()

    /** 跳跃 seek 重定位次数与明细（from/to）。 */
    private val relocations = AtomicLong()
    private val seeks = mutableListOf<Map<String, Long>>()
    private val cancelAtStep = ConcurrentHashMap<String, Long>()

    // ── 单请求不变式观测（按 file 计，不是全局：不同文件各自一条请求是合法的） ──
    private val livePerFile = ConcurrentHashMap<Int, AtomicInteger>()
    private val maxLivePerFile = ConcurrentHashMap<Int, Int>()

    private val json = kotlinx.serialization.json.Json { prettyPrint = true }

    private fun now() = SystemClock.elapsedRealtime()

    /** 新一轮准备开始（同一 Activity 内切集时重置）。 */
    fun begin(mediaId: Long, fileId: Int, size: Long) {
        synchronized(this) {
            this.mediaId = mediaId
            this.fileId = fileId
            this.size = size
            startedAt = now()
            prepareMs = -1L
            prepareTimedOut = false
            planKind = "none"
            planReason = ""
            planMoovStart = -1L
            planMoovLen = -1L
            planPrefixLen = -1L
            planVirtualSize = -1L
            source = "none"
            ensureRows.clear()
            fetchRows.clear()
            probeRows.clear()
            events.clear()
            boxChain.clear()
            dropped.clear()
            cancelCount.set(0)
            cancelAtStep.clear()
            livePerFile.clear()
            maxLivePerFile.clear()
        }
        Log.i(TAG, "begin media=$mediaId file=$fileId size=$size")
    }

    /** 关键节点（prepare 计时锚点）。 */
    fun event(step: String, msg: String) {
        Log.i(TAG, "$step: $msg")
        synchronized(this) {
            if (events.size < MAX_EVENTS) {
                events.add(buildJsonObject {
                    put("t", now() - startedAt)
                    put("step", step)
                    put("msg", msg)
                })
            } else {
                bump("events")
            }
        }
    }

    fun prepareDone(ms: Long, timedOut: Boolean) {
        prepareMs = ms
        prepareTimedOut = timedOut
        Log.i(TAG, "prepareDone ${ms}ms timeout=$timedOut")
    }

    /** 本轮 prepare 是否因硬上限超时而降级。 */
    fun wasPrepareTimedOut(): Boolean = prepareTimedOut

    /** plan 结论：kind = remux / direct / needsFullDownload / error / timeout。 */
    fun plan(kind: String, reason: String) {
        planKind = kind
        planReason = reason
        Log.i(TAG, "plan=$kind reason=$reason")
    }

    fun planDetail(moovStart: Long, moovLen: Long, prefixLen: Long, virtualSize: Long) {
        planMoovStart = moovStart
        planMoovLen = moovLen
        planPrefixLen = prefixLen
        planVirtualSize = virtualSize
    }

    /** 最终数据源：td_virtual / td_data / local / blocked。 */
    fun source(name: String) {
        source = name
        Log.i(TAG, "source=$name")
    }

    fun concurrency(n: Int) {
        streamConcurrency = n
    }

    /** box 链推演（2.1 的证据：走了几个 header、各自偏移/长度）。 */
    fun boxChain(chain: List<String>) {
        synchronized(this) {
            boxChain.clear()
            boxChain.addAll(chain.take(32))
        }
        Log.i(TAG, "boxChain: ${chain.joinToString(" -> ")}")
    }

    /** 一次 ensureRange 的收尾。mode = prepare / stream。 */
    fun ensure(
        offset: Long,
        len: Long,
        expectedSize: Long,
        nearTail: Boolean,
        mode: String,
        ms: Long,
        reason: String,
    ) {
        synchronized(this) {
            if (ensureRows.size < MAX_ROWS) {
                ensureRows.add(buildJsonObject {
                    put("offset", offset)
                    put("len", len)
                    put("expectedSize", expectedSize)
                    put("nearTail", nearTail)
                    put("mode", mode)
                    put("ms", ms)
                    put("reason", reason)
                })
            } else {
                bump("ensureRanges")
            }
        }
        if (ms >= 1_000L || reason != "ok") {
            Log.i(TAG, "ensure off=$offset len=$len mode=$mode ${ms}ms -> $reason")
        }
    }

    /**
     * 一次 fetchRangeSync 的收尾（含重试明细）。
     * outcome = ok / timeout / canceled / error；每次尝试的耗时在 attemptMs。
     */
    fun fetch(
        tag: String,
        offset: Long,
        length: Long,
        attemptMs: List<Long>,
        outcome: String,
        detail: String?,
    ) {
        synchronized(this) {
            if (fetchRows.size < MAX_ROWS) {
                fetchRows.add(buildJsonObject {
                    put("tag", tag)
                    put("offset", offset)
                    put("length", length)
                    put("attempts", attemptMs.size)
                    put("attemptMs", buildJsonArray { attemptMs.forEach { add(JsonPrimitive(it)) } })
                    put("totalMs", attemptMs.sum())
                    put("outcome", outcome)
                    put("detail", detail)
                })
            } else {
                bump("fetches")
            }
        }
        Log.i(
            TAG,
            "fetch[$tag] off=$offset len=$length attempts=${attemptMs.size} " +
                "ms=${attemptMs.joinToString("/")} -> $outcome${detail?.let { " ($it)" } ?: ""}"
        )
    }

    /** probeNonZero 收尾：polls = 轮询次数，nonzero = 实际读到的非零字节数。 */
    fun probe(offset: Long, polls: Int, nonzero: Int, ms: Long, outcome: String) {
        synchronized(this) {
            if (probeRows.size < MAX_ROWS) {
                probeRows.add(buildJsonObject {
                    put("offset", offset)
                    put("polls", polls)
                    put("nonzeroBytes", nonzero)
                    put("ms", ms)
                    put("outcome", outcome)
                })
            } else {
                bump("probes")
            }
        }
        if (outcome != "ok") Log.i(TAG, "probe off=$offset polls=$polls -> $outcome (${ms}ms)")
    }

    /** 跳跃 seek 重定位（取消旧请求 → 从新偏移重新发起）。 */
    fun relocate(from: Long, to: Long) {
        relocations.incrementAndGet()
        synchronized(seeks) {
            if (seeks.size < 50) seeks.add(mapOf("from" to from, "to" to to))
        }
        Log.i(TAG, "RELOCATE $from -> $to (total=${relocations.get()})")
    }

    /** TDLib 取消（error 200）。tag = 发起该请求的调用点。 */
    fun cancel(tag: String) {
        cancelCount.incrementAndGet()
        cancelAtStep.merge(tag, 1L, Long::plus)
        Log.i(TAG, "CANCEL at $tag (total=${cancelCount.get()})")
    }

    /**
     * downloadFile 进出账：返回 true 表示**违反**单请求不变式（同一 file 并发 > 1）。
     * 调用方在 [StreamingDownloadManager] 里记日志，形成运行期断言。
     */
    fun requestEnter(fileId: Int): Boolean {
        val n = livePerFile.getOrPut(fileId) { AtomicInteger() }.incrementAndGet()
        maxLivePerFile.merge(fileId, n) { a, b -> maxOf(a, b) }
        return n > 1
    }

    fun requestExit(fileId: Int) {
        livePerFile[fileId]?.decrementAndGet()
    }

    /** 单文件 downloadFile 并发峰值（诊断断言：必须恒为 1）。 */
    fun maxConcurrent(fileId: Int): Int = maxLivePerFile[fileId] ?: 0

    private fun bump(what: String) {
        dropped.merge(what, 1L, Long::plus)
    }

    /** 写 `<filesDir>/play-diag.json`；context 为 null 只打日志。 */
    suspend fun finish(context: Context?): File? = withContext(NonCancellable + Dispatchers.IO) {
        if (context == null) {
            Log.w(TAG, "appContext == null，跳过 $FILE_NAME")
            return@withContext null
        }
        val file = File(context.filesDir, FILE_NAME)
        try {
            val root = synchronized(this@PlaybackDiag) {
                buildJsonObject {
                    put("generatedAt", System.currentTimeMillis() / 1000)
                    put("mediaId", mediaId)
                    put("fileId", fileId)
                    put("size", size)
                    put("prepareMs", prepareMs)
                    put("prepareTimedOut", prepareTimedOut)
                    put(
                        "plan",
                        buildJsonObject {
                            put("kind", planKind)
                            put("reason", planReason)
                            put("moovStart", planMoovStart)
                            put("moovLen", planMoovLen)
                            put("prefixLen", planPrefixLen)
                            put("virtualSize", planVirtualSize)
                        },
                    )
                    put("source", source)
                    put("concurrency", streamConcurrency)
                    put("boxChain", buildJsonArray { boxChain.forEach { add(JsonPrimitive(it)) } })
                    put("cancelCount", cancelCount.get())
                    put("relocations", relocations.get())
                    put("seeks", JsonArray(synchronized(seeks) {
                        seeks.map { m -> JsonObject(m.mapValues { JsonPrimitive(it.value) }) }
                    }))
                    put(
                        "cancelAtStep",
                        buildJsonObject {
                            cancelAtStep.toSortedMap().forEach { (k, v) -> put(k, v) }
                        },
                    )
                    put("maxConcurrentDownloadFile", maxLivePerFile.values.maxOrNull() ?: 0)
                    put(
                        "maxConcurrentPerFile",
                        buildJsonObject {
                            maxLivePerFile.toSortedMap().forEach { (k, v) -> put(k.toString(), v) }
                        },
                    )
                    put("ensureRanges", buildJsonArray { ensureRows.forEach { add(it) } })
                    put("fetches", buildJsonArray { fetchRows.forEach { add(it) } })
                    put("probes", buildJsonArray { probeRows.forEach { add(it) } })
                    put("events", buildJsonArray { events.forEach { add(it) } })
                    put(
                        "dropped",
                        buildJsonObject { dropped.toSortedMap().forEach { (k, v) -> put(k, v) } },
                    )
                    put(
                        "note",
                        "cancelCount>0 表示 TDLib 报了 td error 200；cancelAtStep 是发起被取消请求的调用点。" +
                            "maxConcurrentDownloadFile 恒为 1 = 单请求不变式成立。",
                    )
                }
            }
            file.writeText(json.encodeToString(JsonObject.serializer(), root))
            Log.i(TAG, "report -> ${file.absolutePath}")
            file
        } catch (t: Throwable) {
            // 诊断绝不能把播放本身搞挂
            Log.e(TAG, "写 $FILE_NAME 失败", t)
            null
        }
    }
}
