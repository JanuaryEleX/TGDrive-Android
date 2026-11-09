package com.yg.tgdrive.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.settings.ProxySnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 代理编辑组件（onboarding 第③步 + 设置页复用）。
 * - 三选：MTProto(server/port/secret) / SOCKS5(server/port/user/pass) / 直连
 * - 「测试延迟」调 TdProxyManager.test 显示 ms
 * - 保存写 SettingsStore.setProxy（proxyManager.apply 由 lead 在登录/启动链路统一处理）
 * - 设置页保存后提示重启（TDLib 不支持运行时换代理）
 */
@Composable
fun ProxyEditor(
    initialMode: ProxyMode,
    onSaved: () -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var restartHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 设置页进入时回填当前已存代理（onboarding 进入时为空）
    LaunchedEffect(Unit) {
        if (initialMode != ProxyMode.Skip) {
            when (val p = ServiceLocator.settings.proxy.first()) {
                null -> mode = ProxyMode.None
                is ProxySnapshot.Mt -> {
                    mode = ProxyMode.MTProto
                    server = p.server; port = p.port.toString(); secret = p.secret
                }
                is ProxySnapshot.Socks -> {
                    mode = ProxyMode.Socks5
                    server = p.server; port = p.port.toString()
                    user = p.user ?: ""; pass = p.pass ?: ""
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ProxyMode.MTProto,
                onClick = { mode = ProxyMode.MTProto; testResult = null; error = null },
                label = { Text("MTProto") },
            )
            FilterChip(
                selected = mode == ProxyMode.Socks5,
                onClick = { mode = ProxyMode.Socks5; testResult = null; error = null },
                label = { Text("SOCKS5") },
            )
            FilterChip(
                selected = mode == ProxyMode.Skip || mode == ProxyMode.None,
                onClick = {
                    mode = if (initialMode == ProxyMode.Skip) ProxyMode.Skip else ProxyMode.None
                    testResult = null; error = null
                },
                label = { Text("直连") },
            )
        }

        when (mode) {
            ProxyMode.MTProto -> {
                LabeledField("服务器地址", server) { server = it }
                LabeledField("端口", port, isNumber = true) { port = it }
                LabeledField("Secret（16/32 位十六进制）", secret) { secret = it }
            }
            ProxyMode.Socks5 -> {
                LabeledField("服务器地址", server) { server = it }
                LabeledField("端口", port, isNumber = true) { port = it }
                LabeledField("用户名（可空）", user) { user = it }
                LabeledField("密码（可空）", pass, isPassword = true) { pass = it }
            }
            ProxyMode.Skip, ProxyMode.None -> {
                Text(
                    "不使用代理，直连 Telegram。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (mode == ProxyMode.MTProto || mode == ProxyMode.Socks5) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val p = port.toIntOrNull()
                        val valid = !server.isBlank() && p != null &&
                            (mode == ProxyMode.Socks5 || secret.isNotBlank())
                        if (!valid) {
                            error = "请完整填写服务器、端口" + if (mode == ProxyMode.MTProto) "与 secret" else ""
                        } else {
                            error = null
                            testing = true
                            testResult = null
                            scope.launch {
                                val cfg = if (mode == ProxyMode.MTProto) {
                                    com.yg.tgdrive.td.ProxyConfig.MtProto(server.trim(), p!!, secret.trim())
                                } else {
                                    com.yg.tgdrive.td.ProxyConfig.Socks5(
                                        server.trim(), p!!,
                                        user.trim().ifBlank { null },
                                        pass.ifBlank { null },
                                    )
                                }
                                val ms = ServiceLocator.proxyManager.test(cfg)
                                testing = false
                                testResult = if (ms != null) "延迟 $ms ms" else "测试失败，请检查配置"
                            }
                        }
                    },
                    enabled = !testing,
                ) { Text(if (testing) "测试中…" else "测试延迟") }
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.startsWith("延迟")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                if (testing) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }

        if (restartHint) {
            Text(
                "代理已保存。TDLib 不支持运行时换代理，重启应用后生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Button(
            onClick = {
                val snapshot = when (mode) {
                    ProxyMode.MTProto -> {
                        val p = port.toIntOrNull()
                        when {
                            server.isBlank() || p == null || secret.isBlank() -> {
                                error = "请完整填写服务器、端口与 secret"
                                return@Button
                            }
                            else -> ProxySnapshot.Mt(server.trim(), p, secret.trim())
                        }
                    }
                    ProxyMode.Socks5 -> {
                        val p = port.toIntOrNull()
                        when {
                            server.isBlank() || p == null -> {
                                error = "请完整填写服务器与端口"
                                return@Button
                            }
                            else -> ProxySnapshot.Socks(
                                server.trim(), p,
                                user.trim().ifBlank { null },
                                pass.ifBlank { null },
                            )
                        }
                    }
                    else -> null
                }
                scope.launch {
                    ServiceLocator.settings.setProxy(snapshot)
                    restartHint = initialMode != ProxyMode.Skip && snapshot != null
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (mode) {
                    ProxyMode.MTProto -> "保存 MTProto 代理"
                    ProxyMode.Socks5 -> "保存 SOCKS5 代理"
                    else -> if (initialMode == ProxyMode.Skip) "跳过（直连）" else "保存为直连"
                },
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    isNumber: Boolean = false,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
