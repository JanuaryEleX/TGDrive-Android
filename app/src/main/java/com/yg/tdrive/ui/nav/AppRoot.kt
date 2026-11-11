package com.yg.tgdrive.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.td.AuthState
import com.yg.tgdrive.ui.onboarding.OnboardingFlow
import com.yg.tgdrive.ui.download.DownloadScreen
import com.yg.tgdrive.ui.search.SearchScreen
import com.yg.tgdrive.ui.settings.SettingsScreen
import com.yg.tgdrive.ui.wall.MainScreen
import kotlinx.coroutines.flow.first

/**
 * 应用根路由（一期：mutableStateOf 路由 + BackHandler，不用 androidx.navigation）。
 * - 启动读 SettingsStore.onboarded → 未引导走 OnboardingFlow（5 步），完成后进主页
 * - 已引导进主页；主页内容在 authState != Ready 时锁定（全屏等待层，由 MainScreen 渲染）
 */
@Composable
fun AppRoot() {
    var route by remember { mutableStateOf<String?>(null) } // null = 还在读 onboarded

    LaunchedEffect(Unit) {
        val onboarded = ServiceLocator.settings.onboarded.first()
        route = if (onboarded) Routes.HOME else Routes.ONBOARDING
    }

    when (val r = route) {
        null -> Box(Modifier.fillMaxSize())
        Routes.ONBOARDING -> {
            // 首次引导：屏蔽返回键，防误触退出向导（OnboardingFlow 内部也屏蔽）
            OnboardingFlow(onFinished = { route = Routes.HOME })
        }
        Routes.SETTINGS -> SettingsScreen(
            onBack = { route = Routes.HOME },
        )
        Routes.SEARCH -> SearchScreen(onBack = { route = Routes.HOME })
        Routes.DOWNLOAD -> DownloadScreen(onBack = { route = Routes.HOME })
        else -> MainScreen(
            onOpenSettings = { route = Routes.SETTINGS },
            onOpenSearch = { route = Routes.SEARCH },
            onOpenDownload = { route = Routes.DOWNLOAD },
        )
    }
}

/** 主页鉴权锁定文案（MainScreen 渲染锁定层时使用）。 */
fun authLockMessage(state: AuthState): String = when (state) {
    is AuthState.Ready -> ""
    is AuthState.Loading -> "正在连接 Telegram…"
    is AuthState.WaitPhoneNumber, is AuthState.WaitCode, is AuthState.WaitPassword ->
        "登录会话异常，请到设置中重新登录"
    is AuthState.Error -> "连接出错：${state.message}"
    is AuthState.Closed -> "会话已关闭，重启应用以恢复"
}
