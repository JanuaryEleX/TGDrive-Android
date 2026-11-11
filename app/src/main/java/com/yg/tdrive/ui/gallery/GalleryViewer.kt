package com.yg.tgdrive.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.player.openPlayer
import kotlinx.coroutines.launch

/**
 * 全屏看图页（相册内滑动）。
 *
 * - 左右滑：只在**当前相册内**滑动（同 media_album_id 的图片，按 message_id 升序）
 * - 图源：先显示缩略图，单击图片下载并换成原图（省流量）
 * - 底部：相册里含视频时出现「播放视频」。单视频直接播；多视频弹列表选。
 * - 播放走 openPlayer 正常流程 → 自动写 playback_progress，自然进入最近tab「继续观看」
 */
@Composable
fun GalleryViewer(
    initial: MediaEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var images by remember { mutableStateOf(listOf(initial)) }
    var videos by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var startIndex by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var showVideoPicker by remember { mutableStateOf(false) }
    /** 已加载的原图：mediaId → 本地文件 */
    var originals by remember { mutableStateOf<Map<Long, java.io.File>>(emptyMap()) }
    var loadingOriginal by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState { images.size.coerceAtLeast(1) }

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
                images = imgs
                startIndex = imgs.indexOfFirst { it.id == initial.id }.coerceAtLeast(0)
            }
            videos = vids
        }
        loaded = true
    }
    LaunchedEffect(loaded, startIndex) {
        if (loaded) runCatching { pagerState.scrollToPage(startIndex) }
    }

    BackHandler { onDismiss() }

    val page = pagerState.currentPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val current = images.getOrNull(page) ?: initial

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val m = images.getOrNull(index)
            if (m != null) {
                val local = originals[m.id]
                AsyncImage(
                    model = local ?: m.thumbPath?.let { java.io.File(it) },
                    contentDescription = m.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(m.id) {
                            detectTapGestures {
                                // 单击 → 拉原图（已有则忽略）
                                if (originals[m.id] == null && !loadingOriginal) {
                                    loadingOriginal = true
                                    scope.launch {
                                        val f = runCatching { ensureOriginal(m) }.getOrNull()
                                        if (f != null) originals = originals + (m.id to f)
                                        loadingOriginal = false
                                    }
                                }
                            }
                        },
                )
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
            if (loadingOriginal) {
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

/**
 * 取图片原图本地文件：走下载层 ensureRange 全量区间。
 * LRU 命中或整段可用时 globalOffset 为 0，直接拿整个文件。
 */
private suspend fun ensureOriginal(m: MediaEntity): java.io.File? {
    val ref = ServiceLocator.downloadManager.ensureRange(m.fileId, 0L, m.size)
    if (ref.globalOffset != 0L) return null
    val f = java.io.File(ref.partialPath)
    return if (f.isFile && f.length() > 0L) f else null
}
