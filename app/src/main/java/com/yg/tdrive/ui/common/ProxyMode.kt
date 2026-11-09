package com.yg.tgdrive.ui.common

/**
 * 代理编辑器的选择态（onboarding 第③步与设置页共用）。
 * - [Direct] 直连；onboarding 里用 `isOnboarding = true` 把文案渲染成「跳过（直连）」
 * - [System] 跟随系统 HTTP 代理（运行时探测，见 SystemProxyResolver）
 */
enum class ProxyMode { MTProto, Socks5, System, Direct }
