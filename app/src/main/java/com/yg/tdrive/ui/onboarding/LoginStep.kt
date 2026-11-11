package com.yg.tgdrive.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.td.AuthState
import com.yg.tgdrive.td.tdObj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录步骤（onboarding 第④步）：随 TdClient.authState 状态机切换
 * 手机号/验证码/2FA/READY；错误用 authErrors Flow 原地提示，3 次失败提示冷却。
 */
@Composable
fun LoginStepContent(onReady: () -> Unit) {
    val authState by ServiceLocator.tdClient.authState.collectAsState()
    // release 构建未内置凭据：getOrBuiltIn() 为 null 时必须先让用户填写，不能 initialize。
    var creds by remember { mutableStateOf(ServiceLocator.credentials.getOrBuiltIn()) }

    // 有凭据才 initialize（幂等，冷启动已跑也安全）；保存新凭据后 key 变化，自动续跑。
    LaunchedEffect(creds) {
        val pair = creds ?: return@LaunchedEffect
        val sl = ServiceLocator
        // 先 initialize 再应用代理：TDLib 未初始化时 addProxy 可能不被应答
        runCatching {
            sl.tdClient.initialize(pair.first, pair.second, sl.tdDatabaseDir, sl.tdFilesDir)
        }
        sl.settings.proxy.first()?.let { snapshot ->
            runCatching { sl.proxyManager.applySnapshot(snapshot, sl.context) }
        }
    }

    if (creds == null) {
        ApiCredentialsStep(
            onSave = { apiId, apiHash ->
                withContext(Dispatchers.IO) { ServiceLocator.credentials.set(apiId, apiHash) }
                creds = apiId to apiHash
            }
        )
        return
    }

    when (val s = authState) {
        is AuthState.Ready -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("登录成功 ✓", style = MaterialTheme.typography.titleLarge)
                Text(
                    "正在进入驱动器选择…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LaunchedEffect(Unit) { onReady() }
        }
        is AuthState.WaitPhoneNumber -> PhoneInput()
        is AuthState.WaitCode -> CodeInput()
        is AuthState.WaitPassword -> PasswordInput()
        is AuthState.Loading -> StepScaffold(title = "正在连接 Telegram…") {
            CircularProgressIndicator()
            Text("请稍候", style = MaterialTheme.typography.bodyMedium)
        }
        is AuthState.Error -> StepScaffold(title = "连接出错") {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            Text(
                "请检查网络或代理后重试；若持续失败可重启应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is AuthState.Closed -> StepScaffold(title = "会话已关闭") {
            Text("请重启应用恢复登录。", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ------------------------------------------------ API 凭据（release 未内置时）

private val ApiHashPattern = Regex("^[0-9a-fA-F]{32}$")

/**
 * release 构建未内置 api 凭据：先让用户用自己的 Telegram 账号申请 app 再填写。
 * 保存成功后由上层写 CredentialsStore 并继续 initialize；校验不通过时禁用保存。
 */
@Composable
private fun ApiCredentialsStep(onSave: suspend (Int, String) -> Unit) {
    var idText by remember { mutableStateOf("") }
    var hashText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val apiId = idText.trim().toIntOrNull()
    val idOk = apiId != null && apiId > 0
    val hashOk = ApiHashPattern.matches(hashText.trim())
    val canSave = idOk && hashOk && !saving

    StepScaffold(title = "填写 API 凭据") {
        Text(
            "当前版本未内置 API 凭据。请用自己的 Telegram 账号登录 my.telegram.org，" +
                "进入「API development tools」创建一个应用，然后复制该应用的 api_id 与 api_hash。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedField(
            value = idText,
            onValueChange = { idText = it },
            label = "api_id（正整数）",
            isNumber = true,
            supportingText = if (idText.isNotBlank() && !idOk) "api_id 必须是正整数" else null,
        )
        OutlinedField(
            value = hashText,
            onValueChange = { hashText = it },
            label = "api_hash（32 位十六进制）",
            isPassword = true,
            supportingText = if (hashText.isNotBlank() && !hashOk) "api_hash 必须是 32 位十六进制" else null,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val id = apiId ?: return@Button
                val hash = hashText.trim().lowercase()
                if (saving || !canSave) return@Button
                saving = true
                scope.launch {
                    // 写盘失败时恢复按钮，避免表单永久卡在「正在保存…」
                    runCatching { onSave(id, hash) }.onFailure { saving = false }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saving) "正在保存…" else "保存并登录") }
        Text(
            "凭据仅加密保存在本机，用于连接你自己的 Telegram 应用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------ 手机号

@Composable
private fun PhoneInput() {
    var phone by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AuthErrorBanner()

    StepScaffold(title = "输入手机号") {
        Text(
            "使用 Telegram 专用小号登录（建议主号不在此设备登录）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedField(
            value = phone,
            onValueChange = { phone = it },
            label = "国际格式手机号，如 +8613800138000",
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (sending) return@Button
                sending = true
                ServiceLocator.tdClient.send(
                    tdObj(
                        "@type" to "setAuthenticationPhoneNumber",
                        "phone_number" to phone.trim(),
                        "allow_flash_call" to false,
                        "is_current_phone_number" to true,
                    )
                )
                // sending 置回：authState 推进（WaitCode/错误）时 when 分支重组自动重置
                ServiceLocator.appScope.launch { delay(1000); sending = false }
            },
            enabled = phone.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在发送…")
            } else {
                Text("发送验证码")
            }
        }
        Text(
            "发送后需连接 Telegram 服务器，可能需要数秒到数十秒，请耐心等待。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------ 验证码

@Composable
private fun CodeInput() {
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AuthErrorBanner()

    StepScaffold(title = "输入验证码") {
        Text(
            "验证码已发送到你的 Telegram 其他客户端或手机短信。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedField(
            value = code,
            onValueChange = { code = it },
            label = "验证码",
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (sending) return@Button
                sending = true
                ServiceLocator.tdClient.send(
                    tdObj("@type" to "checkAuthenticationCode", "code" to code.trim())
                )
                ServiceLocator.appScope.launch { delay(1000); sending = false }
            },
            enabled = code.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在验证…")
            } else {
                Text("登录")
            }
        }
    }
}

// ------------------------------------------------ 2FA 密码

@Composable
private fun PasswordInput() {
    var pass by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AuthErrorBanner()

    StepScaffold(title = "两步验证密码") {
        Text(
            "该账号开启了两步验证，请输入云密码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedField(
            value = pass,
            onValueChange = { pass = it },
            label = "两步验证密码",
            isPassword = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (sending) return@Button
                sending = true
                ServiceLocator.tdClient.send(
                    tdObj("@type" to "checkAuthenticationPassword", "password" to pass)
                )
                ServiceLocator.appScope.launch { delay(1000); sending = false }
            },
            enabled = pass.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在验证…")
            } else {
                Text("确认")
            }
        }
    }
}

// ------------------------------------------------ 错误提示（原地 Toast + 3 次失败冷却文案）

@Composable
private fun AuthErrorBanner() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        var consecutiveFailures = 0
        ServiceLocator.tdClient.authErrors.collect { err ->
            val code = (err["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            // 并发 auth 查询静默：上一个查询还在进行（连 DC 发码需数秒），等它完成即可
            if (code.contains("Another authorization query")) return@collect
            val human = when {
                code.contains("PHONE_CODE_INVALID") -> "验证码错误，请重新输入"
                code.contains("PHONE_NUMBER_INVALID") -> "手机号格式不正确"
                code.contains("PASSWORD_HASH_INVALID") -> "两步验证密码错误"
                code.contains("PHONE_CODE_EXPIRED") -> "验证码已过期，请重新获取"
                code.contains("FLOOD_WAIT") -> "操作过于频繁，请稍后再试"
                else -> "出错了：$code"
            }
            if (code.contains("PHONE_CODE_INVALID") || code.contains("PASSWORD_HASH_INVALID") ||
                code.contains("PHONE_NUMBER_INVALID")
            ) {
                consecutiveFailures++
            }
            val text = if (consecutiveFailures >= 3) {
                "连续失败 $consecutiveFailures 次，请冷却几分钟后再试"
            } else human
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
