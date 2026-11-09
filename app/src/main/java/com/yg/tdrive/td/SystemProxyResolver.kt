package com.yg.tgdrive.td

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo

/**
 * 读取当前默认网络的系统 HTTP 代理，供「跟随系统代理」模式使用。
 *
 * 能力边界（由 TDLib 侧决定，不是实现取舍）：
 * - TDLib 只认 addProxy 显式配置，不会自己读系统代理；这里负责把它翻译成 [ProxyConfig.Http]。
 * - TDLib 只有 proxyTypeHttp / proxyTypeSocks5 / proxyTypeMtproto，没有 PAC 求值器
 *   → PAC（pacFileUrl 非空、host 为空）无法解析，返回 null。
 * - TDLib 无 per-host 绕过能力 → 忽略 ProxyInfo.getExclusionList()，宁可全部走代理。
 *
 * 全程 runCatching：无 ACCESS_NETWORK_STATE、服务缺失、网络切换竞态等任何异常
 * 都退化为 null（调用方据此直连），绝不向上抛。
 */
object SystemProxyResolver {

    /** 当前默认网络的 HTTP 代理；无代理 / 仅 PAC / 不可用时返回 null。 */
    fun resolve(context: Context): ProxyConfig.Http? = runCatching {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null
        // 优先活动网络的 LinkProperties（Wi-Fi/以太网手动代理都会出现在这里），
        // 取不到可用配置再退回 getDefaultProxy()（已废弃，兼容只在全局设置里写代理的旧 ROM）。
        val fromLink = toHttp(cm.activeNetwork?.let { cm.getLinkProperties(it)?.httpProxy })
        if (fromLink != null) return@runCatching fromLink
        toHttp(cm.defaultProxy)
    }.getOrNull()

    private fun toHttp(info: ProxyInfo?): ProxyConfig.Http? {
        if (info == null) return null
        val host = info.host
        // PAC：有脚本地址但没有静态 host，TDLib 无法执行脚本 → 放弃。
        if (info.pacFileUrl != null && host.isNullOrBlank()) return null
        if (host.isNullOrBlank()) return null
        if (!info.isValid) return null
        val port = info.port
        if (port !in 1..65535) return null
        return ProxyConfig.Http(
            server = host,
            port = port,
            // ProxyInfo 的 getUsername/getPassword 是 @hide（非公开 API），拿不到凭据；
            // TDLib 侧留空即可，需要认证的系统代理会 CONNECT 失败 —— 属已知限制。
            user = null,
            pass = null,
        )
    }
}
