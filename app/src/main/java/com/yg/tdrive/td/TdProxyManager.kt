package com.yg.tgdrive.td

/**
 * 代理管理：持有当前生效配置并负责 addProxy/testProxy。
 * TDLib 不支持运行时换代理 —— 修改后由 App 重建 TdClient（进程级重启）。
 */
class TdProxyManager(private val td: TdClient) {

    @Volatile var current: ProxyConfig? = null
        private set

    /** 添加并启用代理；proxy == null 时禁用全部（直连）。 */
    suspend fun apply(proxy: ProxyConfig?) {
        current = proxy
        if (proxy == null) {
            td.call(tdObj("@type" to "disableProxy"))
            return
        }
        td.call(
            tdObj(
                "@type" to "addProxy",
                "server" to proxy.server,
                "port" to proxy.port,
                "enable" to true,
                "type" to proxy.toTdObject()
            )
        )
    }

    /** 测试代理连通性，返回延迟 ms；失败返回 null。 */
    suspend fun test(proxy: ProxyConfig): Long? = try {
        val r = td.call(
            tdObj(
                "@type" to "testProxy",
                "server" to proxy.server,
                "port" to proxy.port,
                "type" to proxy.toTdObject(),
                "dc_id" to 2    // Telegram 主 DC（Ashburn），经代理测往返
            )
        )
        (r["milliseconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong()
    } catch (t: Throwable) {
        null
    }
}
