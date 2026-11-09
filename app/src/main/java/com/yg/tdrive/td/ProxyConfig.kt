package com.yg.tgdrive.td

import kotlinx.serialization.json.JsonObject

/**
 * 代理配置（SPEC §4.1）：MTProto 或 SOCKS5，null = 直连。
 * TDLib 不支持运行时换代理，变更后需重建 client（App 级重启）。
 */
sealed interface ProxyConfig {
    val server: String
    val port: Int

    data class MtProto(override val server: String, override val port: Int, val secret: String) : ProxyConfig
    data class Socks5(override val server: String, override val port: Int, val user: String?, val pass: String?) : ProxyConfig

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
    }
}
