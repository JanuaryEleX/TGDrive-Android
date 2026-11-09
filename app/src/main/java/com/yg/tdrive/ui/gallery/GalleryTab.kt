package com.yg.tgdrive.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDate
import com.yg.tgdrive.ui.common.formatSize
import com.yg.tgdrive.ui.wall.MediaDetailSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 图片 tab：2 列瀑布流（SPEC §4.5）。驱动器切换与海报墙一致。
 */
@Composable
fun GalleryTab() {
    val drives by ServiceLocator.driveManager.observeDrives().collectAsState(initial = emptyList())
    var selectedDrive by remember { mutableStateOf<Long?>(null) }
    var detailFor by remember { mutableStateOf<MediaEntity?>(null) }

    LaunchedEffect(drives) {
        if (selectedDrive == null && drives.isNotEmpty()) selectedDrive = drives.first().id
    }

    val imageFlow: Flow<PagingData<MediaEntity>> = remember(selectedDrive) {
        selectedDrive?.let { ServiceLocator.mediaRepository.observeWall(it, "image") }
            ?: flowOf(PagingData.empty())
    }
    val items: LazyPagingItems<MediaEntity> = imageFlow.collectAsLazyPagingItems()

    Column(Modifier.fillMaxSize()) {
        // 驱动器 chip 横滑（复用海报墙风格）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(drives, key = { it.id }) { drive ->
                FilterChip(
                    selected = selectedDrive == drive.id,
                    onClick = { selectedDrive = drive.id },
                    label = { Text(drive.title) },
                )
            }
        }

        when {
            items.loadState.refresh is LoadState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            items.itemCount == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无图片，把图片转发进驱动器频道即可自动入库",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = items.itemCount,
                        key = items.itemKey { it.id },
                    ) { index ->
                        val m = items[index] ?: return@items
                        ImageCell(m) { detailFor = m }
                    }
                }
            }
        }
    }

    detailFor?.let {
        MediaDetailSheet(media = it, onDismiss = { detailFor = null })
    }
}

/** 瀑布流单元：缩略图原始宽高比 + 底部渐变信息条。 */
@Composable
private fun ImageCell(m: MediaEntity, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen),
    ) {
        val w = m.width?.takeIf { it > 0 } ?: 3
        val h = m.height?.takeIf { it > 0 } ?: 4
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.5f, 2.2f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio),
            ) {
                AsyncImage(
                    model = m.thumbPath?.let { java.io.File(it) },
                    contentDescription = m.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 底部渐变信息条（叠加在图上）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.6f to androidx.compose.ui.graphics.Color.Transparent,
                        1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f),
                    )
                )
        )
        Text(
            "${formatSize(m.size)} · ${formatDate(m.messageDate)}",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
