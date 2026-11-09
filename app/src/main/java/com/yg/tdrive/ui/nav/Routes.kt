package com.yg.tgdrive.ui.nav

/**
 * 极简路由（一期决策：gradle 无 navigation 依赖，用 mutableStateOf + BackHandler 管理）。
 * route 定义：
 *   "home"                        主页（BottomNav 2 tab，tab 序号由 MainScreen 内部状态）
 *   "settings"                    设置页
 *   "onboarding/<step 1..5>"      五步向导
 */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
}
