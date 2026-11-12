package com.yg.tgdrive.ui.gallery

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.data.GalleryPrefetchPolicy
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.player.openPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏看图页（相册内滑动）。
 *
 * - 左右滑：只在**当前相册内**滑动（同 media_album_id 的图片，按 message_id 升序）
 * - 图源：缩略图先顶上占位，**打开即自动拉原图**，原图就绪后无缝替换（不再需要单击）。
 *   预取窗口 = 停稳页 ± 1 页（见 [GalleryPrefetchPolicy]）：既让当前页尽快变清晰，
 *   又不至于把上千张相册的原图一次性拉下来打爆内存/流量。
 * - 状态按页记录（mediaId → [OriginalState]），某页在拉不影响其它页显示；
 *   失败只保留缩略图 + 一次性轻提示，不阻塞、不重复弹。
 * - 底部：相册里含视频时出现「播放视频」。单视频直接播；多视频弹列表选。
 * - 播放走 openPlayer 正常流程 → 自动写 playback_progress，自然进入最近tab「继续观看」
 */
@Composable
fun GalleryViewer(
    initial: MediaEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var images by remember { mutableStateOf(listOf(initial)) }
    var videos by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var startIndex by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var showVideoPicker by remember { mutableStateOf(false) }

    /** 按页记录的原图状态：mediaId → 状态。缺省（不在表里）= 还没开始，界面只有缩略图。 */
    var originalStates by remember { mutableStateOf<Map<Long, OriginalState>>(emptyMap()) }
    /** 正在拉取的 mediaId：防同页并发重复请求（滑动取消后立刻切回同一页时会撞上）。 */
    val inFlight = remember { mutableSetOf<Long>() }
    /** 已经提示过「原图加载失败」的 mediaId：同一张图不反复弹。 */
    val failureNotified = remember { mutableSetOf<Long>() }
    var lastFailureToastAt by remember { mutableLongStateOf(0L) }

    val pagerState = rememberPagerState { images.size.coerceAtLeast(1) }

    /** 已就绪的原图（仅用于预取策略判断，避免重复下载）。 */
    fun readyIds(): Set<Long> = originalStates
        .filterValues { it is OriginalState.Ready }
        .keys

    /** 原图没拉到时的轻提示：同一张图只弹一次，且 1.5s 内不连弹（预取连败时不刷屏）。 */
    fun notifyFailure(id: Long) {
        if (!GalleryPrefetchPolicy.shouldNotifyFailure(id, failureNotified)) return
        failureNotified.add(id)
        val now = System.currentTimeMillis()
        if (now - lastFailureToastAt < FAILURE_TOAST_GAP_MS) return
        lastFailureToastAt = now
        Toast.makeText(context, "原图加载失败，已显示预览图", Toast.LENGTH_SHORT).show()
    }

    /**
     * 拉一页的原图：预览图不动，状态流转 缺省 → Loading → Ready/Failed。
     *
     * 注意：这里**不能**用 runCatching 包 ensureOriginal —— 它会把 CancellationException
     * 一并吞成 null，滑动取消就被误判成「加载失败」而弹 Toast。
     */
    /** 看图页诊断：只记 id/序号/异常文本，不记文件名与画面。用于定位打开错图与原图失败。 */
    fun diag(line: String) {
        runCatching {
            java.io.File(context.filesDir, "viewer-diag.txt")
                .appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    suspend fun loadOriginal(m: MediaEntity) {
        val id = m.id
        if (id <= 0L) return
        if (originalStates[id] is OriginalState.Ready) return
        if (!inFlight.add(id)) return

        diag("orig start id=$id fileId=${m.fileId} size=${m.size} thumb=${m.thumbPath != null}")
        originalStates = originalStates + (id to OriginalState.Loading)
        var ready = false
        var cancelled = false
        try {
            val file = ensureOriginal(m) { diag(it) }
            if (file != null) {
                originalStates = originalStates + (id to OriginalState.Ready(file))
                ready = true
            }
        } catch (c: CancellationException) {
            // 切页/退出导致的取消不算失败：回落到「未开始」，下次进入该页会重新拉
            cancelled = true
            throw c
        } catch (t: Throwable) {
            // 网络/TDLib 失败：保持缩略图，交给下面的统一提示
            diag("orig FAIL id=$id fileId=${m.fileId} size=${m.size} ${t::class.java.simpleName}: ${t.message}")
        } finally {
            inFlight.remove(id)
            if (!ready) {
                if (cancelled) {
                    if (originalStates[id] is OriginalState.Loading) {
                        originalStates = originalStates - id
                    }
                } else {
                    originalStates = originalStates + (id to OriginalState.Failed)
                    notifyFailure(id)
                }
            }
        }
    }

    /** 看图页诊断：只记 id/序号，不记文件名与画面。用于定位「点开 A 显示 B」。 */
    // 载入相册内容（图片序列 + 视频列表）
    LaunchedEffect(initial.id) {
        val album = initial.mediaAlbumId ?: 0L
        if (album > 0L) {
            val imgs = runCatching {
                ServiceLocator.mediaRepository.imagesInAlbum(album)
            }.getOrDefault(emptyList())
            val vids = runCatching {
                ServiceLocator.mediaRepository.videosInAlbum(album)
            }.getOrDefault(emptyList())
            if (imgs.isNotEmpty()) {
                val hit = imgs.indexOfFirst { it.id == initial.id }
                // ★ 命不中时绝不能回落第 0 张 —— 那正是「点开 A 显示 B」的稳定复现：
                // 相册查询与列表不同源（跨页、刚入库、分组 id 变过）就会 index=-1，
                // 旧写的 coerceAtLeast(0) 于是永远打开相册第一张。
                // 命不中时把被点的那张顶到首位，保证点谁看谁。
                images = if (hit >= 0) imgs else listOf(initial) + imgs.filter { it.id != initial.id }
                startIndex = if (hit >= 0) hit else 0
                diag("album=$album imgs=${imgs.size} hit=$hit start=$startIndex want=${initial.id} first=${images.first().id}")
            } else {
                diag("album=$album imgs=0 fallback-single want=${initial.id}")
            }
            videos = vids
        } else {
            diag("album=null single want=${initial.id}")
        }
        loaded = true
    }
    // 初始页：pageCount 由 images.size 决定。images 落位前 pager 只有 1 页，
    // 这时的 scrollToPage(startIndex) 会被丢掉，界面停在相册第一张 ——
    // 必须等 pageCount 真的容纳 startIndex 再滚，而不是发一次就不管。
    LaunchedEffect(images, startIndex) {
        if (!loaded || startIndex <= 0) return@LaunchedEffect
        repeat(10) {
            if (pagerState.pageCount > startIndex) {
                runCatching { pagerState.scrollToPage(startIndex) }
                diag("scrolled to $startIndex (pageCount=${pagerState.pageCount})")
                return@LaunchedEffect
            }
            delay(50)
        }
        diag("scroll FAILED start=$startIndex pageCount=${pagerState.pageCount}")
    }

    BackHandler { onDismiss() }

    // 停稳页（settledPage）而不是 currentPage：滑动途中不反复起停请求，
    // 松手停稳后才计算预取窗口。key 变化 → 上一轮的协程（含未完成的下载）自动取消，
    // 同一个 LaunchedEffect 内不会出现「同一页两份请求」。
    val settledPage = pagerState.settledPage
    LaunchedEffect(loaded, settledPage, images) {
        if (!loaded || images.isEmpty()) return@LaunchedEffect
        // 等一拍再算窗口：初始 scrollToPage(startIndex) 落位会再改一次 settledPage，
        // 免得先给第 0 页白发一轮请求。
        delay(SETTLE_DEBOUNCE_MS)
        val targets = GalleryPrefetchPolicy.prefetchIds(
            current = settledPage,
            total = images.size,
            idsByPage = images.map { it.id },
            loadedIds = readyIds(),
        )
        if (targets.isEmpty()) return@LaunchedEffect
        val byId = images.associateBy { it.id }
        coroutineScope {
            targets.forEachIndexed { order, id ->
                val m = byId[id] ?: return@forEachIndexed
                launch {
                    // 当前页先起跑；邻居稍后，别和当前页抢同一条下载通道
                    if (order > 0) delay(NEIGHBOR_DELAY_MS)
                    loadOriginal(m)
                }
            }
        }
    }

    val page = pagerState.currentPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val current = images.getOrNull(page) ?: initial

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val m = images.getOrNull(index)
            if (m != null) {
                val origState = originalStates[m.id]
                val original = (origState as? OriginalState.Ready)?.file
                // 缩略图占位是否还在顶层之外显示：原图已经绘制出来就撤掉，
                // 避免原图的上下留白区域透出低分辨率预览（看得出「糊边」）。
                var showThumb by remember(m.id) { mutableStateOf(original == null) }
                Box(Modifier.fillMaxSize()) {
                    val thumb = m.thumbPath?.let { java.io.File(it) }
                    if (showThumb && thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = m.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (original != null) {
                        AsyncImage(
                            model = original,
                            contentDescription = m.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            // 原图解码完成、即将绘制时才撤占位：中间不会出现黑屏/白屏那一帧
                            onSuccess = { showThumb = false },
                            onError = { showThumb = true },
                        )
                    }
                }
            }
        }

        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    current.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (images.size > 1) {
                    Text(
                        "${page + 1} / ${images.size}",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            // 进度圈只看**当前页**的状态：邻居在后台拉不该让顶栏一直转
            if (originalStates[current.id] is OriginalState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
        }

        // 底部：组内视频入口
        if (videos.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(
                            Color(0xFF4EA1FF),
                            RoundedCornerShape(50),
                        ),
                ) {
                    TextButton(onClick = {
                        if (videos.size == 1) {
                            openPlayer(context, videos.first())
                        } else {
                            showVideoPicker = true
                        }
                    }) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (videos.size == 1) "播放视频" else "播放视频（${videos.size}）",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }

    if (showVideoPicker) {
        AlertDialog(
            onDismissRequest = { showVideoPicker = false },
            title = { Text("组内视频") },
            text = {
                Column {
                    videos.forEach { v ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    v.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val d = formatDuration(v.durationMs)
                                if (d.isNotEmpty()) {
                                    Text(
                                        d,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                showVideoPicker = false
                                openPlayer(context, v)
                            }) {
                                Text("播放")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVideoPicker = false }) { Text("取消") }
            },
        )
    }
}

/** 顶部小进度圈出现的等待下限（毫秒）。 */
private const val SETTLE_DEBOUNCE_MS = 120L

/** 邻居页相对当前页的起跑延迟（毫秒），避免抢当前页的下载通道。 */
private const val NEIGHBOR_DELAY_MS = 250L

/** 失败提示的最短间隔（毫秒）。 */
private const val FAILURE_TOAST_GAP_MS = 1_500L

/**
 * 单页原图状态。
 *
 * 之所以按页记而不是一个全局 boolean：旧实现里全局 loading 会卡住所有页
 * （滑到别的页也只能干等），且无法表达「这页已就绪 / 那页失败了」。
 */
private sealed interface OriginalState {
    /** 正在拉取（顶部小进度圈）。 */
    data object Loading : OriginalState

    /** 已就绪，[file] 是本地完整原图。 */
    data class Ready(val file: java.io.File) : OriginalState

    /** 拉取失败（保留了缩略图，可再次进入该页重试）。 */
    data object Failed : OriginalState
}

/**
 * 取图片原图本地文件：走下载层 ensureRange 全量区间。
 * LRU 命中或整段可用时 globalOffset 为 0，直接拿整个文件。
 * 返回 null 表示没拿到全量（区间起点非 0 或文件为空），调用方保持缩略图。
 */
private suspend fun ensureOriginal(m: MediaEntity, log: (String) -> Unit): java.io.File? {
    val ref = ServiceLocator.downloadManager.ensureRange(m.fileId, 0L, m.size)
    val f = java.io.File(ref.partialPath)
    log("orig range id=${m.id} fileId=${m.fileId} size=${m.size} gOff=${ref.globalOffset} disk=${f.length()}")
    if (ref.globalOffset != 0L) return null
    return if (f.isFile && f.length() > 0L) f else null
}
