package com.yg.tgdrive.ui.common

/**
 * 代理编辑器的三态选择（onboarding 第③步与设置页共用）。
 * Skip 仅用于 onboarding「跳过（直连）」入口；设置页见 None。
 */
enum class ProxyMode { Skip, MTProto, Socks5, None }
