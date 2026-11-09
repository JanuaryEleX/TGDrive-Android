package com.yg.tgdrive.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.DriveEntity
import com.yg.tgdrive.ui.common.ProxyEditor
import com.yg.tgdrive.ui.common.ProxyMode
import kotlinx.coroutines.launch

/**
 * 设置页（SPEC §4.5）：驱动器管理（列表+删除+手动重扫）· 并发滑杆 1~8 ·
 * 缓存上限滑杆 1~64GB + 清空缓存 · 代理修改（复用 ProxyEditor）· 退出登录 · 关于。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 滑杆本地状态（拖动中即时显示，change 结束落库）
    val concurrencyFlow = ServiceLocator.settings.concurrency
    val cacheCapFlow = ServiceLocator.settings.cacheCapGb
    val concurrency by concurrencyFlow.collectAsState(initial = 4)
    val cacheCapGb by cacheCapFlow.collectAsState(initial = 4)
    var concurrencyDraft by remember(concurrency) { mutableStateOf(concurrency.toFloat()) }
    var cacheDraft by remember(cacheCapGb) { mutableStateOf(cacheCapGb.toFloat()) }

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DriveSection()

            // ---------- 并发滑杆 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("下载并发数：${concurrencyDraft.toInt()}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1~8 路，实时生效；数值越大下载/流播越快，也可能更容易触发服务端限速。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = concurrencyDraft,
                        onValueChange = { concurrencyDraft = it },
                        valueRange = 1f..8f,
                        steps = 6,
                    )
                    Button(
                        onClick = { scope.launch { ServiceLocator.settings.setConcurrency(concurrencyDraft.toInt()) } },
                        enabled = concurrencyDraft.toInt() != concurrency,
                    ) { Text("应用并发设置") }
                }
            }

            // ---------- 缓存滑杆 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("缓存上限：${cacheDraft.toInt()} GB", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "已播放分片的磁盘缓存总量上限（LRU 逐出），1~64 GB。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = cacheDraft,
                        onValueChange = { cacheDraft = it },
                        valueRange = 1f..64f,
                        steps = 62,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { scope.launch { ServiceLocator.settings.setCacheCapGb(cacheDraft.toInt()) } },
                            enabled = cacheDraft.toInt() != cacheCapGb,
                        ) { Text("应用缓存设置") }
                        OutlinedButton(onClick = { showClearCacheConfirm = true }) { Text("清空缓存") }
                    }
                }
            }

            // ---------- 代理 ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("代理", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "修改后需重启应用生效（TDLib 不支持运行时换代理）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ProxyEditor(initialMode = ProxyMode.None, onSaved = { /* 已提示重启 */ })
                }
            }

            // ---------- 退出登录 ----------
            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }

            // ---------- 关于 ----------
            OutlinedButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) {
                Text("关于")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 清空缓存确认
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清空缓存") },
            text = { Text("将删除所有已缓存的视频分片与缩略图缓存（不影响云端与索引），确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    scope.launch { runCatching { ServiceLocator.mediaRepository.clearCache() } }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } },
        )
    }

    // 退出登录确认
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的 api 凭据与登录会话，并重启应用。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    scope.launch {
                        ServiceLocator.credentials.clear()
                        // 重启进程（TDLib 会话库保留，下次启动自动恢复会话）
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") } },
        )
    }

    // 关于
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 TG云盘") },
            text = {
                Text(
                    "TG云盘 v0.1.0\n\n" +
                        "用 Telegram 专用小号的私有频道当作私人网盘，" +
                        "TDLib 直连 MTProto 流播视频与图片，全程无自建服务端。\n\n" +
                        "免责：TG 云端无 SLA，请为重要内容保留独立备份；本应用仅供个人自用。",
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } },
        )
    }
}

// ---------------------------------------------------------------- 驱动器管理

@Composable
private fun DriveSection() {
    val drives by ServiceLocator.driveManager.observeDrives().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDeleteFor by remember { mutableStateOf<DriveEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("驱动器管理", style = MaterialTheme.typography.titleMedium)
            Text(
                "每个驱动器对应一个 Telegram 频道/群组。删除驱动器将同时清除其本地索引（不影响云端消息）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (drives.isEmpty()) {
                Text("暂无驱动器", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                drives.forEach { drive ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(drive.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "chat ${drive.chatId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    ServiceLocator.driveManager.rescan(drive.id)
                                } catch (t: Throwable) {
                                    error = "重扫失败：${t.message}"
                                }
                            }
                        }) { Text("重扫") }
                        TextButton(onClick = { confirmDeleteFor = drive }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmDeleteFor != null) {
        val drive = confirmDeleteFor!!
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = { Text("删除驱动器") },
            text = { Text("确定删除「${drive.title}」？其本地索引将一并清除，云端消息不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val d = drive
                    confirmDeleteFor = null
                    scope.launch {
                        try {
                            ServiceLocator.driveManager.removeDrive(d.id)
                        } catch (t: Throwable) {
                            error = "删除失败：${t.message}"
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFor = null }) { Text("取消") } },
        )
    }
}
