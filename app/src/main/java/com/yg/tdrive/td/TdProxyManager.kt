package com.yg.tgdrive.td

import android.content.Context
import com.yg.tgdrive.settings.ProxySnapshot

/**
 * 代理管理：持有当前生效配置并负责 addProxy/testProxy。
 * TDLib 不支持运行时换代理 —— 修改后由 App 重建 TdClient（进程级重启）。
 */
class TdProxyManager(private val td: TdClient) {

    @Volatile var current: ProxyConfig? = null
        private set

    /**
     * 按已保存的偏好应用代理。冷启动/登录链路统一走这里：
     * - null → 直连
     * - [ProxySnapshot.System] → 探测系统 HTTP 代理；探测不到就直连（不报错，静默降级）
     * - 其余 → 直接映射
     *
     * 需要 Context 是因为 [ProxySnapshot.System] 的解析发生在运行时，
     * 而 [ProxySnapshot.toConfig] 拿不到 Context。
     */
    suspend fun applySnapshot(snapshot: ProxySnapshot?, context: Context) {
        when (snapshot) {
            null -> apply(null)
            is ProxySnapshot.System -> apply(SystemProxyResolver.resolve(context))
            else -> apply(snapshot.toConfig())
        }
    }

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
