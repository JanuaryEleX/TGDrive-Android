package com.yg.tgdrive.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            TgTheme {
                AppRoot()
            }
        }
    }
}
