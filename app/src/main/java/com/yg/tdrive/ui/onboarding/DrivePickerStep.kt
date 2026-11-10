package com.yg.tgdrive.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.td.tdObj
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ⑤ 驱动器勾选：getChats(mainChatList) + getChat 拉聊天列表，
 * 过滤 private channel / megagroup / basicGroup，Saved Messages(chat_id=me) 特判；
 * 多选写入 DriveManager.addDrive(chatId, title) → setOnboarded → 主页。
 */
@Composable
fun DrivePickerStep(onFinished: () -> Unit) {
    data class ChatOption(val chatId: Long, val title: String, val subtitle: String)

    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<List<ChatOption>>(emptyList()) }
    val checked = remember { mutableStateMapOf<Long, Boolean>() }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val td = ServiceLocator.tdClient
            // 拉主聊天列表（chat_ids 有序）
            val chatsResp = td.call(tdObj("@type" to "getChats", "limit" to 100))
            val chatIds = chatsResp["chat_ids"]?.jsonArray?.map { it.jsonPrimitive.longOrNull() ?: 0L }
                ?: emptyList()

            val meResp = td.call(tdObj("@type" to "getMe"))
            val myId = meResp["id"]?.jsonPrimitive?.longOrNull() ?: 0L

            val list = mutableListOf<ChatOption>()
            for (id in chatIds) {
                // 每个请求间隔防风控
                delay(50)
                val chat = td.call(tdObj("@type" to "getChat", "chat_id" to id))
                val type = chat["type"] as? JsonObject ?: continue
                val t = type["@type"]?.jsonPrimitive?.content ?: continue
                val isSavedMessages = id == myId
                val selectable = isSavedMessages ||
                    t == "chatTypePrivateChannel" ||
                    t == "chatTypeSupergroup" ||
                    t == "chatTypeBasicGroup"
                if (!selectable) continue
                val title = if (isSavedMessages) {
                    "收藏（Saved Messages）"
                } else {
                    chat["title"]?.jsonPrimitive?.content
                        ?: chat["first_name"]?.jsonPrimitive?.content ?: "未知聊天"
                }
                val subtitle = when {
                    isSavedMessages -> "与本机账号的私人存储对话"
                    t == "chatTypePrivateChannel" -> "私有频道"
                    t == "chatTypeSupergroup" -> "超级群组"
                    else -> "普通群组"
                }
                list += ChatOption(id, title, subtitle)
            }
            options = list
            loading = false
        } catch (t: Throwable) {
            loadError = "拉取聊天列表失败：${t.message ?: t::class.java.simpleName}"
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("选择网盘驱动器", style = MaterialTheme.typography.titleLarge)
        Text(
            "勾选用作存储的频道/群组（建议使用专用小号的私有频道）。" +
                "勾选后将自动执行首次全量扫描建索引。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 24.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("正在拉取聊天列表…")
            }
            loadError != null -> Text(
                loadError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(options, key = { it.chatId }) { opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = checked[opt.chatId] == true,
                                onCheckedChange = { checked[opt.chatId] = it },
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(opt.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    opt.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                saving = true
                scope.launch {
                    try {
                        val added = options.filter { checked[it.chatId] == true }.map { opt ->
                            ServiceLocator.driveManager.addDrive(opt.chatId, opt.title)
                        }
                        // 新建驱动器立即首扫：否则海报墙要等用户手动下拉才出内容
                        added.forEach { drive ->
                            runCatching {
                                ServiceLocator.mediaRepository.fullScan(drive.id)
                            }
                        }
                        onFinished()
                    } catch (t: Throwable) {
                        loadError = "保存驱动器失败：${t.message}"
                        saving = false
                    }
                }
            },
            enabled = !loading && !saving && checked.any { it.value },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(if (saving) "正在创建驱动器…" else "完成")
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.longOrNull(): Long? =
    (this as? JsonPrimitive)?.content?.toLongOrNull()
