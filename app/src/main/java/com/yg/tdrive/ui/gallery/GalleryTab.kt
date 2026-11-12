package com.yg.tgdrive.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDate
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.common.formatSize
import com.yg.tgdrive.ui.wall.MediaDetailSheet
import com.yg.tgdrive.ui.search.FilterButton
import com.yg.tgdrive.ui.search.FilterSheet
import com.yg.tgdrive.ui.search.FilterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 图片 tab：2 列瀑布流。
 *
 * 与海报墙同一套视觉语言：深色底、圆角、无描边 chip、图内只留一行小字。
 */
@Composable
fun GalleryTab() {
    val drives by ServiceLocator.driveManager.observeDrives().collectAsState(initial = emptyList())
    var selectedDrive by remember { mutableStateOf<Long?>(null) }
    var detailFor by remember { mutableStateOf<MediaEntity?>(null) }
    /** 正在全屏查看的图片（null = 未打开看图页）。 */
    var viewerFor by remember { mutableStateOf<MediaEntity?>(null) }
    /** 相册ID → 组内视频时长（null 表示有视频但时长未知）；只含「组内确实有视频」的相册。 */
    var albumVideo by remember { mutableStateOf<Map<Long, Long?>>(emptyMap()) }
    var filter by remember { mutableStateOf(FilterState()) }
    var showFilter by remember { mutableStateOf(false) }

    if (showFilter) {
        FilterSheet(
            initial = filter,
            onDismiss = { showFilter = false },
            onApply = { filter = it; showFilter = false },
        )
    }

    LaunchedEffect(drives) {
        if (selectedDrive == null && drives.isNotEmpty()) selectedDrive = drives.first().id
    }

    val imageFlow: Flow<PagingData<MediaEntity>> = remember(selectedDrive, filter) {
        selectedDrive?.let { d ->
            if (filter.isActive()) {
                ServiceLocator.mediaRepository.observeFilteredWall(
                    driveId = d,
                    kind = "image",
                    minSize = filter.minSizeBytes,
                    maxSize = filter.maxSizeBytes,
                    minDurationMs = filter.minDurationMs,
                    maxDurationMs = filter.maxDurationMs,
                    dateFrom = filter.dateFromSec,
                    dateTo = filter.dateToSec,
                    sortBy = filter.sortBy,
                    desc = filter.desc,
                )
            } else {
                ServiceLocator.mediaRepository.observeWall(d, "image")
            }
        } ?: flowOf(PagingData.empty())
    }
    val items: LazyPagingItems<MediaEntity> = imageFlow.collectAsLazyPagingItems()

    // 换驱动器时清空角标缓存
    LaunchedEffect(selectedDrive) { albumVideo = emptyMap() }

    // 已加载图片的相册 id 去重后一次 IN 查询，拿到「哪些相册含视频 + 时长」。
    // 只做批量查询，绝不逐格 N+1。
    LaunchedEffect(items.itemCount, selectedDrive) {
        val ids = LinkedHashSet<Long>()
        for (i in 0 until items.itemCount) {
            val a = items.peek(i)?.mediaAlbumId ?: continue
            if (a > 0L) ids.add(a)
        }
        if (ids.isNotEmpty()) {
            val info = runCatching {
                ServiceLocator.mediaRepository.albumVideoInfo(ids)
            }.getOrDefault(emptyMap())
            if (info.isNotEmpty()) albumVideo = albumVideo + info
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterButton(active = filter.isActive(), onClick = { showFilter = true })
        }
        if (drives.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(drives, key = { it.id }) { drive ->
                    val sel = selectedDrive == drive.id
                    val accent = MaterialTheme.colorScheme.primary
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (sel) accent.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { selectedDrive = drive.id }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            drive.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when {
            // 同 WallGrid：扫描期间 Room 写库会反复 invalidate PagingSource，
            // 只有真正空库时才显示骨架屏，避免列表被反复顶掉造成屏闪。
            items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> {
                ImageSkeletonGrid()
            }
            items.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                AppIcons.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "还没有图片",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "把图片转发进驱动器频道即可自动入库",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            else -> {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalItemSpacing = 9.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) { index ->
                        val m = items[index] ?: return@items
                        val album = m.mediaAlbumId
                        val hasVideo = album != null && albumVideo.containsKey(album)
                        ImageCell(
                            m = m,
                            hasVideo = hasVideo,
                            videoDurationMs = if (hasVideo) albumVideo[album] else null,
                            onOpen = { viewerFor = m },
                            onLongPress = { detailFor = m },
                        )
                    }
                }
            }
        }
    }

    viewerFor?.let { m ->
        GalleryViewer(initial = m, onDismiss = { viewerFor = null })
    }
    }

    detailFor?.let {
        MediaDetailSheet(media = it, onDismiss = { detailFor = null })
    }
}

/** 骨架屏。 */
@Composable
private fun ImageSkeletonGrid() {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalItemSpacing = 9.dp,
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(8) { i ->
            val ratio = if (i % 3 == 0) 0.75f else if (i % 3 == 1) 1.33f else 1f
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }
    }
}

/** 瀑布流单元：原始宽高比缩略图 + 图内底部一行小字。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageCell(
    m: MediaEntity,
    hasVideo: Boolean,
    videoDurationMs: Long?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val w = m.width?.takeIf { it > 0 } ?: 3
    val h = m.height?.takeIf { it > 0 } ?: 4
    val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.55f, 2.0f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = m.thumbPath?.let { java.io.File(it) },
            contentDescription = m.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 底部轻渐变托字
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(40.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    )
                )
        )
        Text(
            formatSize(m.size),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )

        // 组内含视频：右下角小视频角标（纯展示，不可点）
        if (hasVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 7.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Play,
                    contentDescription = "组内含视频",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
                val dur = formatDuration(videoDurationMs)
                if (dur.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        dur,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
