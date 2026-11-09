package com.yg.tgdrive.data

import com.yg.tgdrive.td.TdClient
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TdClient.call 对 TDLib error 的处理约定（td/TdClient.dispatch 实测确认）：
 * error 响应带 @extra，走 pending.remove(extra)?.complete(obj) 正常配对返回，
 * 不抛异常。本层数据面代码统一用 [tdOrThrow] 把 error 对象转成异常，
 * 保证扫描/删除等链路的失败可见。
 *
 * FLOOD_WAIT_X 错误的 message 形如 "Too Many Requests; retry after 3 (FLOOD_WAIT_3)"，
 * 解析逻辑见 MediaRepository.callWithFloodRetry（SPEC §8：严格 sleep 后重试，>120s 上抛）。
 */
class TdRequestException(val code: Int, message: String) :
    Exception("td error $code: $message")

/** TDLib 返回 error 对象时抛出 [TdRequestException]，否则原样返回。 */
fun JsonObject.tdOrThrow(): JsonObject {
    if ((this["@type"] as? JsonPrimitive)?.content == "error") {
        throw TdRequestException(
            (this["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            (this["message"] as? JsonPrimitive)?.content ?: "unknown",
        )
    }
    return this
}

/**
 * 标准 TD 调用入口：SPEC §4.1 的 30s 超时由调用方负责（TdClient.call 无内建超时），
 * 此扩展统一包 withTimeout(30s) + error→异常转换。数据层所有请求走这里。
 */
suspend fun TdClient.tdCall(query: JsonObject, timeoutMs: Long = TD_CALL_TIMEOUT_MS): JsonObject =
    withTimeout(timeoutMs) { call(query).tdOrThrow() }

/** SPEC §4.1 默认请求超时。 */
const val TD_CALL_TIMEOUT_MS = 30_000L
