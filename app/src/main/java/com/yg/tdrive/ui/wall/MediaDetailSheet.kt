package com.yg.tgdrive.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDate
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.common.formatSize
import com.yg.tgdrive.ui.player.openPlayer
import kotlinx.coroutines.launch

/**
 * 详情底部弹层。
 *
 * 版式：大封面 → 标题/元信息 → 主操作按钮（播放/重播）→
 * 一排图标动作（收藏/下载/分享/删除）→ 跳转源消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(
    media: MediaEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
        ) {
            // ---- 封面 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = media.thumbPath?.let { java.io.File(it) },
                    contentDescription = media.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.5f),
                            )
                        )
                )
                val dur = formatDuration(media.durationMs)
                if (dur.isNotEmpty()) {
                    Text(
                        dur,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 标题 ----
            Text(
                media.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(6.dp))

            // ---- 元信息 ----
            Text(
                buildString {
                    append(formatSize(media.size))
                    formatDuration(media.durationMs).takeIf { it.isNotEmpty() }?.let {
                        append(" · ").append(it)
                    }
                    media.width?.takeIf { it > 0 }?.let { w ->
                        media.height?.takeIf { it > 0 }?.let { h ->
                            append(" · ").append(w).append("×").append(h)
                        }
                    }
                    append(" · ").append(formatDate(media.messageDate))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            actionError?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(18.dp))

            // ---- 主操作 ----
            if (isVideo(media)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction(
                        icon = AppIcons.Play,
                        text = "播放",
                        modifier = Modifier.weight(1f),
                    ) { openPlayer(context, media.id) }
                    SecondaryAction(
                        text = "从头播",
                        modifier = Modifier.weight(1f),
                    ) {
                        scope.launch {
                            try {
                                ServiceLocator.mediaRepository.resetProgress(media.id)
                                openPlayer(context, media.id)
                                onDismiss()
                            } catch (t: Throwable) {
                                actionError = "操作失败：${t.message}"
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ---- 图标动作行 ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconAction(AppIcons.Download, "下载") {
                    ServiceLocator.offlineDownloads.enqueue(media)
                    android.widget.Toast.makeText(
                        context, "已加入下载：${media.displayName}", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                IconAction(AppIcons.Share, "分享") {
                    shareLink(context, media)
                }
                IconAction(AppIcons.Info, "详情") {
                    openSourceMessage(context, media)
                }
                IconAction(AppIcons.Delete, "删除", danger = true) {
                    showDeleteConfirm = true
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(media = media, onDismiss = { showRename = false })
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("删除这个文件？") },
            text = {
                Text(
                    "将从 Telegram 频道中彻底删除「${media.displayName}」，所有会话中都不可见，且不可撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        try {
                            ServiceLocator.mediaRepository.delete(media)
                            onDismiss()
                        } catch (t: Throwable) {
                            actionError = "删除失败：${t.message}"
                        }
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 主操作按钮：实心主色胶囊。 */
@Composable
private fun PrimaryAction(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 次操作按钮：半透明填充胶囊。 */
@Composable
private fun SecondaryAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 图标动作（圆底 + 图标 + 文字）。 */
@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (danger) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun isVideo(m: MediaEntity) = m.kind == "video"

/** tg:// 深链跳源消息。 */
private fun openSourceMessage(context: android.content.Context, m: MediaEntity) {
    val deepLink = "tg://message?chat_id=${m.chatId}&message_id=${m.messageId}"
    try {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(deepLink),
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (t: Throwable) {
        android.widget.Toast.makeText(context, "未找到 Telegram 客户端", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/** 分享 t.me 链接。 */
private fun shareLink(context: android.content.Context, m: MediaEntity) {
    val link = "https://t.me/c/${m.chatId.toString().removePrefix("-100")}/${m.messageId}"
    try {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "${m.displayName}\n$link")
        }
        context.startActivity(
            android.content.Intent.createChooser(send, "分享").addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        )
    } catch (t: Throwable) {
        android.widget.Toast.makeText(context, "分享失败", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------- 重命名对话框

@Composable
private fun RenameDialog(
    media: MediaEntity,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(media.displayName) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("重命名") },
        text = {
            Column {
                Text(
                    "仅修改本地显示名，不会改动 Telegram 云端消息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("新名称") },
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) {
                    error = "名称不能为空"
                    return@TextButton
                }
                scope.launch {
                    try {
                        ServiceLocator.mediaRepository.rename(media, trimmed)
                        onDismiss()
                    } catch (t: Throwable) {
                        error = "重命名失败：${t.message}"
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
