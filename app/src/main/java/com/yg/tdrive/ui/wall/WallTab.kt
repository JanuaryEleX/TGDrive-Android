package com.yg.tgdrive.ui.wall

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.yg.tgdrive.R
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.td.AuthState
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.common.formatSize
import com.yg.tgdrive.ui.gallery.GalleryTab
import com.yg.tgdrive.ui.nav.authLockMessage
import com.yg.tgdrive.ui.player.openPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 主页：BottomNav 2 tab（海报墙 / 图片）+ 设置入口（SPEC §4.5）。
 * authState != Ready 时全屏锁定主页内容。
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val authState by ServiceLocator.tdClient.authState.collectAsState()
    var tab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 大标题顶栏（沉浸式，无 Surface 卡片感）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "TG 云盘",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (tab == 0) "视频海报墙" else "图片流",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> WallTab()
                else -> GalleryTab()
            }
        }

        // 无阴影底栏，融入背景
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                label = { Text("视频") },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_tab_image),
                        contentDescription = null,
                    )
                },
                label = { Text("图片") },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }

    // 鉴权锁定层：非 Ready 全屏覆盖（SPEC §2 / 任务书第 2 条）
    if (authState !is AuthState.Ready) {
        val message = authLockMessage(authState)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- 海报墙 tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallTab() {
    val drives by ServiceLocator.driveManager.observeDrives().collectAsState(initial = emptyList())
    var selectedDrive by remember { mutableStateOf<Long?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaEntity>?>(null) }
    var detailFor by remember { mutableStateOf<MediaEntity?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 默认选第一个驱动器
    LaunchedEffect(drives) {
        if (selectedDrive == null && drives.isNotEmpty()) selectedDrive = drives.first().id
    }

    // 分页流：无条件收集（Compose 规则）；无驱动器时空流
    val wallFlow: Flow<PagingData<MediaEntity>> = remember(selectedDrive) {
        selectedDrive?.let {
            ServiceLocator.mediaRepository.observeWall(it, "video")
        } ?: flowOf(PagingData.empty())
    }
    val wallItems: LazyPagingItems<MediaEntity> = wallFlow.collectAsLazyPagingItems()

    // 搜索防抖（search 为 suspend，须在协程内调用）
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = null
        } else {
            delay(300)
            searchResults = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ServiceLocator.mediaRepository.search(searchQuery.trim())
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 胶囊搜索框（填充式，无描边）
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text("搜索文件名…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // 驱动器 chip 横滑
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(drives, key = { it.id }) { drive ->
                FilterChip(
                    selected = selectedDrive == drive.id,
                    onClick = { selectedDrive = drive.id },
                    label = { Text(drive.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }

        // 下拉刷新 → incrementalSync(driveId)（任务书第 7 条）
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                val d = selectedDrive ?: return@PullToRefreshBox
                scope.launch {
                    refreshing = true
                    try {
                        ServiceLocator.mediaRepository.incrementalSync(d)
                    } finally {
                        refreshing = false
                    }
                }
            },
        ) {
            when {
                searchResults != null -> SearchResultGrid(searchResults!!) { detailFor = it }
                else -> WallGrid(wallItems) { detailFor = it }
            }
        }
    }

    // 详情底部弹层
    if (detailFor != null) {
        MediaDetailSheet(media = detailFor!!, onDismiss = { detailFor = null })
    }
}

// ---------------------------------------------------------------- 海报 Grid

@Composable
private fun WallGrid(items: LazyPagingItems<MediaEntity>, onOpen: (MediaEntity) -> Unit) {
    when {
        items.loadState.refresh is LoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        items.itemCount == 0 -> {
            EmptyState(
                icon = "🎬",
                title = "海报墙还是空的",
                subtitle = "把视频转发进驱动器频道\n即可自动入库",
            )
        }
        else -> {
            Column(Modifier.fillMaxSize()) {
                ContinueWatchingRow()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) { index ->
                        val m = items[index] ?: return@items
                        PosterCell(m, onOpen)
                    }
                }
            }
        }
    }
}

/** 统一空态：大符号 + 主副文案。 */
@Composable
private fun EmptyState(
    icon: String,
    title: String,
    subtitle: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

/** 继续观看横滑行（有进度未看完的 video，数据层过滤）。 */
@Composable
private fun ContinueWatchingRow() {
    val list by ServiceLocator.mediaRepository.observeContinueWatching()
        .collectAsState(initial = emptyList())
    if (list.isEmpty()) return
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            "继续观看",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { m ->
                Box(
                    modifier = Modifier
                        .width(150.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { openPlayer(context, m.id) },
                ) {
                    AsyncImage(
                        model = m.thumbPath?.let { java.io.File(it) },
                        contentDescription = m.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 底部渐变遮罩 + 标题内嵌
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.85f),
                                )
                            )
                    )
                    Text(
                        m.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** 单个海报：缩略图 + 底部渐变 + 时长角标（左下）+ 大小角标（右下）。 */
@Composable
internal fun PosterCell(m: MediaEntity, onOpen: (MediaEntity) -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(m) },
    ) {
        AsyncImage(
            model = m.thumbPath?.let { java.io.File(it) },
            contentDescription = m.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 底部渐变遮罩（Netflix 风）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    )
                )
        )
        // 标题内嵌渐变区
        Text(
            m.displayName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 20.dp),
        )
        val dur = formatDuration(m.durationMs)
        if (dur.isNotEmpty()) {
            Text(
                dur,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Text(
            formatSize(m.size),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        )
    }
}

/** 搜索结果 3 列 Grid（本地 FTS，不分页）。 */
@Composable
private fun SearchResultGrid(results: List<MediaEntity>, onOpen: (MediaEntity) -> Unit) {
    if (results.isEmpty()) {
        EmptyState(
            icon = "🔍",
            title = "没有匹配的文件",
            subtitle = "换个关键词试试",
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(results.size) { i -> PosterCell(results[i], onOpen) }
    }
}
