package com.yg.tgdrive.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yg.tgdrive.ui.common.TgTheme
import com.yg.tgdrive.ui.nav.AppRoot

/**
 * 单 Activity Compose 宿主（SPEC §4.5）。
 * 启动时按 SettingsStore.onboarded 决定进入 onboarding 或主页（AppRoot 内决策）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 沉浸式/全屏播放页可能修改了系统栏状态；返回主页时明确恢复状态栏。
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.statusBars())
        setContent {
            TgTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 返回主页时再次确认状态栏可见，不影响播放器 Activity 自己的全屏行为。
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.statusBars())
    }
}
