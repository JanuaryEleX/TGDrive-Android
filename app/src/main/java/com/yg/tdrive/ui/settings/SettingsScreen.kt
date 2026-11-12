package com.yg.tgdrive.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yg.tgdrive.BuildConfig
import com.yg.tgdrive.CrashLogger
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.data.ScanDiagnostics
import com.yg.tgdrive.data.SelectableChat
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.ui.common.DriveEntity
import com.yg.tgdrive.ui.common.ProxyEditor
import com.yg.tgdrive.ui.common.ProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 【临时诊断】重扫取证入口的状态（定位视频丢因后整块删除）
    var diagRunning by remember { mutableStateOf(false) }
    var diagState by remember { mutableStateOf<String?>(null) }
    // 本地崩溃日志条数：每次打开「关于」时重读（期间可能又有新崩溃）
    var crashCount by remember { mutableStateOf(0) }
    LaunchedEffect(showAbout) {
        if (showAbout) {
            crashCount = withContext(Dispatchers.IO) { CrashLogger.count(context) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
            // ---------- 主题 ----------
            ThemeSection()

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
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = concurrencyDraft,
                        onValueChange = { concurrencyDraft = it },
                        valueRange = 1f..8f,
                        steps = 6,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { ServiceLocator.settings.setConcurrency(concurrencyDraft.toInt()) } },
                        enabled = concurrencyDraft.toInt() != concurrency,
                        modifier = Modifier.fillMaxWidth(),
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
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = cacheDraft,
                        onValueChange = { cacheDraft = it },
                        valueRange = 1f..64f,
                        steps = 62,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { scope.launch { ServiceLocator.settings.setCacheCapGb(cacheDraft.toInt()) } },
                            enabled = cacheDraft.toInt() != cacheCapGb,
                            modifier = Modifier.weight(1f),
                        ) { Text("应用缓存设置") }
                        OutlinedButton(
                            onClick = { showClearCacheConfirm = true },
                            modifier = Modifier.weight(1f),
                        ) { Text("清空缓存") }
                    }
                }
            }

            DownloadStoreSection()

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
                    ProxyEditor(initialMode = ProxyMode.Direct, onSaved = { /* 已提示重启 */ })
                }
            }

            // ---------- API 凭据 ----------
            CredentialsSection()

            // ---------- 其它（与上面卡片同构） ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("其它", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "会话与关于",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // 【临时诊断】一键全量重扫并落 scan-diag.json（定位视频丢因后删除）
                    OutlinedButton(
                        onClick = {
                            if (diagRunning) return@OutlinedButton
                            diagRunning = true
                            val report = java.io.File(context.filesDir, ScanDiagnostics.FILE_NAME).absolutePath
                            diagState = "扫描中…（全量，约数分钟）"
                            // 用 appScope：离开设置页不取消扫描，报告一定能落盘
                            ServiceLocator.appScope.launch {
                                try {
                                    val drive = ServiceLocator.driveManager
                                        .observeDrives().firstOrNull()?.firstOrNull()
                                    if (drive == null) {
                                        diagState = "没有驱动器，无法扫描"
                                        return@launch
                                    }
                                    ScanDiagnostics.reset()
                                    ServiceLocator.mediaRepository.fullScan(drive.id)
                                    diagState = "结束 → $report\n${ScanDiagnostics.summary()}"
                                } catch (t: Throwable) {
                                    diagState = "失败：${t.message ?: t.javaClass.simpleName}"
                                } finally {
                                    diagRunning = false
                                }
                            }
                        },
                        enabled = !diagRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重扫并生成诊断报告（含相册封面）") }
                    diagState?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showLogoutConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showAbout = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("关于") }
                }
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
                Column {
                    Text(
                        "TG云盘 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" +
                            "用 Telegram 专用小号的私有频道当作私人网盘，" +
                            "TDLib 直连 MTProto 流播视频与图片，全程无自建服务端。\n\n" +
                            "免责：TG 云端无 SLA，请为重要内容保留独立备份；本应用仅供个人自用。",
                    )
                    // 只有存在崩溃日志时才显示（无崩溃 = 不打扰）
                    if (crashCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "崩溃日志：$crashCount 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    crashCount = withContext(Dispatchers.IO) {
                                        CrashLogger.clear(context)
                                    }
                                }
                            }) { Text("清空") }
                        }
                    }
                }
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
    var showAdd by remember { mutableStateOf(false) }

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

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    error = null
                    showAdd = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("＋ 新增驱动器")
            }
        }
    }

    if (showAdd) {
        AddDriveDialog(
            existingChatIds = drives.map { it.chatId }.toSet(),
            onDismiss = { showAdd = false },
            onError = { error = it },
        )
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

// ---------------------------------------------------------------- 新增驱动器

/**
 * 新增驱动器对话框：拉取 TG 聊天列表供勾选，已入库的驱动器置灰不可选。
 * 选中后走 DriveManager.addDrive（chat_id UNIQUE 幂等）并立即首扫，
 * 与引导页 DrivePickerStep 的行为保持一致。
 */
@Composable
private fun AddDriveDialog(
    existingChatIds: Set<Long>,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var chats by remember { mutableStateOf<List<SelectableChat>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            chats = ServiceLocator.driveManager.listSelectableChats()
        } catch (t: Throwable) {
            loadError = "拉取聊天列表失败：${t.message ?: t::class.java.simpleName}"
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("新增驱动器") },
        text = {
            Column {
                Text(
                    "勾选用作存储的频道/群组，保存后立即执行首次全量扫描。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text("正在拉取聊天列表…", style = MaterialTheme.typography.bodyMedium)
                    }
                    loadError != null -> Text(
                        loadError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    chats.isEmpty() -> Text(
                        "没有可选的频道/群组",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        val filtered = remember(chats, query) {
                            val q = query.trim()
                            if (q.isEmpty()) chats
                            else chats.filter { it.title.contains(q, ignoreCase = true) }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("搜索频道/群组", style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = {
                                Icon(
                                    AppIcons.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    TextButton(onClick = { query = "" }) { Text("清空") }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                        )
                        if (filtered.isEmpty()) {
                            Text(
                                "没有匹配「$query」的频道/群组",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(filtered, key = { it.chatId }) { chat ->
                                val already = chat.chatId in existingChatIds
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Checkbox(
                                        checked = already || chat.chatId in picked,
                                        enabled = !already && !saving,
                                        onCheckedChange = { on ->
                                            picked = if (on) picked + chat.chatId
                                            else picked - chat.chatId
                                        },
                                    )
                                    Column(Modifier.padding(start = 2.dp)) {
                                        Text(
                                            chat.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (already) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            if (already) "已添加" else chat.type,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && picked.isNotEmpty(),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val added = chats.filter { it.chatId in picked }.map {
                                ServiceLocator.driveManager.addDrive(it.chatId, it.title)
                            }
                            // 首扫是分钟级任务，挂 appScope 避免对话框关闭后协程被取消
                            ServiceLocator.appScope.launch {
                                added.forEach { d ->
                                    runCatching { ServiceLocator.mediaRepository.fullScan(d.id) }
                                }
                            }
                            onDismiss()
                        } catch (t: Throwable) {
                            onError("新增驱动器失败：${t.message}")
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------------------------------------------------------- API 凭据

private val ApiHashPattern = Regex("^[0-9a-fA-F]{32}$")

/** 只露前 6 位，绝不回显完整 api_hash（短于 7 位则整体隐藏）。 */
private fun maskHash(hash: String): String =
    if (hash.length > 6) hash.take(6) + "…" else "…"

/**
 * 自定义 API 凭据（TDLib api_id/api_hash）：可折叠区块。
 * - 状态行显示当前来源（内置 / 自定义）+ api_id + 掩码 hash
 * - 校验：api_id 正整数、api_hash 32 位十六进制；不合法禁用保存并就地提示
 * - 保存/恢复内置写 CredentialsStore，重启应用后生效
 */
@Composable
private fun CredentialsSection() {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var idText by remember { mutableStateOf("") }
    var hashText by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val saved = withContext(Dispatchers.IO) {
            runCatching { ServiceLocator.credentials.get() }.getOrNull()
        }
        custom = saved
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    val builtIn = ServiceLocator.credentials.builtInCreds()
    val active = custom ?: builtIn
    val idValue = idText.trim().toIntOrNull()
    val idOk = idValue != null && idValue > 0
    val hashOk = ApiHashPattern.matches(hashText.trim())
    val canSave = idOk && hashOk

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("API 凭据", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !loaded -> "读取中…"
                            custom != null -> "当前：自定义凭据"
                            builtIn != null -> "当前：内置凭据"
                            else -> "当前：未配置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            if (loaded) {
                Text(
                    if (active != null) {
                        "api_id = ${active.first} · api_hash = ${maskHash(active.second)}"
                    } else {
                        "尚未配置凭据，登录前必须填写。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "自定义凭据用于自有 Telegram 应用（my.telegram.org 申请）。修改后需重启应用生效，已登录会话可能需重新登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = idText,
                    onValueChange = { idText = it; error = null },
                    label = { Text("api_id（正整数）") },
                    singleLine = true,
                    isError = idText.isNotBlank() && !idOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hashText,
                    onValueChange = { hashText = it; error = null },
                    label = { Text("api_hash（32 位十六进制）") },
                    singleLine = true,
                    isError = hashText.isNotBlank() && !hashOk,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                val hint = when {
                    idText.isNotBlank() && !idOk -> "api_id 必须是正整数"
                    hashText.isNotBlank() && !hashOk -> "api_hash 必须是 32 位十六进制"
                    else -> null
                }
                if (hint != null) {
                    Text(hint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                feedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val id = idText.trim().toIntOrNull()
                            val hash = hashText.trim().lowercase()
                            if (id == null || id <= 0 || !ApiHashPattern.matches(hash)) {
                                error = "请检查 api_id 与 api_hash 格式"
                                return@Button
                            }
                            scope.launch {
                                withContext(Dispatchers.IO) { ServiceLocator.credentials.set(id, hash) }
                                idText = ""
                                hashText = ""
                                error = null
                                feedback = "已保存自定义凭据（api_id=$id），重启应用后生效。"
                                reload()
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                    // release 无内置值：不展示「恢复内置」，否则点击会清空凭据并锁死登录
                    if (builtIn != null) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { ServiceLocator.credentials.clear() }
                                    idText = ""
                                    hashText = ""
                                    error = null
                                    feedback = "已恢复内置凭据，重启应用后生效。"
                                    reload()
                                }
                            },
                            enabled = loaded && custom != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("恢复内置") }
                    }
                }
            }
        }
    }
}



// ---------------- 下载存储位置 ----------------

@Composable
private fun DownloadStoreSection() {
    val scope = rememberCoroutineScope()
    val mode by ServiceLocator.settings.downloadStoreMode.collectAsState(initial = "private")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("下载存储位置", style = MaterialTheme.typography.titleMedium)
            Text(
                "私有：存到应用专属目录，仅本应用可见；公共：写入系统下载目录，文件管理器可见（Android 10+ 走 MediaStore）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice("私有", mode == "private", Modifier.weight(1f)) {
                    scope.launch { ServiceLocator.settings.setDownloadStoreMode("private") }
                }
                ThemeChoice("公共", mode == "public", Modifier.weight(1f)) {
                    scope.launch { ServiceLocator.settings.setDownloadStoreMode("public") }
                }
            }
        }
    }
}

// ---------------- 主题选择 ----------------

@Composable
private fun ThemeSection() {
    val scope = rememberCoroutineScope()
    val mode by ServiceLocator.settings.themeMode.collectAsState(initial = "system")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("主题", style = MaterialTheme.typography.titleMedium)
            Text(
                "跟随系统，或手动锁定深色 / 浅色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice("系统", mode == "system", Modifier.weight(1f)) {
                    scope.launch { ServiceLocator.settings.setThemeMode("system") }
                }
                ThemeChoice("浅色", mode == "light", Modifier.weight(1f)) {
                    scope.launch { ServiceLocator.settings.setThemeMode("light") }
                }
                ThemeChoice("深色", mode == "dark", Modifier.weight(1f)) {
                    scope.launch { ServiceLocator.settings.setThemeMode("dark") }
                }
            }
        }
    }
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}
