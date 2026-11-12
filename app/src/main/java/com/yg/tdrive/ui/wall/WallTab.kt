package com.yg.tgdrive.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.td.AuthState
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.common.formatSize
import com.yg.tgdrive.ui.gallery.GalleryTab
import com.yg.tgdrive.ui.nav.authLockMessage
import com.yg.tgdrive.ui.player.openPlayer
import com.yg.tgdrive.ui.search.FilterButton
import com.yg.tgdrive.ui.search.FilterSheet
import com.yg.tgdrive.ui.search.FilterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 主页：底部 3 tab（最近 / 视频 / 图片）+ 设置入口。
 *
 * 视觉基线取 CloudStream / Aniyomi 一类影音应用：
 * 深色近黑底、单一强调色、海报本身承载全部信息，不再在图上叠一排黑底小方块。
 * authState != Ready 时全屏锁定。
 */
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenDownload: () -> Unit = {},
) {
    val authState by ServiceLocator.tdClient.authState.collectAsState()
    var tab by remember { mutableStateOf(1) } // 1 = 视频（默认）

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // ---- 顶栏：小标题 + 副标题 + 设置 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (tab) {
                        0 -> "最近"
                        1 -> "视频"
                        else -> "图片"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    when (tab) {
                        0 -> "最近播放与新增"
                        1 -> "海报墙"
                        else -> "图片流"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 搜索入口
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onOpenSearch),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    AppIcons.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            // 下载入口（进行中徽标）
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onOpenDownload),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        AppIcons.Download,
                        contentDescription = "下载",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                val dlBadge by ServiceLocator.downloadBadge.collectAsState()
                if (dlBadge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    AppIcons.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        // 扫描进度：用 AnimatedVisibility 淡出，避免扫描结束瞬间列表整体上跳
        val scan by ServiceLocator.mediaRepository.scanProgress.collectAsState()
        androidx.compose.animation.AnimatedVisibility(
            visible = scan.running,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        if (scan.scanned == 0 && scan.phase.isNotBlank()) append(scan.phase)
                        else append("正在扫描")
                        if (scan.scanned > 0) append(" · 已扫 ${scan.scanned} · 入库 ${scan.inserted}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> RecentTab()
                1 -> WallTab()
                else -> GalleryTab()
            }
        }

        // ---- 底部三 tab（纯图标：文字已删，42dp 紧凑高度）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .navigationBarsPadding()
                .height(42.dp),
        ) {
            val items = listOf(
                Triple("最近", AppIcons.History, 0),
                Triple("视频", AppIcons.Movie, 1),
                Triple("图片", AppIcons.Image, 2),
            )
            items.forEach { (label, icon, index) ->
                val selected = tab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { tab = index },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.Icon(
                            icon,
                            contentDescription = label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (selected) 23.dp else 21.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 14.dp, height = 2.5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                ),
                        )
                    }
                }
            }
        }
    }

    // 鉴权锁定层
    if (authState !is AuthState.Ready) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    authLockMessage(authState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 最近 tab

/** 最近：继续观看横滑 + 最近入库网格。 */
@Composable
private fun RecentTab() {
    val drives by ServiceLocator.driveManager.observeDrives().collectAsState(initial = emptyList())
    val firstDrive = drives.firstOrNull()?.id
    val flow: Flow<PagingData<MediaEntity>> = remember(firstDrive) {
        firstDrive?.let { ServiceLocator.mediaRepository.observeWall(it, "video") }
            ?: flowOf(PagingData.empty())
    }
    val items: LazyPagingItems<MediaEntity> = flow.collectAsLazyPagingItems()

    if (drives.isEmpty()) {
        EmptyState(
            icon = AppIcons.History,
            title = "还没有驱动器",
            subtitle = "到设置里选一个 Telegram 频道",
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ContinueWatchingRow()
        SectionHeader("最近入库")
        RecentGrid(items)
    }
}

/** 最近入库网格（复用 PosterCell）。 */
@Composable
private fun RecentGrid(items: LazyPagingItems<MediaEntity>) {
    val context = LocalContext.current
    var detailFor by remember { mutableStateOf<MediaEntity?>(null) }
    if (items.loadState.refresh is LoadState.Loading && items.itemCount == 0) {
        PosterSkeletonGrid(2)
        return
    }
    if (items.itemCount == 0) {
        EmptyState(
            icon = AppIcons.History,
            title = "还没有入库内容",
            subtitle = "下拉刷新扫描频道",
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val m = items[index] ?: return@items
            PosterCell(
                m,
                columns = 2,
                onPlay = { target ->
                    val n = items.itemCount.coerceAtMost(400)
                    val ids = ArrayList<Long>(n)
                    var at = -1
                    for (i in 0 until n) {
                        val e = items[i] ?: continue
                        if (e.id == target.id) at = ids.size
                        ids.add(e.id)
                    }
                    openPlayer(context, target.id, ids.toLongArray(), at)
                },
                onInfo = { detailFor = it },
            )
        }
    }
    if (detailFor != null) {
        MediaDetailSheet(media = detailFor!!, onDismiss = { detailFor = null })
    }
}

/** 分区标题。 */
@Composable
private fun SectionHeader(text: String, trailing: String = "") {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (trailing.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 继续观看卡片：16:9 缩略图。 */
@Composable
private fun ContinueCard(m: MediaEntity, onClick: () -> Unit) {
    Column(
        Modifier
            .width(176.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(m.thumbPath?.let { java.io.File(it) })
                    .size(CONTINUE_DECODE_PX)
                    .crossfade(false)
                    .build(),
                contentDescription = m.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            m.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
        )
    }
}

/** 继续观看卡片：176dp 宽 16:9 → 3x 下约 528px，取 560。 */
private val CONTINUE_DECODE_PX = 560

// ---------------------------------------------------------------- 海报墙 tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallTab() {
    val drives by ServiceLocator.driveManager.observeDrives().collectAsState(initial = emptyList())
    var selectedDrive by remember { mutableStateOf<Long?>(null) }
    var detailFor by remember { mutableStateOf<MediaEntity?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(FilterState()) }
    var showFilter by remember { mutableStateOf(false) }
    // 海报墙列数偏好（2/3 列可切换，持久化在 DataStore）
    val wallColumns by ServiceLocator.settings.wallColumns.collectAsState(initial = 2)

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

    val wallFlow: Flow<PagingData<MediaEntity>> = remember(selectedDrive, filter) {
        selectedDrive?.let { d ->
            if (filter.isActive()) {
                ServiceLocator.mediaRepository.observeFilteredWall(
                    driveId = d,
                    kind = "video",
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
                ServiceLocator.mediaRepository.observeWall(d, "video")
            }
        } ?: flowOf(PagingData.empty())
    }
    val wallItems: LazyPagingItems<MediaEntity> = wallFlow.collectAsLazyPagingItems()


    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 布局切换：2 列 ⇄ 3 列（偏好持久化，切换后 Paging 与 Coil 缓存复用）
            LayoutToggleButton(
                columns = wallColumns,
                onToggle = {
                    ServiceLocator.appScope.launch {
                        ServiceLocator.settings.setWallColumns(if (wallColumns >= 3) 2 else 3)
                    }
                },
            )
            Spacer(Modifier.size(8.dp))
            FilterButton(active = filter.isActive(), onClick = { showFilter = true })
        }

        if (drives.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                items(drives, key = { it.id }) { drive ->
                    DriveChip(
                        label = drive.title,
                        selected = selectedDrive == drive.id,
                        onClick = { selectedDrive = drive.id },
                    )
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                val d = selectedDrive ?: return@PullToRefreshBox
                refreshing = true
                ServiceLocator.appScope.launch {
                    try {
                        ServiceLocator.mediaRepository.incrementalSync(d)
                    } finally {
                        refreshing = false
                    }
                }
            },
        ) {
            WallGrid(wallItems, onInfo = { detailFor = it }, columns = wallColumns)
        }
    }

    if (detailFor != null) {
        MediaDetailSheet(media = detailFor!!, onDismiss = { detailFor = null })
    }
}

/** 布局切换按钮：2 列 ⇄ 3 列。 */
@Composable
private fun LayoutToggleButton(columns: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            if (columns >= 3) AppIcons.GridThree else AppIcons.GridTwo,
            contentDescription = if (columns >= 3) "切换到两列" else "切换到三列",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (columns >= 3) "三列" else "两列",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 驱动器 chip。 */
@Composable
private fun DriveChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 海报 Grid

@Composable
private fun WallGrid(
    items: LazyPagingItems<MediaEntity>,
    onInfo: (MediaEntity) -> Unit,
    columns: Int = 2,
) {
    val context = LocalContext.current
    when {
        // 扫描/补封面期间 Room 每次写库都会 invalidate PagingSource，
        // loadState.refresh 反复回到 Loading。只在「确实没有数据」时显示骨架屏，
        // 否则列表会被骨架屏顶掉再重建，表现为持续屏闪。
        items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> {
            PosterSkeletonGrid(columns)
        }
        items.itemCount == 0 -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                EmptyState(
                    icon = AppIcons.Movie,
                    title = "海报墙还是空的",
                    subtitle = "下拉刷新扫描频道\n或把视频转发进驱动器频道",
                )
            }
        }
        else -> {
            Column(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (columns >= 3) 9.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (columns >= 3) 14.dp else 18.dp),
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) { index ->
                        val m = items[index] ?: return@items
                        PosterCell(
                            m,
                            columns = columns,
                            onPlay = { target ->
                                val n = items.itemCount.coerceAtMost(400)
                                val ids = ArrayList<Long>(n)
                                var at = -1
                                for (i in 0 until n) {
                                    val e = items[i] ?: continue
                                    if (e.id == target.id) at = ids.size
                                    ids.add(e.id)
                                }
                                openPlayer(context, target.id, ids.toLongArray(), at)
                            },
                            onInfo = onInfo,
                        )
                    }
                }
            }
        }
    }
}

/** 骨架屏：加载中占位，避免白屏抖动。 */
@Composable
private fun PosterSkeletonGrid(columns: Int = 2) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (columns >= 3) 9.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (columns >= 3) 14.dp else 18.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(if (columns >= 3) 12 else 8) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )
            }
        }
    }
}

/** 统一空态。 */
@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Box(Modifier.fillMaxSize().height(420.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 继续观看横滑行。 */
@Composable
private fun ContinueWatchingRow() {
    val list by ServiceLocator.mediaRepository.observeContinueWatching()
        .collectAsState(initial = emptyList())
    if (list.isEmpty()) return
    val context = LocalContext.current
    Column(Modifier.padding(bottom = 6.dp)) {
        SectionHeader("继续观看")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { m ->
                ContinueCard(m) {
                    val ids = list.map { it.id }.toLongArray()
                    openPlayer(context, m.id, ids, ids.indexOfFirst { it == m.id })
                }
            }
        }
    }
}

/**
 * 单个海报。
 *
 * 版式：2:3 圆角缩略图 + 图内左下时长胶囊 + 图下一行标题。
 * 标题移出图片，避免 CloudStream 早期版本那种「图上叠三行字」的脏感。
 */
@Composable
internal fun PosterCell(
    m: MediaEntity,
    columns: Int = 2,
    onPlay: (MediaEntity) -> Unit,
    onInfo: (MediaEntity) -> Unit,
) {
    // 手势只挂在图片区（Box）上，不包整个 Column：
    // 整卡手势会让标题文字区也参与命中测试，滚动时每帧多一次布局遍历。
    // 外层不再 clip：图片 Box 自己已经圆角裁剪，多一层 clip 等于每格多一个离屏图层，
    // 三列时一屏几十个图层，是实测掉帧的主要来源之一。
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(m.id) {
                    detectTapGestures(
                        onTap = { onPlay(m) },
                        onLongPress = { onInfo(m) },
                    )
                },
        ) {
            // 解码尺寸按列数/屏幕宽度实时算：三列每格更小，按原图或固定 400px 解码
            // 会让 UI 线程在滚动时忙于 Bitmap 分配（实测三列中位帧耗时 44ms）。
            val config = LocalConfiguration.current
            val density = LocalDensity.current.density
            val ctx = LocalContext.current
            val posterPx = PosterSizing.decodePx(config.screenWidthDp, columns, density)
            // ImageRequest 必须在 composition 外构建一次：每次重组 new 一个 Builder
            // 会让 Coil 认为请求变了，反复重建、丢弃缓存键。
            val posterModel = remember(m.thumbPath, posterPx) {
                ImageRequest.Builder(ctx)
                    .data(m.thumbPath?.let { java.io.File(it) })
                    .size(posterPx)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = posterModel,
                contentDescription = m.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 底部极轻渐变，只为托住时长胶囊，不压暗画面
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                        )
                    ),
            )

            val dur = formatDuration(m.durationMs)
            if (dur.isNotEmpty()) {
                Text(
                    dur,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            // 详情入口：右下角。视觉保持小圆点，外层 48dp 透明热区保证可点。
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .pointerInput(m.id) { detectTapGestures { onInfo(m) } },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        AppIcons.More,
                        contentDescription = "详情",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(
            m.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
    }
}
