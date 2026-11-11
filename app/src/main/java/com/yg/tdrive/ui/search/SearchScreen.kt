package com.yg.tgdrive.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.player.openPlayer
import com.yg.tgdrive.ui.wall.MediaDetailSheet
import com.yg.tgdrive.ui.wall.PosterCell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** 每页条数（搜索结果 / 筛选项分页）。 */
private const val PAGE_SIZE = 40

/**
 * 全局搜索页：视频 + 图片统一搜索。
 *
 * - 顶部：返回 + 搜索输入框（进入自动聚焦）+ 右侧「筛选」
 * - 输入即时搜索（~300ms 防抖），结果 3 列网格混排
 * - 视频点开走 openPlayer；图片点开弹 MediaDetailSheet
 * - 滚到底自动加载下一页；空输入/空结果有提示
 * - 应用筛选后按 FilterState 走 filtered()（名称关键词在筛选模式下做本地过滤）
 */
@Composable
fun SearchScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val repo = ServiceLocator.mediaRepository
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FilterState()) }
    var showFilter by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var detailFor by remember { mutableStateOf<MediaEntity?>(null) }

    val focus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // 进入自动聚焦
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
    }

    // 取一页数据（query / filter 组合）
    suspend fun fetchPage(p: Int): List<MediaEntity> {
        val q = query.trim()
        val active = filter.isActive()
        val off = p * PAGE_SIZE
        return when {
            q.isNotEmpty() && !active ->
                repo.searchAll(query = q, kind = null, limit = PAGE_SIZE, offset = off)

            active ->
                repo.filtered(
                    kind = filter.kind,
                    minSize = filter.minSizeBytes,
                    maxSize = filter.maxSizeBytes,
                    minDurationMs = filter.minDurationMs,
                    maxDurationMs = filter.maxDurationMs,
                    dateFrom = filter.dateFromSec,
                    dateTo = filter.dateToSec,
                    sortBy = filter.sortBy,
                    desc = filter.desc,
                    limit = PAGE_SIZE,
                    offset = off,
                ).let { list ->
                    // 筛选模式下若还带关键词，则在页内按文件名过滤
                    if (q.isEmpty()) list else list.filter { it.displayName.contains(q, ignoreCase = true) }
                }

            else -> emptyList()
        }
    }

    suspend fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        try {
            val next = page + 1
            val data = fetchPage(next)
            if (data.isNotEmpty()) {
                results = results + data
                page = next
            }
            hasMore = data.size >= PAGE_SIZE
        } finally {
            loading = false
        }
    }

    // query / filter 变化：防抖后重置并拉第一页
    LaunchedEffect(query, filter) {
        if (query.trim().isEmpty() && !filter.isActive()) {
            results = emptyList()
            page = 0
            hasMore = false
            hasSearched = false
            loading = false
            return@LaunchedEffect
        }
        delay(300)
        loading = true
        hasSearched = true
        results = emptyList()
        page = 0
        hasMore = false
        try {
            val data = fetchPage(0)
            results = data
            hasMore = data.size >= PAGE_SIZE
        } finally {
            loading = false
        }
    }

    // 滚到底加载下一页
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= 0 && hasMore && !loading && results.isNotEmpty() && last >= results.size - 3) {
                    loadMore()
                }
            }
    }

    if (showFilter) {
        FilterSheet(
            initial = filter,
            onDismiss = { showFilter = false },
            onApply = {
                filter = it
                showFilter = false
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // ---- 顶栏：返回 + 搜索框 + 筛选 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "搜索视频与图片文件名…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { query = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            FilterButton(active = filter.isActive(), onClick = { showFilter = true })
        }

        Box(Modifier.weight(1f)) {
            when {
                !hasSearched && results.isEmpty() && !loading -> {
                    HintState(
                        title = "输入关键词开始搜索",
                        subtitle = "支持按视频/图片文件名搜索，可用右上角「筛选」进一步过滤",
                    )
                }

                loading && results.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                results.isEmpty() -> {
                    HintState(
                        title = "没有匹配的结果",
                        subtitle = "换个关键词，或放宽筛选条件试试",
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(results, key = { _, m -> m.id }) { _, m ->
                            PosterCell(
                                m = m,
                                onPlay = { target ->
                                    if (target.kind == "video") {
                                        val ids = results.take(400).map { it.id }.toLongArray()
                                        openPlayer(
                                            context,
                                            target.id,
                                            ids,
                                            ids.indexOfFirst { it == target.id },
                                        )
                                    } else {
                                        detailFor = target
                                    }
                                },
                                onInfo = { detailFor = it },
                            )
                        }
                        if (loading && results.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detailFor?.let { m ->
        MediaDetailSheet(media = m, onDismiss = { detailFor = null })
    }
}

@Composable
private fun HintState(title: String, subtitle: String) {
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
                    AppIcons.Search,
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
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}
