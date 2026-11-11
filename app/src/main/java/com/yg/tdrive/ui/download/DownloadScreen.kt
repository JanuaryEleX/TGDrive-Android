package com.yg.tgdrive.ui.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.data.DownloadTask
import com.yg.tgdrive.ui.common.formatSize
import java.io.File

/**
 * 下载管理页（Wave1-B 离线下载）。
 *
 * - 列出全部离线下载任务：文件名 / 进度条 / 已下&总大小 / 状态 / 操作按钮。
 * - 顶部「全部暂停 / 全部开始」；空态「暂无下载」。
 * - 进入时请求 Android 13+ 通知权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val tasks by ServiceLocator.offlineDownloads.tasks.collectAsState(initial = emptyList())

    // Android 13+ 通知权限（首次进入请求一次）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不阻塞下载 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val hasRunning = tasks.any {
        it.state == DownloadTask.STATE_QUEUED || it.state == DownloadTask.STATE_DOWNLOADING
    }
    val hasPaused = tasks.any { it.state == DownloadTask.STATE_PAUSED }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = { Text("下载管理") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                if (hasRunning) {
                    TextButton(onClick = { ServiceLocator.offlineDownloads.pauseAll() }) {
                        Text("全部暂停")
                    }
                }
                if (hasPaused) {
                    TextButton(onClick = { ServiceLocator.offlineDownloads.resumeAll() }) {
                        Text("全部开始")
                    }
                }
            },
        )

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无下载", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadRow(task = task)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(task: DownloadTask) {
    val context = LocalContext.current
    val progress = if (task.bytesTotal > 0L) {
        (task.bytesDone.toFloat() / task.bytesTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stateLabel(task.state),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor(task.state),
                )
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (task.state == DownloadTask.STATE_COMPLETED) 1f else progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append(formatSize(task.bytesDone))
                    append(" / ")
                    append(formatSize(task.bytesTotal))
                    if (task.bytesTotal > 0L) {
                        append(" · ").append((progress * 100).toInt()).append("%")
                    }
                    task.errorMsg?.takeIf { task.state == DownloadTask.STATE_FAILED }?.let {
                        append(" · ").append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.state) {
                    DownloadTask.STATE_QUEUED,
                    DownloadTask.STATE_DOWNLOADING -> {
                        ActionButton("暂停") { ServiceLocator.offlineDownloads.pause(task.id) }
                        ActionButton("取消", outlined = true) {
                            ServiceLocator.offlineDownloads.cancel(task.id)
                        }
                    }
                    DownloadTask.STATE_PAUSED -> {
                        ActionButton("继续") { ServiceLocator.offlineDownloads.resume(task.id) }
                        ActionButton("取消", outlined = true) {
                            ServiceLocator.offlineDownloads.cancel(task.id)
                        }
                    }
                    DownloadTask.STATE_COMPLETED -> {
                        ActionButton("打开") { openDownloaded(context, task) }
                        ActionButton("删除", outlined = true) {
                            ServiceLocator.offlineDownloads.deleteTask(task.id)
                        }
                    }
                    DownloadTask.STATE_FAILED,
                    DownloadTask.STATE_CANCELLED -> {
                        ActionButton("重试") { ServiceLocator.offlineDownloads.retry(task.id) }
                        ActionButton("删除", outlined = true) {
                            ServiceLocator.offlineDownloads.deleteTask(task.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    if (outlined) {
        OutlinedButton(onClick = onClick) { Text(text) }
    } else {
        Button(onClick = onClick) { Text(text) }
    }
}

private fun stateLabel(state: Int): String = when (state) {
    DownloadTask.STATE_QUEUED -> "排队中"
    DownloadTask.STATE_DOWNLOADING -> "下载中"
    DownloadTask.STATE_PAUSED -> "已暂停"
    DownloadTask.STATE_COMPLETED -> "已完成"
    DownloadTask.STATE_FAILED -> "失败"
    DownloadTask.STATE_CANCELLED -> "已取消"
    else -> "未知"
}

@Composable
private fun stateColor(state: Int) = when (state) {
    DownloadTask.STATE_COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadTask.STATE_FAILED -> MaterialTheme.colorScheme.error
    DownloadTask.STATE_DOWNLOADING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 打开已下载文件：content:// 直接 ACTION_VIEW；私有 file:// 走系统尝试（失败提示路径）。 */
private fun openDownloaded(context: Context, task: DownloadTask) {
    val path = task.targetPath ?: return
    val mime = if (task.kind == "image") "image/*" else "video/*"
    try {
        val uri: Uri =
            if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (t: Throwable) {
        android.widget.Toast.makeText(
            context,
            "文件已保存到：$path",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}
