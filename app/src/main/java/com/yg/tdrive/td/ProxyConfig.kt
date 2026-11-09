package com.yg.tgdrive.td

import kotlinx.serialization.json.JsonObject

/**
 * 代理配置（SPEC §4.1）：MTProto / SOCKS5 / HTTP，null = 直连。
 * TDLib 不支持运行时换代理，变更后需重建 client（App 级重启）。
 */
sealed interface ProxyConfig {
    val server: String
    val port: Int

    data class MtProto(override val server: String, override val port: Int, val secret: String) : ProxyConfig
    data class Socks5(override val server: String, override val port: Int, val user: String?, val pass: String?) : ProxyConfig

    /**
     * HTTP 代理（TDLib 1.8.65 的 proxyTypeHttp）。
     * 系统代理（[SystemProxyResolver]）只能以这种形式交给 TDLib：
     * 不支持 PAC，且 TDLib 的 HTTP 代理仅用于 CONNECT 隧道。
     */
    data class Http(
        override val server: String,
        override val port: Int,
        val user: String?,
        val pass: String?,
    ) : ProxyConfig

    fun toTdObject(): JsonObject = when (this) {
        is MtProto -> tdObj(
            "@type" to "proxyTypeMtproto",
            "secret" to secret
        )
        is Socks5 -> tdObj(
            "@type" to "proxyTypeSocks5",
            "username" to (user ?: ""),
            "password" to (pass ?: "")
        )
        is Http -> tdObj(
            "@type" to "proxyTypeHttp",
            "username" to (user ?: ""),
            "password" to (pass ?: ""),
            "http_only" to false
        )
    }
}
