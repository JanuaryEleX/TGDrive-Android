package com.yg.tgdrive.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.ui.common.ProxyEditor
import com.yg.tgdrive.ui.common.ProxyMode
import kotlinx.coroutines.launch

/**
 * 四步向导（SPEC §4.5 简化版）：凭据内置（Telegram Desktop 公开值）零填写，
 * 主流程 = 欢迎 → 代理（可选）→ 登录 → 驱动器勾选。
 * 引导期间屏蔽系统返回（防误触退出后状态半途丢失）。
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = true) { /* 引导期间不响应返回 */ }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0D12),
                        Color(0xFF0E141B),
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            LinearProgressIndicator(
                progress = { step / 4f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding-step",
            ) { s ->
                when (s) {
                    1 -> WelcomeStep(onNext = { step = 2 })
                    2 -> ProxyStep(
                        onDone = { step = 3 },
                        onSkip = { step = 3 },
                    )
                    3 -> LoginStep(onReady = { step = 4 })
                    else -> DrivesStep(
                        onFinished = {
                            scope.launch {
                                ServiceLocator.settings.setOnboarded(true)
                                onFinished()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ① 欢迎/免责

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepScaffold(title = "欢迎使用 TG 云盘") {
        Text(
            "用 Telegram 专用小号的私有频道，当作你的私人无限网盘。" +
                "视频秒开流播、缩略图海报墙、全局搜索，全程无自建服务端。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "API 凭据已内置，无需申请——直接登录即可使用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "免责声明",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "TG 云端无 SLA，请为重要内容保留独立备份；本应用仅供个人自用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("开始配置")
        }
    }
}

// ---------------------------------------------------------------- ② 代理

@Composable
private fun ProxyStep(onDone: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("代理设置（可选）", style = MaterialTheme.typography.titleLarge)
        Text(
            "如所在网络无法直连 Telegram，可配置 MTProto 或 SOCKS5 代理；" +
                "也可以先跳过，之后在设置页修改（修改代理需重启应用生效）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProxyEditor(
            initialMode = ProxyMode.Direct,
            onSaved = { onDone() },
            isOnboarding = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("跳过（直连）")
        }
    }
}

// ---------------------------------------------------------------- ③ 登录

@Composable
private fun LoginStep(onReady: () -> Unit) {
    LoginStepContent(onReady = onReady)
}

// ---------------------------------------------------------------- ④ 驱动器勾选

@Composable
private fun DrivesStep(onFinished: () -> Unit) {
    DrivePickerStep(onFinished = onFinished)
}

/** 四步通用版式：标题 + 滚动内容。 */
@Composable
internal fun StepScaffold(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}
