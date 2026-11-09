package com.yg.tgdrive.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.MediaEntity
import com.yg.tgdrive.ui.common.formatDate
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.common.formatSize
import com.yg.tgdrive.ui.player.openPlayer
import kotlinx.coroutines.launch

/**
 * 详情底部弹层（SPEC §4.5 详情）：封面大图 + 元信息 +
 * 继续播放/重播（跳 PlayerActivity，extra mediaId:Long）+
 * 重命名对话框 + 删除确认 + 跳转 TG 源消息（tg:// 深链）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailSheet(
    media: MediaEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // 封面大图
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = media.thumbPath?.let { java.io.File(it) },
                    contentDescription = media.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(12.dp))

            Text(
                media.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            // 元信息
            val info = buildString {
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
            }
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            actionError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            // 播放按钮组
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { openPlayer(context, media.id) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (isVideo(media)) "继续播放" else "查看") }
                if (isVideo(media)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    // 重播：清进度后开播
                                    ServiceLocator.mediaRepository.resetProgress(media.id)
                                    openPlayer(context, media.id)
                                    onDismiss()
                                } catch (t: Throwable) {
                                    actionError = "操作失败：${t.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("重播") }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 管理按钮组
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { showRename = true },
                    modifier = Modifier.weight(1f),
                ) { Text("重命名") }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.weight(1f),
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }

            Spacer(Modifier.height(12.dp))

            // 跳转 TG 源消息：tg:// 深链（t.me 私有频道链接亦可直达消息）
            TextButton(onClick = { openSourceMessage(context, media) }) {
                Text("在 Telegram 中查看源消息")
            }
        }
    }

    if (showRename) {
        RenameDialog(
            media = media,
            onDismiss = { showRename = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = {
                Text(
                    "将从 Telegram 频道中删除「${media.displayName}」。\n" +
                        "该操作会同时删除云端消息（revoke），所有会话中均不可见，且不可撤销。",
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
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

private fun isVideo(m: MediaEntity) = m.kind == "video"

/** tg:// 深链跳源消息（官方 TG 客户端可解析 chat_id + message_id）。 */
private fun openSourceMessage(context: android.content.Context, m: MediaEntity) {
    val deepLink = "tg://message?chat_id=${m.chatId}&message_id=${m.messageId}"
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(deepLink))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (t: Throwable) {
        android.widget.Toast.makeText(context, "未找到 Telegram 客户端", android.widget.Toast.LENGTH_SHORT).show()
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
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
