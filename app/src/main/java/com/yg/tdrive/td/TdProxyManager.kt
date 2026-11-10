package com.yg.tgdrive.td

import android.content.Context
import com.yg.tgdrive.settings.ProxySnapshot
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 代理管理：持有当前生效配置并负责 addProxy/pingProxy。
 * TDLib 不支持运行时换代理 —— 修改后由 App 重建 TdClient（进程级重启）。
 *
 * ## addProxy 的真实签名（td_api.tl）
 * ```
 * addProxy proxy:proxy enable:Bool comment:string = AddedProxy;
 * proxy server:string port:int32 type:ProxyType = Proxy;
 * ```
 * server/port/type 必须嵌在 `proxy` 对象里，**不能平铺**。
 * 平铺时 TDLib 解析不到 `proxy` 字段 → proxy == nullptr →
 * 400 "Proxy must be non-empty"（Proxy::create_proxy）。
 *
 * ## 测延迟用 pingProxy，不是 testProxy
 * ```
 * pingProxy proxy:proxy = Seconds;          // 返回秒数
 * testProxy proxy:proxy dc_id:int32 timeout:double = Ok;  // 只回 Ok，无延迟值
 * ```
 */
class TdProxyManager(private val td: TdClient) {

    @Volatile var current: ProxyConfig? = null
        private set

    /**
     * 按已保存的偏好应用代理。冷启动/登录链路统一走这里：
     * - null → 直连
     * - [ProxySnapshot.System] → 探测系统 HTTP 代理；探测不到就直连（不报错，静默降级）
     * - 其余 → 直接映射
     */
    suspend fun applySnapshot(snapshot: ProxySnapshot?, context: Context) {
        when (snapshot) {
            null -> apply(null)
            is ProxySnapshot.System -> apply(SystemProxyResolver.resolve(context))
            else -> apply(snapshot.toConfig())
        }
    }

    /**
     * 添加并启用代理；proxy == null 时禁用全部（直连）。
     *
     * 全部请求都包超时：TDLib 在收到 setTdlibParameters 之前可能不应答 addProxy，
     * 无超时的 await 会永久挂起并堵死调用方协程（曾导致冷启动永远停在"正在连接"）。
     */
    suspend fun apply(proxy: ProxyConfig?) {
        current = proxy
        withTimeoutOrNull(PROXY_CALL_TIMEOUT_MS) {
            if (proxy == null) {
                td.call(tdObj("@type" to "disableProxy"))
            } else {
                td.call(
                    tdObj(
                        "@type" to "addProxy",
                        "proxy" to proxy.toTdProxy(),
                        "enable" to true,
                        "comment" to "",
                    )
                )
            }
        }
    }

    /** 经代理 ping 主 DC，返回延迟 ms；失败/超时返回 null。 */
    suspend fun test(proxy: ProxyConfig): Long? = try {
        val r = withTimeoutOrNull(PROXY_CALL_TIMEOUT_MS) {
            td.call(
                tdObj(
                    "@type" to "pingProxy",
                    "proxy" to proxy.toTdProxy(),
                )
            )
        }
        (r?.get("seconds") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0 }
            ?.let { (it * 1000).toLong() }
    } catch (t: Throwable) {
        null
    }

    private companion object {
        /** 代理类请求的等待上限；超时即放弃（冷启动不应被代理阻塞）。 */
        const val PROXY_CALL_TIMEOUT_MS = 20_000L
    }
}
